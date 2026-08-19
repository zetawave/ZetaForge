#!/usr/bin/env node
/**
 * A syntax check over every CLI source file. Not a style linter: it answers the
 * one question that matters before publishing — does all of this parse?
 */
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const targets = [
  path.join(root, "zeta-cli", "src"),
  path.join(root, "zeta-cli", "bin"),
  path.join(root, "zeta-cli", "scripts"),
  path.join(root, "scripts"),
];

let failed = 0;
let checked = 0;

for (const dir of targets) {
  if (!fs.existsSync(dir)) continue;
  for (const file of walk(dir)) {
    if (!/\.(js|mjs)$/.test(file)) continue;
    checked++;
    const result = spawnSync(process.execPath, ["--check", file], { encoding: "utf8" });
    if (result.status !== 0) {
      console.error(`  ✗ ${path.relative(root, file)}`);
      console.error(result.stderr.split("\n").slice(0, 4).map((l) => `      ${l}`).join("\n"));
      failed++;
    }
  }
}

console.log(failed === 0 ? `  ${checked} files parse` : `  ${failed} of ${checked} files failed`);
process.exit(failed === 0 ? 0 : 1);

function walk(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(full));
    else out.push(full);
  }
  return out;
}
