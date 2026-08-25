/**
 * The parts both release trains share.
 *
 * ZetaForge ships on two trains that move at their own pace - the Host APK and
 * the `zeta` CLI - joined by one rule: their major version is the Host API
 * version, and a plugin built by CLI N.x runs on Host N.x and nowhere else.
 * Minor and patch mean nothing to the contract, which is why they are allowed
 * to drift apart.
 *
 * Everything here is about *how* to release safely: spawning tools on Windows,
 * reading versions out of the files that carry them, and refusing to start a
 * release that cannot finish. What each train actually ships lives in
 * release-host.mjs and release-cli.mjs.
 */
import fs from "node:fs";
import path from "node:path";
import readline from "node:readline/promises";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

export const root = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
export const app = path.join(root, "app");
export const cli = path.join(root, "zeta-cli");
export const RELEASE_BRANCH = "main";

// --- versions --------------------------------------------------------------

/** The Host API version: the one number the two trains must agree on. */
export function hostApiVersion() {
  return Number(readProperty(path.join(app, "zetaforge.properties"), "zetaforge.hostApiVersion"));
}

export function hostVersion() {
  return readProperty(path.join(app, "zetaforge.properties"), "zetaforge.versionName");
}

export function cliVersion() {
  return readJson(path.join(cli, "package.json")).version;
}

export function bump(current, kind) {
  const [major, minor, patch] = current.split(".").map(Number);
  if (kind === "major") return `${major + 1}.0.0`;
  if (kind === "minor") return `${major}.${minor + 1}.0`;
  if (kind === "patch") return `${major}.${minor}.${patch + 1}`;
  return fail(`Unknown bump "${kind}".`, "One of: major, minor, patch");
}

/**
 * The rule that keeps the two trains compatible.
 *
 * A major bump is a new contract, so it may only happen once
 * zetaforge.hostApiVersion has been raised to match - by hand, deliberately,
 * because raising it also means the contract in app/plugin-api has changed.
 */
export function requireMajorMatchesHostApi(version, what) {
  const major = Number(version.split(".")[0]);
  const api = hostApiVersion();
  if (major !== api) {
    fail(
      `The ${what} major (${major}) must equal the Host API version (${api}).`,
      "Either pick a different version, or raise zetaforge.hostApiVersion in\n" +
        "    app/zetaforge.properties - which is only correct if the contract really changed.",
    );
  }
  return api;
}

/** Rewrites one `key=value` line in a .properties file, and can put it back. */
export function writeProperty(file, key, value) {
  const before = fs.readFileSync(file, "utf8");
  const pattern = new RegExp(`^${key.replace(/\./g, "\\.")}=.*$`, "m");
  const updated = before.replace(pattern, `${key}=${value}`);
  if (updated === before) fail(`${key} not found in ${file}`);
  fs.writeFileSync(file, updated);
  return () => fs.writeFileSync(file, before);
}

/** Rewrites `version` in a package.json, and can put it back. */
export function writePackageVersion(file, version) {
  const before = fs.readFileSync(file, "utf8");
  const json = JSON.parse(before);
  json.version = version;
  fs.writeFileSync(file, `${JSON.stringify(json, null, 2)}\n`);
  return () => fs.writeFileSync(file, before);
}

/**
 * Opens a dated entry in a changelog, if one is not there already.
 *
 * Deliberately left blank: a release note written by a script is a release note
 * nobody reads.
 */
export function openChangelogEntry(file, version) {
  if (!fs.existsSync(file)) return () => {};
  const before = fs.readFileSync(file, "utf8");
  if (before.includes(`## ${version}`)) return () => {};
  const today = new Date().toISOString().slice(0, 10);
  const firstRelease = before.indexOf("\n## ");
  const entry = `\n## ${version} - ${today}\n\n- _describe the change_\n`;
  fs.writeFileSync(
    file,
    firstRelease < 0 ? before + entry : before.slice(0, firstRelease) + entry + before.slice(firstRelease),
  );
  info(`${path.relative(root, file)} - write the entry before pushing`);
  return () => fs.writeFileSync(file, before);
}

// --- git -------------------------------------------------------------------

export function preflight({ dryRun, force }) {
  if (dryRun) return;
  if (git(["status", "--porcelain"]).trim()) {
    fail("The working tree is not clean.", "Commit or stash your changes first.");
  }
  const branch = git(["rev-parse", "--abbrev-ref", "HEAD"]).trim();
  if (branch !== RELEASE_BRANCH && !force) {
    fail(`On branch "${branch}", not "${RELEASE_BRANCH}".`, "Use --force to release anyway.");
  }
}

