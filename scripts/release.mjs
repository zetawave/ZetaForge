#!/usr/bin/env node
/**
 * One command that ships everything.
 *
 *   npm run release:patch      3.0.0 -> 3.0.1
 *   npm run release:minor      3.0.0 -> 3.1.0
 *   npm run release:major      3.0.0 -> 4.0.0   (a new Host API — see below)
 *   npm run release:dry        do everything except publish
 *
 * It refuses to start on a dirty tree, on the wrong branch, or without the
 * credentials it will need at the end — because the worst possible release is
 * one that fails halfway, after the tag has been pushed.
 *
 * What it does, in order:
 *   1. checks the tree, the branch, npm and gh
 *   2. bumps the version in every place that carries one
 *   3. builds the contract jar and the Host APK
 *   4. copies the small artifacts into the CLI package
 *   5. runs the tests
 *   6. packs the npm tarball and inspects it
 *   7. commits, tags, pushes
 *   8. publishes to npm and creates the GitHub release with the APK attached
 */
import fs from "node:fs";
import path from "node:path";
import readline from "node:readline/promises";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const app = path.join(root, "app");
const cli = path.join(root, "zeta-cli");
const RELEASE_BRANCH = "main";

const args = parseArgs(process.argv.slice(2));
const dryRun = args["dry-run"] === true;

main().catch((error) => {
  console.error(`\n  ✗ ${error.message}\n`);
  process.exit(1);
});

