#!/usr/bin/env node
/**
 * Copies the artifacts the CLI ships with out of the Gradle build.
 *
 * Only small things live inside the npm package: the contract jar (about 50 KB)
 * and the Gradle wrapper. The Host APK is 18 MB and goes to GitHub Releases
 * instead, where `zeta host install` fetches it on demand.
 *
 * Run automatically by the release script; run it by hand after changing the
 * contract.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const app = path.join(root, "app");
const cli = path.join(root, "zeta-cli");

const hostApi = readProperty(path.join(app, "zetaforge.properties"), "zetaforge.hostApiVersion");

const copies = [
  {
    what: "contract",
    from: path.join(app, "build", "zetaforge", "sdk", `zetaforge-api-${hostApi}.jar`),
    to: path.join(cli, "assets", `zetaforge-api-${hostApi}.jar`),
    build: "npm run build:contract",
  },
  {
    what: "gradlew",
    from: path.join(app, "gradlew"),
    to: path.join(cli, "assets", "gradle", "gradlew"),
  },
  {
    what: "gradlew.bat",
    from: path.join(app, "gradlew.bat"),
    to: path.join(cli, "assets", "gradle", "gradlew.bat"),
  },
  {
    what: "wrapper jar",
    from: path.join(app, "gradle", "wrapper", "gradle-wrapper.jar"),
    to: path.join(cli, "assets", "gradle", "wrapper", "gradle-wrapper.jar"),
  },
  {
    what: "wrapper properties",
    from: path.join(app, "gradle", "wrapper", "gradle-wrapper.properties"),
    to: path.join(cli, "assets", "gradle", "wrapper", "gradle-wrapper.properties"),
  },
];

let failed = false;
for (const copy of copies) {
  if (!fs.existsSync(copy.from)) {
    console.error(`  missing  ${copy.what}: ${path.relative(root, copy.from)}`);
    if (copy.build) console.error(`           build it first:  ${copy.build}`);
    failed = true;
    continue;
  }
  fs.mkdirSync(path.dirname(copy.to), { recursive: true });
  fs.copyFileSync(copy.from, copy.to);
  const size = fs.statSync(copy.to).size;
  console.log(`  copied   ${copy.what.padEnd(20)} ${(size / 1024).toFixed(1)} KB`);
}

// Stale contracts from an older major would ship alongside the current one.
const assets = path.join(cli, "assets");
for (const file of fs.readdirSync(assets)) {
  const match = file.match(/^zetaforge-api-(\d+)\.jar$/);
  if (match && match[1] !== String(hostApi)) {
    fs.rmSync(path.join(assets, file));
    console.log(`  removed  stale ${file}`);
  }
}

process.exit(failed ? 1 : 0);

function readProperty(file, key) {
  const line = fs
    .readFileSync(file, "utf8")
    .split("\n")
    .find((l) => l.trim().startsWith(`${key}=`));
  if (!line) throw new Error(`${key} not found in ${file}`);
  return line.split("=")[1].trim();
}