export function tagExists(tag) {
  return Boolean(git(["tag", "--list", tag]).trim());
}

export function tagIsOnHead(tag) {
  if (!tagExists(tag)) return false;
  return git(["rev-list", "-n1", tag]).trim() === git(["rev-parse", "HEAD"]).trim();
}

/**
 * Pushes a tag, tolerating a remote that already has it on the same commit.
 *
 * Git calls that an error even when both point at the same place. For a release
 * being retried it is not a conflict - it is the previous attempt having got
 * this far.
 */
export function pushTag(tag) {
  const remote = git(["ls-remote", "origin", `refs/tags/${tag}`]).trim();
  if (remote) {
    const local = git(["rev-list", "-n1", tag]).trim();
    if (remote.includes(local)) {
      info(`the remote already has ${tag} on this commit`);
      return;
    }
    fail(
      `The remote has a different ${tag}.`,
      `    git push --delete origin ${tag}    # only if nothing was published`,
    );
  }
  git(["push", "origin", tag]);
}

export function commitAndTag(tag, message) {
  git(["add", "-A"]);
  // Re-releasing the current version leaves nothing to commit, and git treats
  // that as an error. The tag marks the release, so an empty commit would be
  // noise rather than a record.
  if (git(["status", "--porcelain"]).trim()) {
    git(["commit", "-m", message]);
  } else {
    info("nothing to commit; tagging the current HEAD");
  }
  git(["tag", "-a", tag, "-m", message]);
  git(["push", "origin", RELEASE_BRANCH]);
  pushTag(tag);
}

export async function confirm(question) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const answer = await rl.question(`\n  ${question} [y/N] `);
  rl.close();
  if (answer.trim().toLowerCase() !== "y") fail("Cancelled.");
}

// --- process ---------------------------------------------------------------

export function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (!token.startsWith("--")) continue;
    const key = token.slice(2);
    if (["bump", "version", "otp", "notes"].includes(key)) out[key] = argv[++i];
    else out[key] = true;
  }
  return out;
}

/**
 * Spawns a tool, on every platform, without either of the two Windows traps.
 *
 *  * `npm` is a `.cmd` shim, and since Node 18.20 spawning one without a shell
 *    throws `EINVAL` (CVE-2024-27980). It *needs* a shell.
 *  * With a shell, cmd.exe re-parses every argument, so a commit message with
 *    a space in it arrives as several arguments. It *must not* have a shell.
 *
 * Both are satisfied by using a shell only for the shims that require one, and
 * quoting the arguments when we do.
 */
export function spawnTool(command, commandArgs, options = {}) {
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

export function run(command, commandArgs, options = {}) {
  const result = spawnTool(command, commandArgs, options);
  if (result.error) fail(`${command} could not be started.`, result.error.message);
  if (result.status !== 0 && !options.allowFailure) {
    fail(`${command} ${commandArgs[0]} failed.`, (result.stderr || "").trim());
  }
  return result.stdout || "";
}

export function git(gitArgs, options = {}) {
  return run("git", gitArgs, { capture: true, ...options });
}

export function gradle(tasks) {
  run("node", [path.join(root, "scripts", "gradle.mjs"), ...tasks]);
}

export function requireCommand(command, probe, message) {
  if (spawnTool(command, probe, { capture: true }).status !== 0) {
    fail(`${command} is not available.`, message);
  }
}

export function readJson(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

export function readProperty(file, key) {
  const line = fs.readFileSync(file, "utf8").split("\n").find((l) => l.trim().startsWith(`${key}=`));
  if (!line) fail(`${key} not found in ${file}`);
  return line.split("=")[1].trim();
}

// --- output ----------------------------------------------------------------

export function banner(what, dryRun) {
  console.log(`\n  ZetaForge ${what} release${dryRun ? "  (dry run)" : ""}\n`);
}
export function step(message) { console.log(`\n  > ${message}`); }
export function info(message) { console.log(`    ${message}`); }
export function warn(message) { console.log(`    ! ${message}`); }
export function ok(message) { console.log(`\n  + ${message}\n`); }
export function fail(message, hint) {
  console.error(`\n  x ${message}`);
  if (hint) console.error(`    ${hint}`);
  process.exit(1);
}