async function main() {
  banner();

  // ---- 1. preflight ------------------------------------------------------
  step("checking the working tree");
  const currentVersion = readJson(path.join(cli, "package.json")).version;

  if (!dryRun) {
    if (git(["status", "--porcelain"]).trim()) {
      fail("The working tree is not clean.", "Commit or stash your changes first.");
    }
    const branch = git(["rev-parse", "--abbrev-ref", "HEAD"]).trim();
    if (branch !== RELEASE_BRANCH && !args.force) {
      fail(`On branch "${branch}", not "${RELEASE_BRANCH}".`, "Use --force to release anyway.");
    }
    requireCommand("npm", ["--version"], "npm is required to publish.");
    requireCommand("gh", ["--version"],
      "The GitHub CLI is required to attach the Host APK.\n    https://cli.github.com — then: gh auth login");
    if (spawnSync("npm", ["whoami"], { encoding: "utf8", shell: true }).status !== 0) {
      fail("Not logged in to npm.", "Run: npm login");
    }
  }

  // ---- 2. version --------------------------------------------------------
  const version = args.bump ? bump(currentVersion, args.bump) : (args.version || currentVersion);
  if (!/^\d+\.\d+\.\d+(-[\w.]+)?$/.test(version)) {
    fail(`"${version}" is not a valid version.`);
  }

  const majorChanged = version.split(".")[0] !== currentVersion.split(".")[0];
  info(`${currentVersion}  ->  ${version}`);
  if (majorChanged) {
    warn("A major bump means a new Host API version.");
    warn("The contract in app/plugin-api must have changed, and");
    warn("zetaforge.properties must already declare the new hostApiVersion.");
  }

  const hostApi = readProperty(path.join(app, "zetaforge.properties"), "zetaforge.hostApiVersion");
  if (hostApi !== version.split(".")[0]) {
    fail(
      `The major version (${version.split(".")[0]}) must equal the Host API version (${hostApi}).`,
      "Either pick a different version, or update zetaforge.hostApiVersion in app/zetaforge.properties.",
    );
  }

  if (!dryRun && !args.yes) {
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    const answer = await rl.question(`\n  Release ${version}? [y/N] `);
    rl.close();
    if (answer.trim().toLowerCase() !== "y") fail("Cancelled.");
  }

  const restore = writeVersion(version);

  // ---- 3. build ----------------------------------------------------------
  step("building the contract");
  gradle([":plugin-api:apiJar"]);

  step("building the Host");
  gradle([":host:assembleDebug"]);
  const apk = path.join(app, "host", "build", "outputs", "apk", "debug", "host-debug.apk");
  if (!fs.existsSync(apk)) fail(`The Host APK was not produced: ${apk}`);

  step("copying artifacts into the CLI");
  run("node", [path.join(root, "scripts", "sync-assets.mjs")]);

  // ---- 4. test -----------------------------------------------------------
  if (!args["skip-tests"]) {
    step("running the runtime tests");
    gradle([":runtime:test"]);

    step("running the CLI tests");
    // Through npm, so the test glob is the one the package itself declares -
    // and not allowed to fail: shipping a CLI with red tests is not a release.
    run("npm", ["test"], { cwd: cli });
  }

  // ---- 5. pack -----------------------------------------------------------
  step("packing the npm tarball");
  const packOutput = run("npm", ["pack", "--dry-run", "--json"], { cwd: cli, capture: true });
  const packed = JSON.parse(packOutput)[0];
  info(`${packed.filename}  ${(packed.unpackedSize / 1024 / 1024).toFixed(1)} MB unpacked, ${packed.entryCount} files`);

  const contract = packed.files.find((f) => f.path.includes("zetaforge-api-"));
  if (!contract) fail("The contract jar is not in the npm package.", "Run: npm run sync:assets");
  if (packed.unpackedSize > 20 * 1024 * 1024) {
    fail("The npm package is over 20 MB.", "Something large slipped into zeta-cli/. Check the `files` field.");
  }

  const apkName = `zetaforge-host-${version}.apk`;
  const apkSize = fs.statSync(apk).size;
  info(`${apkName}  ${(apkSize / 1024 / 1024).toFixed(1)} MB  (GitHub release, not npm)`);

  if (dryRun) {
    // Only the files this script wrote are put back. Never `git checkout -- .`:
    // a build tool must not be able to destroy work it did not create.
    restore();
    console.log("\n  ✓ dry run: nothing was committed, tagged or published\n");
    return;
  }

  // ---- 6. commit, tag, push ---------------------------------------------
  step("committing and tagging");
  git(["add", "-A"]);
  git(["commit", "-m", `release: v${version}`]);
  git(["tag", "-a", `v${version}`, "-m", `ZetaForge ${version} (Host API ${hostApi})`]);
  git(["push", "origin", RELEASE_BRANCH]);
  git(["push", "origin", `v${version}`]);

  // ---- 7. publish --------------------------------------------------------
  step("publishing to npm");
  run("npm", ["publish", "--access", "public"], { cwd: cli });

  step("creating the GitHub release");
  const staged = path.join(app, "build", apkName);
  fs.copyFileSync(apk, staged);
  run("gh", [
    "release", "create", `v${version}`,
    staged,
    path.join(cli, "assets", `zetaforge-api-${hostApi}.jar`),
    "--title", `ZetaForge ${version}`,
    "--notes", releaseNotes(version, hostApi),
  ]);

  console.log(`
  ✓ released ${version}

    npm      npm install -g zetaforge@${version}
    github   https://github.com/zetaforge/zetaforge/releases/tag/v${version}
    try it   npx zetaforge@${version} doctor
`);
}

// --- version bookkeeping ---------------------------------------------------

/**
 * Every file that carries the version. Kept in one place on purpose: a release
 * where these disagree is confusing for months afterwards.
 */
