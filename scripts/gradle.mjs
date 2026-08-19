#!/usr/bin/env node
/**
 * Runs a Gradle task in app/ from the repository root, so every npm script can
 * call Gradle without caring where the Android project lives or which wrapper
 * script this platform needs.
 */
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const app = path.join(root, "app");
const wrapper = path.join(app, process.platform === "win32" ? "gradlew.bat" : "gradlew");

const args = process.argv.slice(2);
if (args.length === 0) {
  console.error("usage: node scripts/gradle.mjs <task> [task...]");
  process.exit(2);
}

const result = spawnSync(wrapper, [...args, "--console=plain"], {
  cwd: app,
  stdio: "inherit",
  shell: process.platform === "win32",
});

process.exit(result.status ?? 1);
