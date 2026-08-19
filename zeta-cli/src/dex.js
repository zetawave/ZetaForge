/**
 * Just enough DEX parsing to answer one question: which classes are defined in
 * this file?
 *
 * Only the header, the string table, the type table and the class definitions
 * are read — that is all it takes to verify an entry point exists and that no
 * shared class was bundled by mistake, and it costs about a millisecond.
 *
 * Format reference: https://source.android.com/docs/core/runtime/dex-format
 */
import { ZetaError } from "./errors.js";

const MAGIC = Buffer.from("dex\n");
const HEADER_SIZE = 112;

/** Reads a ULEB128 at `offset`. @returns {[value, nextOffset]} */
function uleb128(buffer, offset) {
  let result = 0;
  let shift = 0;
  for (;;) {
    const byte = buffer[offset++];
    result |= (byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) return [result >>> 0, offset];
    shift += 7;
    if (shift > 28) return [result >>> 0, offset];
  }
}

function checkMagic(buffer) {
  if (buffer.length < HEADER_SIZE || !buffer.subarray(0, 4).equals(MAGIC)) {
    throw new ZetaError("The produced file is not a DEX file.", {
      hint: "The dexer wrote something unexpected. Run `zeta build --verbose` and report the output.",
    });
  }
}

/** The three-digit format version, e.g. "039". */
export function dexVersion(buffer) {
  checkMagic(buffer);
  return buffer.toString("latin1", 4, 7);
}

/**
 * @param {Buffer} buffer a classes.dex
 * @returns {string[]} type descriptors defined here, e.g. "Lcom/example/Foo;"
 */
export function readDexClasses(buffer) {
  checkMagic(buffer);

  const stringIdsSize = buffer.readUInt32LE(56);
  const stringIdsOff = buffer.readUInt32LE(60);
  const typeIdsSize = buffer.readUInt32LE(64);
  const typeIdsOff = buffer.readUInt32LE(68);
  const classDefsSize = buffer.readUInt32LE(96);
  const classDefsOff = buffer.readUInt32LE(100);

  const strings = new Array(stringIdsSize);
  for (let i = 0; i < stringIdsSize; i++) {
    const dataOff = buffer.readUInt32LE(stringIdsOff + i * 4);
    const [length, start] = uleb128(buffer, dataOff);
    // MUTF-8, but class descriptors are ASCII in practice.
    strings[i] = buffer.toString("utf8", start, start + length);
  }

  const types = new Array(typeIdsSize);
  for (let i = 0; i < typeIdsSize; i++) {
    types[i] = strings[buffer.readUInt32LE(typeIdsOff + i * 4)];
  }

  const classes = [];
  for (let i = 0; i < classDefsSize; i++) {
    classes.push(types[buffer.readUInt32LE(classDefsOff + i * 32)]);
  }
  return classes;
}