function writeVersion(version) {
  step("writing the version");
  const before = new Map();
  const remember = (file) => before.set(file, fs.readFileSync(file, "utf8"));

  for (const file of [path.join(root, "package.json"), path.join(cli, "package.json")]) {
    remember(file);
    const json = readJson(file);
    json.version = version;
    fs.writeFileSync(file, `${JSON.stringify(json, null, 2)}\n`);
    info(path.relative(root, file));
  }

  const gradleProps = path.join(app, "zetaforge.properties");
  remember(gradleProps);
  const updated = fs
    .readFileSync(gradleProps, "utf8")
    .replace(/^zetaforge\.versionName=.*$/m, `zetaforge.versionName=${version}`);
  fs.writeFileSync(gradleProps, updated);
  info(path.relative(root, gradleProps));

  const changelog = path.join(cli, "CHANGELOG.md");
  if (fs.existsSync(changelog)) {
    const body = fs.readFileSync(changelog, "utf8");
    if (!body.includes(`## ${version}`)) {
      remember(changelog);
      const today = new Date().toISOString().slice(0, 10);
      // Above the previous release, below the file's own introduction.
      const firstRelease = body.indexOf("\n## ");
      const entry = `\n## ${version} — ${today}\n\n- _describe the change_\n`;
      fs.writeFileSync(
        changelog,
        firstRelease < 0 ? body + entry : body.slice(0, firstRelease) + entry + body.slice(firstRelease),
      );
      info("CHANGELOG.md — write the entry before pushing");
    }
  }

  /** Puts back exactly the files this function wrote, and nothing else. */
  return function restore() {
    for (const [file, content] of before) fs.writeFileSync(file, content);
  };
}

function releaseNotes(version, hostApi) {
  const changelog = path.join(cli, "CHANGELOG.md");
  const section = fs.existsSync(changelog)
    ? (fs.readFileSync(changelog, "utf8").split(/^## /m).find((s) => s.startsWith(version)) || "")
        .split("\n").slice(1).join("\n").trim()
    : "";

  return [
    section || "See the changelog.",
    "",
    "---",
    "",
    `**Host API ${hostApi}** — plugins built with \`zetaforge@${hostApi}\` run on this Host.`,
    "",
    "```bash",
    `npm install -g zetaforge@${version}`,
    "zeta doctor",
    "zeta new my-plugin",
    "```",
    "",
    `\`zetaforge-host-${version}.apk\` is the app itself; \`zeta host install\` downloads it for you.`,
  ].join("\n");
}

// --- helpers ---------------------------------------------------------------

function bump(current, kind) {
  const [major, minor, patch] = current.split(".").map(Number);
  if (kind === "major") return `${major + 1}.0.0`;
  if (kind === "minor") return `${major}.${minor + 1}.0`;
  if (kind === "patch") return `${major}.${minor}.${patch + 1}`;
  fail(`Unknown bump "${kind}".`, "One of: major, minor, patch");
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (!token.startsWith("--")) continue;
    const key = token.slice(2);
    if (["bump", "version"].includes(key)) out[key] = argv[++i];
    else out[key] = true;
  }
  return out;
}

function run(command, commandArgs, options = {}) {
  const result = spawnSync(command, commandArgs, {
    cwd: options.cwd || root,
    encoding: "utf8",
    stdio: options.capture ? "pipe" : "inherit",
    shell: process.platform === "win32",
  });
  if (result.status !== 0 && !options.allowFailure) {
    fail(`${command} ${commandArgs[0]} failed.`, result.stderr?.trim());
  }
  return result.stdout || "";
}

function git(gitArgs, options = {}) {
  return run("git", gitArgs, { capture: true, ...options });
}

function gradle(tasks) {
  run("node", [path.join(root, "scripts", "gradle.mjs"), ...tasks]);
}

function requireCommand(command, probe, message) {
  const result = spawnSync(command, probe, { encoding: "utf8", shell: process.platform === "win32" });
  if (result.status !== 0) fail(`${command} is not available.`, message);
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function readProperty(file, key) {
  const line = fs.readFileSync(file, "utf8").split("\n").find((l) => l.trim().startsWith(`${key}=`));
  if (!line) fail(`${key} not found in ${file}`);
  return line.split("=")[1].trim();
}

function banner() {
  console.log(`\n  ZetaForge release${dryRun ? "  (dry run)" : ""}\n`);
}
function step(message) { console.log(`\n  › ${message}`); }
function info(message) { console.log(`    ${message}`); }
function warn(message) { console.log(`    ! ${message}`); }
function fail(message, hint) {
  console.error(`\n  ✗ ${message}`);
  if (hint) console.error(`    ${hint}`);
  process.exit(1);
}
