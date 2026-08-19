#!/usr/bin/env node
/**
 * The last gate before `npm publish`.
 *
 * A CLI published without its contract jar installs cleanly and then fails on
 * the user's first build, which is the worst way to find out. npm runs this
 * automatically via `prepublishOnly`.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const pkg = JSON.parse(fs.readFileSync(path.join(root, "package.json"), "utf8"));
const hostApi = pkg.version.split(".")[0];

const required = [
  ["contract", `assets/zetaforge-api-${hostApi}.jar`],
  ["gradlew", "assets/gradle/gradlew"],
  ["gradlew.bat", "assets/gradle/gradlew.bat"],
  ["wrapper jar", "assets/gradle/wrapper/gradle-wrapper.jar"],
  ["wrapper properties", "assets/gradle/wrapper/gradle-wrapper.properties"],
  ["basic template", "templates/basic/zetaplugin.toml.tmpl"],
  ["network template", "templates/network/zetaplugin.toml.tmpl"],
  ["readme", "README.md"],
  ["licence", "LICENSE"],
];

let failed = false;
for (const [what, relative] of required) {
  const file = path.join(root, relative);
  if (fs.existsSync(file) && fs.statSync(file).size > 0) {
    console.log(`  ok       ${what}`);
  } else {
    console.error(`  MISSING  ${what}: ${relative}`);
    failed = true;
  }
}

// A stale contract from a previous major would silently be preferred by nothing
// and confuse everything.
for (const file of fs.readdirSync(path.join(root, "assets"))) {
  const match = file.match(/^zetaforge-api-(\d+)\.jar$/);
  if (match && match[1] !== hostApi) {
    console.error(`  STALE    assets/${file} does not match version ${pkg.version}`);
    failed = true;
  }
}

if (failed) {
  console.error("\n  Run:  npm run build:contract && npm run sync:assets   (from the repository root)\n");
  process.exit(1);
}
console.log("\n  assets complete\n");
