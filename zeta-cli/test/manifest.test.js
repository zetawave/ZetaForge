/**
 * The descriptor is the developer-facing surface: every mistake here should
 * produce a sentence, not a stack trace. These tests pin the sentences.
 */
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { loadProject } from "../src/project/manifest.js";
import { HOST_API_VERSION, UI_API_VERSION } from "../src/config.js";

/** Asserts on the whole error: a hint that goes missing is a regression. */
function throwsWith(fn, pattern) {
  try {
    fn();
  } catch (error) {
    const whole = `${error.message}
${error.hint || ""}
${error.docs || ""}`;
    assert.match(whole, pattern);
    return error;
  }
  assert.fail("expected an error, none was thrown");
}

const VALID = `
[plugin]
id         = "com.example.demo"
name       = "Demo"
version    = "1.0.0"
entryPoint = "com.example.demo.DemoPlugin"
`;

function project(toml) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "zeta-proj-"));
  fs.writeFileSync(path.join(dir, "zetaplugin.toml"), toml);
  return dir;
}

test("loads a minimal descriptor and fills in the defaults", () => {
  const loaded = loadProject(project(VALID));
  assert.equal(loaded.plugin.id, "com.example.demo");
  assert.equal(loaded.plugin.minSdk, 26);
  // Pinned to the constant, not to a literal: the CLI's major version *is*
  // the Host API version, so a bump must not need a test edit.
  assert.equal(loaded.plugin.minHostApi, HOST_API_VERSION);
  assert.deepEqual(loaded.permissions, []);
  assert.deepEqual(loaded.settings, []);
});

test("rejects an id that is not reverse-DNS", () => {
  const dir = project(VALID.replace("com.example.demo", "Demo"));
  assert.throws(() => loadProject(dir), /not a valid plugin id/);
});

test("rejects a version that is not major.minor.patch", () => {
  const dir = project(VALID.replace('version    = "1.0.0"', 'version    = "1.0"'));
  assert.throws(() => loadProject(dir), /not a valid version/);
});

test("insists on a reason for every permission", () => {
  const dir = project(`${VALID}
[[permission]]
name = "android.permission.INTERNET"
`);
  assert.throws(() => loadProject(dir), /has no "reason"/);
});

test("rejects an unknown special access, and lists the valid ones", () => {
  const dir = project(`${VALID}
[[specialAccess]]
id     = "readMinds"
reason = "why not"
`);
  throwsWith(() => loadProject(dir), /allFilesAccess/);
});

test("rejects two settings sharing a key", () => {
  const dir = project(`${VALID}
[[setting]]
key = "mode"
type = "switch"

[[setting]]
key = "mode"
type = "text"
`);
  assert.throws(() => loadProject(dir), /share the key/);
});

test("insists that a choice has options", () => {
  const dir = project(`${VALID}
[[setting]]
key = "codec"
type = "choice"
`);
  assert.throws(() => loadProject(dir), /has no options/);
});

test("rejects a dependency that is not a Maven coordinate", () => {
  const dir = project(`${VALID}
[dependencies]
retrofit = "retrofit"
`);
  assert.throws(() => loadProject(dir), /not a valid Maven coordinate/);
});

test("rejects minHostApi greater than maxHostApi", () => {
  const dir = project(`${VALID}
`.replace("[plugin]", "[plugin]\nminHostApi = 3\nmaxHostApi = 2"));
  assert.throws(() => loadProject(dir), /greater than maxHostApi/);
});

test("says which CLI to install when the plugin needs a newer Host", () => {
  const dir = project(VALID.replace("[plugin]", "[plugin]\nminHostApi = 99"));
  throwsWith(() => loadProject(dir), /zetaforge@99/);
});

test("explains itself when there is no descriptor at all", () => {
  const empty = fs.mkdtempSync(path.join(os.tmpdir(), "zeta-empty-"));
  throwsWith(() => loadProject(empty), /zeta new/);
});

test("points at the file when the TOML is malformed", () => {
  const dir = project("[plugin\nid = ");
  assert.throws(() => loadProject(dir), /not valid TOML/);
});

test("a descriptor without a [ui] block declares no screen", () => {
  const loaded = loadProject(project(VALID));
  assert.equal(loaded.ui, null);
  assert.deepEqual(loaded.capabilities, []);
});

const UI_ONLY = ["", "[ui]", "only = true", ""].join("\n");
const UI_FUTURE = ["", "[ui]", "uiApi = 99", ""].join("\n");

test("a [ui] block becomes a screen declaration and the ui capability", () => {
  const loaded = loadProject(project(VALID + UI_ONLY));
  assert.equal(loaded.ui.enabled, true);
  assert.equal(loaded.ui.only, true);
  assert.equal(loaded.ui.uiApi, UI_API_VERSION);
  // Declared once in the [ui] block, mirrored into capabilities by the loader,
  // so the two can never disagree.
  assert.deepEqual(loaded.capabilities, ["ui"]);
});

test("refuses a screen contract this CLI cannot build", () => {
  throwsWith(() => loadProject(project(VALID + UI_FUTURE)), /screen contract 99/);
});
