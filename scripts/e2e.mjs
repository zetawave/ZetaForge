#!/usr/bin/env node
/**
 * End-to-end proof, in a throwaway folder: scaffold a plugin from nothing,
 * build it, put it on a device and run it.
 *
 * This is the test that would have caught every integration bug found while the
 * CLI was written — a ZIP Android would not open, a log format that did not
 * parse, a race where the plugin finished before anyone was listening. None of
 * them are visible from a unit test.
 *
 *   npm run test:e2e                 needs a connected device or emulator
 *   npm run test:e2e -- --no-device  build only
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const zeta = path.join(root, "zeta-cli", "bin", "zeta.js");
const withDevice = !process.argv.includes("--no-device");

const workspace = fs.mkdtempSync(path.join(os.tmpdir(), "zeta-e2e-"));
let failures = 0;

console.log(`\n  end-to-end  ${workspace}\n`);

step("scaffold", () => zetaRun(["new", "e2e-demo", "--author", "e2e"], workspace));

const project = path.join(workspace, "e2e-demo");
step("build", () => zetaRun(["build"], project));

step("the package exists and is a ZIP", () => {
  const file = path.join(project, "dist", "e2e-demo-1.0.0.zeta");
  if (!fs.existsSync(file)) throw new Error("no package produced");
  const header = fs.readFileSync(file).subarray(0, 2).toString("latin1");
  if (header !== "PK") throw new Error(`not a ZIP: header is ${JSON.stringify(header)}`);
});

step("inspect", () => zetaRun(["inspect", "dist/e2e-demo-1.0.0.zeta"], project));
step("unit tests", () => zetaRun(["test"], project));

step("a bad entry point is caught before packaging", () => {
  const descriptor = path.join(project, "zetaplugin.toml");
  const original = fs.readFileSync(descriptor, "utf8");
  fs.writeFileSync(descriptor, original.replace("E2eDemoPlugin", "NotAClass"));
  const result = zetaRun(["build"], project, { allowFailure: true });
  fs.writeFileSync(descriptor, original);
  if (result.status === 0) throw new Error("the build accepted an entry point that does not exist");
});

if (withDevice) {
  step("install on a device", () => zetaRun(["install", "--no-build"], project));
  step("run on a device", () => zetaRun(["run"], project));
} else {
  console.log("  skipped  device steps (--no-device)");
}

fs.rmSync(workspace, { recursive: true, force: true });

console.log(failures === 0 ? "\n  ✓ end-to-end passed\n" : `\n  ✗ ${failures} step(s) failed\n`);
process.exit(failures === 0 ? 0 : 1);

function step(name, body) {
  process.stdout.write(`  ${name.padEnd(46)}`);
  try {
    body();
    console.log("ok");
  } catch (error) {
    console.log("FAILED");
    console.log(`      ${error.message}`);
    failures++;
  }
}

function zetaRun(args, cwd, options = {}) {
  const result = spawnSync(process.execPath, [zeta, ...args], {
    cwd,
    encoding: "utf8",
    env: { ...process.env, FORCE_COLOR: "0" },
  });
  if (result.status !== 0 && !options.allowFailure) {
    throw new Error(`zeta ${args[0]} exited ${result.status}\n${result.stdout}${result.stderr}`);
  }
  return result;
}
