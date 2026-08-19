/**
 * A small, dependency-free ZIP writer and reader.
 *
 * A `.zeta` is a ZIP, and it is read on the device by `java.util.zip`, which is
 * stricter than most desktop tools: a file that opens fine in Explorer can still
 * be rejected by Android. So this writes the format by the book — local header,
 * data, central directory, end-of-central-directory — rather than shelling out
 * to whatever archiver happens to be installed.
 *
 * Deliberately not supported: ZIP64, encryption, data descriptors. A plugin
 * package that needs any of those is not a plugin package.
 */
import zlib from "node:zlib";
import fs from "node:fs";
import { ZetaError } from "./errors.js";

const SIG_LOCAL = 0x04034b50;
const SIG_CENTRAL = 0x02014b50;
const SIG_EOCD = 0x06054b50;
const MAX_UINT32 = 0xffffffff;

/** CRC-32, table-driven. Java checks it, so it has to be right. */
const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buffer) {
  let crc = -1;
  for (let i = 0; i < buffer.length; i++) {
    crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buffer[i]) & 0xff];
  }
  return (crc ^ -1) >>> 0;
}

/** MS-DOS date/time, which is what the format stores. */
function dosDateTime(date) {
  const year = Math.max(1980, date.getFullYear());
  return {
    time:
      (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1),
    date:
      ((year - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate(),
  };
}

export class ZipWriter {
  /**
   * @param {object} [options]
   * @param {Date} [options.modified] one timestamp for every entry, which makes
   *   the output byte-for-byte reproducible across machines.
   */
  constructor(options = {}) {
    this.entries = [];
    this.chunks = [];
    this.offset = 0;
    this.modified = options.modified || new Date(2020, 0, 1, 0, 0, 0);
  }

  /**
   * @param {string} name path inside the archive, always with forward slashes
   * @param {Buffer|string} content
   * @param {object} [options]
   * @param {boolean} [options.store] skip compression (for already-compressed data)
   */
  add(name, content, options = {}) {
    const data = Buffer.isBuffer(content) ? content : Buffer.from(content, "utf8");
    const entryName = name.replace(/\\/g, "/");

    if (this.entries.some((e) => e.name === entryName)) {
      throw new ZetaError(`Duplicate entry in package: ${entryName}`, {
        hint: "Two files would land on the same path inside the .zeta.",
      });
    }
    if (data.length > MAX_UINT32) {
      throw new ZetaError(`Entry too large for a .zeta: ${entryName}`, {
        hint: "A single file inside the package must stay under 4 GB.",
      });
    }

    const compressed = options.store ? data : zlib.deflateRawSync(data, { level: 9 });
    // Compression that made the file bigger is compression not worth doing.
    const useDeflate = !options.store && compressed.length < data.length;
    const payload = useDeflate ? compressed : data;
    const method = useDeflate ? 8 : 0;

    const { time, date } = dosDateTime(this.modified);
    const nameBytes = Buffer.from(entryName, "utf8");
    const crc = crc32(data);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(SIG_LOCAL, 0);
    local.writeUInt16LE(20, 4);            // version needed
    local.writeUInt16LE(0x0800, 6);        // flags: UTF-8 names
    local.writeUInt16LE(method, 8);
    local.writeUInt16LE(time, 10);
    local.writeUInt16LE(date, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(payload.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    local.writeUInt16LE(0, 28);            // no extra field

    this.entries.push({
      name: entryName,
      nameBytes,
      crc,
      method,
      time,
      date,
      compressedSize: payload.length,
      size: data.length,
      offset: this.offset,
    });

    this.chunks.push(local, nameBytes, payload);
    this.offset += local.length + nameBytes.length + payload.length;
    return this;
  }

  /** Adds a file from disk, keeping its bytes exactly. */
  addFile(name, filePath, options) {
    return this.add(name, fs.readFileSync(filePath), options);
  }

  /** @returns {Buffer} the complete archive */
  toBuffer() {
    if (this.entries.length > 0xffff) {
      throw new ZetaError("Too many files in the package (limit is 65535).", {
        hint: "Bundle assets into fewer files.",
      });
    }
    const central = [];
    let centralSize = 0;

    for (const e of this.entries) {
      const header = Buffer.alloc(46);
      header.writeUInt32LE(SIG_CENTRAL, 0);
      header.writeUInt16LE(20, 4);         // version made by
      header.writeUInt16LE(20, 6);         // version needed
      header.writeUInt16LE(0x0800, 8);     // flags: UTF-8 names
      header.writeUInt16LE(e.method, 10);
      header.writeUInt16LE(e.time, 12);
      header.writeUInt16LE(e.date, 14);
      header.writeUInt32LE(e.crc, 16);
      header.writeUInt32LE(e.compressedSize, 20);
      header.writeUInt32LE(e.size, 24);
      header.writeUInt16LE(e.nameBytes.length, 28);
      header.writeUInt16LE(0, 30);         // extra length
      header.writeUInt16LE(0, 32);         // comment length
      header.writeUInt16LE(0, 34);         // disk number
      header.writeUInt16LE(0, 36);         // internal attributes
      header.writeUInt32LE(0o644 << 16, 38); // external attributes: regular file
      header.writeUInt32LE(e.offset, 42);
      central.push(header, e.nameBytes);
      centralSize += header.length + e.nameBytes.length;
    }

    const eocd = Buffer.alloc(22);
    eocd.writeUInt32LE(SIG_EOCD, 0);
    eocd.writeUInt16LE(0, 4);              // this disk
    eocd.writeUInt16LE(0, 6);              // disk with central directory
    eocd.writeUInt16LE(this.entries.length, 8);
    eocd.writeUInt16LE(this.entries.length, 10);
    eocd.writeUInt32LE(centralSize, 12);
    eocd.writeUInt32LE(this.offset, 16);
    eocd.writeUInt16LE(0, 20);             // comment length

    return Buffer.concat([...this.chunks, ...central, eocd]);
  }

  writeTo(filePath) {
    const buffer = this.toBuffer();
    fs.writeFileSync(filePath, buffer);
    return buffer.length;
  }
}

/**
 * Reads an archive by its central directory — the authoritative index, and the
 * one Java trusts. Returns a map of name -> Buffer.
 */
export function readZip(filePath) {
  const buffer = fs.readFileSync(filePath);
  let eocd = -1;
  for (let i = buffer.length - 22; i >= 0 && i > buffer.length - 65558; i--) {
    if (buffer.readUInt32LE(i) === SIG_EOCD) { eocd = i; break; }
  }
  if (eocd < 0) {
    throw new ZetaError(`Not a valid ZIP archive: ${filePath}`, {
      hint: "The end-of-central-directory record is missing. The file is truncated or is not a .zeta.",
    });
  }

  const count = buffer.readUInt16LE(eocd + 10);
  let pointer = buffer.readUInt32LE(eocd + 16);
  const files = new Map();

  for (let i = 0; i < count; i++) {
    if (buffer.readUInt32LE(pointer) !== SIG_CENTRAL) {
      throw new ZetaError(`Corrupt central directory in ${filePath}`);
    }
    const method = buffer.readUInt16LE(pointer + 10);
    const compressedSize = buffer.readUInt32LE(pointer + 20);
    const nameLength = buffer.readUInt16LE(pointer + 28);
    const extraLength = buffer.readUInt16LE(pointer + 30);
    const commentLength = buffer.readUInt16LE(pointer + 32);
    const localOffset = buffer.readUInt32LE(pointer + 42);
    const name = buffer.toString("utf8", pointer + 46, pointer + 46 + nameLength);

    const localNameLength = buffer.readUInt16LE(localOffset + 26);
    const localExtraLength = buffer.readUInt16LE(localOffset + 28);
    const dataStart = localOffset + 30 + localNameLength + localExtraLength;
    const raw = buffer.subarray(dataStart, dataStart + compressedSize);

    files.set(name, method === 8 ? zlib.inflateRawSync(raw) : Buffer.from(raw));
    pointer += 46 + nameLength + extraLength + commentLength;
  }
  return files;
}
