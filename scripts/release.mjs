#!/usr/bin/env node
/**
 * One command that ships everything.
 *
 *   npm run release:patch      3.0.0 -> 3.0.1
 *   npm run release:minor      3.0.0 -> 3.1.0
 *   npm run release:major      3.0.0 -> 4.0.0   (a new Host API — see below)
 *   npm run release:dry        do everything except publish
 *
 * Flags: --skip-tests, --yes, --force, --version <v>,
 * --otp <code> (when the npm token does not bypass 2FA)
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

    // Checked here rather than at the end, because the worst release is one
    // that fails after the tag has been pushed. A token in ~/.npmrc that has
    // expired fails exactly like never having logged in, and the message has
    // to tell the two apart.
    const whoami = spawnTool("npm", ["whoami"], { capture: true });
    if (whoami.status !== 0) {
      fail(
        "npm rejected your credentials.",
        "If ~/.npmrc has an _authToken, it has expired or been revoked:\n" +
          "    create a new automation token at https://www.npmjs.com/settings/~/tokens\n" +
          "    and put it back as //registry.npmjs.org/:_authToken=...\n" +
          "    Otherwise just run: npm login",
      );
    }
    info(`npm user: ${whoami.stdout.trim()}`);

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

  const tagged = git(["tag", "--list", `v${version}`]).trim();

  if (tagged) {
    const tagPoints = git(["rev-list", "-n1", `v${version}`]).trim();
    const head = git(["rev-parse", "HEAD"]).trim();
    const onRegistry = publishedVersions().includes(version);

    if (onRegistry) {
      fail(
        `${version} is already on npm.`,
        "A version is published once, and npm does not allow reuse. Bump it.",
      );
    }
    if (tagPoints !== head) {
      fail(
        `The tag v${version} exists and points somewhere else.`,
        `    git tag -d v${version} && git push --delete origin v${version}`,
      );
    }
    // Tagged, never published: this is a retry of a release that got as far as
    // pushing the tag. Everything before the publish is already done.
    warn("resuming: the tag is already on this commit and npm has no such version");
  } else {
    git(["add", "-A"]);

    // Re-releasing the current version leaves nothing to commit, and
    // `git commit` treats that as an error. The tag marks the release, so an
    // empty commit would be noise rather than a record.
    if (git(["status", "--porcelain"]).trim()) {
      git(["commit", "-m", `release: v${version}`]);
    } else {
      info("nothing to commit; tagging the current HEAD");
    }

    git(["tag", "-a", `v${version}`, "-m", `ZetaForge ${version} (Host API ${hostApi})`]);
    git(["push", "origin", RELEASE_BRANCH]);
    git(["push", "origin", `v${version}`]);
  }

  // ---- 7. publish --------------------------------------------------------
  step("publishing to npm");
  publishToNpm();

  // The GitHub release is created by the Release workflow, which the tag push
  // above just triggered. It builds the Host APK on a clean machine and attaches
  // it, which is work this script has no reason to duplicate — and no reason to
  // require `gh` for.
  info("the tag triggers the Release workflow, which attaches the Host APK");

  console.log(`
  ✓ released ${version}

    npm      npm install -g zetaforge-cli@${version}
    docs     https://zetawave.github.io/ZetaForge/
    github   https://github.com/zetawave/ZetaForge/releases/tag/v${version}
    try it   npx zetaforge-cli@${version} doctor
`);
}

/**
 * Publishes, and turns npm's two most likely refusals into an instruction.
 *
 * Both are things a first release runs into and neither is obvious from npm's
 * own output: the name can belong to somebody else, and a token can be
 * read-only.
 */
/**
 * The versions npm already has, or an empty list when the package is new.
 *
 * A 404 here is the ordinary answer for a package that has never been
 * published, so it is not treated as a failure.
 */
function publishedVersions() {
  const name = readJson(path.join(cli, "package.json")).name;
  const result = spawnTool("npm", ["view", name, "versions", "--json"], { capture: true });
  if (result.status !== 0) return [];
  try {
    const parsed = JSON.parse(result.stdout || "[]");
    return Array.isArray(parsed) ? parsed : [parsed];
  } catch {
    return [];
  }
}

