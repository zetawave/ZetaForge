/**
 * The ZIP writer is the one piece here that Android will reject silently if it
 * is even slightly wrong, so it is the piece with tests.
 */
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { ZipWriter, readZip } from "../src/zip.js";

function tempFile(name) {
  return path.join(fs.mkdtempSync(path.join(os.tmpdir(), "zeta-test-")), name);
}

test("round-trips text and binary entries", () => {
  const file = tempFile("a.zip");
  const binary = Buffer.from([0, 1, 2, 253, 254, 255, 0, 0, 42]);

  new ZipWriter()
    .add("manifest.json", '{"hello":"world"}')
    .add("dex/classes.dex", binary)
    .writeTo(file);

  const entries = readZip(file);
  assert.equal(entries.size, 2);
  assert.equal(entries.get("manifest.json").toString("utf8"), '{"hello":"world"}');
  assert.deepEqual([...entries.get("dex/classes.dex")], [...binary]);
});

test("stores incompressible data without growing it", () => {
  const file = tempFile("b.zip");
  const random = Buffer.from(Array.from({ length: 4096 }, (_, i) => (i * 7919) % 256));

  new ZipWriter().add("random.bin", random).writeTo(file);

  assert.deepEqual([...readZip(file).get("random.bin")], [...random]);
});

test("survives an empty entry and a long path", () => {
  const file = tempFile("c.zip");
  const longPath = `source/${"deep/".repeat(30)}File.kt`;

  new ZipWriter().add("empty.txt", "").add(longPath, "class A").writeTo(file);

  const entries = readZip(file);
  assert.equal(entries.get("empty.txt").length, 0);
  assert.equal(entries.get(longPath).toString("utf8"), "class A");
});

test("keeps UTF-8 names intact", () => {
  const file = tempFile("d.zip");
  new ZipWriter().add("assets/città-più-blu.txt", "ciao").writeTo(file);

  const entries = readZip(file);
  assert.equal(entries.get("assets/città-più-blu.txt").toString("utf8"), "ciao");
});

test("refuses a duplicate entry", () => {
  const zip = new ZipWriter().add("a.txt", "one");
  assert.throws(() => zip.add("a.txt", "two"), /Duplicate entry/);
});

test("is byte-for-byte reproducible", () => {
  const build = () =>
    new ZipWriter().add("manifest.json", "{}").add("dex/classes.dex", Buffer.from([1, 2, 3])).toBuffer();

  assert.deepEqual([...build()], [...build()]);
});

test("rejects a file that is not a ZIP", () => {
  const file = tempFile("e.zip");
  fs.writeFileSync(file, "not a zip at all");
  assert.throws(() => readZip(file), /not a valid ZIP/i);
});