function publishToNpm() {
  // --otp is passed through when given, for a token that keeps 2FA on.
  const publishArgs = ["publish", "--access", "public"];
  if (args.otp) publishArgs.push("--otp", String(args.otp));

  const result = spawnTool("npm", publishArgs, { cwd: cli, stdio: "pipe" });
  process.stdout.write(result.stdout || "");
  if (result.status === 0) return;

  const output = `${result.stdout || ""}${result.stderr || ""}`;
  process.stderr.write(output);
  const name = readJson(path.join(cli, "package.json")).name;

  // A granular token that does not bypass 2FA needs a one-time password, and
  // stdio is piped here so npm cannot prompt for one. Saying so beats leaving
  // the developer with npm's "this operation requires a one-time password".
  if (/one-time pass|EOTP|otp/i.test(output)) {
    fail(
      "npm wants a one-time password.",
      "This token does not bypass 2FA. Either:\n" +
        "    re-run with the code:  npm run release -- --otp 123456\n" +
        "    or issue a token with \"Bypass two-factor authentication\" enabled.",
    );
  }

  if (/403|forbidden|not allowed to publish/i.test(output)) {
    fail(
      `npm refused to publish "${name}".`,
      "Either the name belongs to someone else, or your token is read-only.\n" +
        `    Check:  npm view ${name}\n` +
        "    A publish needs an *automation* or *publish* token, not a read-only one.",
    );
  }
  if (/402|payment required/i.test(output)) {
    fail("npm asked for payment: a scoped package defaults to private.",
      "publishConfig.access is already \"public\" — check it was not removed.");
  }
  fail("npm publish failed.", "The output above is npm's.");
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
    if (["bump", "version", "otp"].includes(key)) out[key] = argv[++i];
    else out[key] = true;
  }
  return out;
}

/**
 * Spawns a tool, on every platform, without either of the two Windows traps.
 *
 * Windows presents two constraints that pull in opposite directions:
 *
 *  * `npm` is a `.cmd` shim, and since Node 18.20 spawning one without a shell
 *    throws `EINVAL` (CVE-2024-27980). It *needs* a shell.
 *  * With a shell, cmd.exe re-parses every argument, so
 *    `git commit -m "release: v4.0.0"` arrives as `-m release:` plus a stray
 *    pathspec `v4.0.0`, and a tag message containing parentheses is mangled
 *    differently again. It *must not* have a shell.
 *
 * Both are satisfied by using a shell only for the shims that require one, and
 * quoting the arguments when we do. Everything else — git, node, gh — is a real
 * executable and gets its arguments through untouched, exactly as on Unix.
 */
function spawnTool(command, commandArgs, options = {}) {
  const isShim = process.platform === "win32" && /^(npm|npx|yarn|pnpm)$/.test(command);
  const args = isShim ? commandArgs.map(quoteForShell) : commandArgs;

  return spawnSync(command, args, {
    cwd: options.cwd || root,
    encoding: "utf8",
    stdio: options.stdio ?? (options.capture ? "pipe" : "inherit"),
    shell: isShim,
  });
}

/** Quotes one argument for cmd.exe, for the few calls that go through it. */
function quoteForShell(argument) {
  const value = String(argument);
  return /[\s"^&|<>()]/.test(value) ? `"${value.replace(/"/g, '\\"')}"` : value;
}

function run(command, commandArgs, options = {}) {
  const result = spawnTool(command, commandArgs, options);
  if (result.error) {
    fail(`${command} could not be started.`, result.error.message);
  }
  if (result.status !== 0 && !options.allowFailure) {
    fail(`${command} ${commandArgs[0]} failed.`, (result.stderr || "").trim());
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
  if (!hasCommand(command, probe)) fail(`${command} is not available.`, message);
}

function hasCommand(command, probe) {
  return spawnTool(command, probe, { capture: true }).status === 0;
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
