#!/usr/bin/env node
/**
 * Releases the `zeta` CLI to npm, and nothing else.
 *
 *   npm run release:cli:patch     4.2.0 -> 4.2.1
 *   npm run release:cli:minor     4.2.0 -> 4.3.0
 *   npm run release:cli:major     4.2.0 -> 5.0.0   (a new Host API)
 *   npm run release:cli:dry       everything except tagging and publishing
 *
 * Flags: --skip-tests, --yes, --force, --version <v>, --bump <kind>,
 *        --otp <code>  (type the code yourself)
 *        --no-web      (turn the browser flow off)
 *
 * Two-factor authentication goes through the browser by default: npm prints a
 * link, you approve there, and the publish continues. Typing a six-digit code
 * into a terminal is the other option and not the good one - the code expires
 * in thirty seconds, and by the time a release script has built, tested and
 * packed, half of that is gone.
 *
 * The APK is not built here and no GitHub release is created: the tag
 * `cli-v<version>` triggers the Release (CLI) workflow, which attaches the
 * contract jar. What this script owns is npm, because it publishes from the
 * credentials of whoever is releasing.
 *
 * The CLI carries the contract jar for the Host API it targets, so a release
 * always rebuilds it - shipping a stale contract is the one mistake here that
 * would not show up until somebody's plugin failed to load.
 */
import fs from "node:fs";
import path from "node:path";
import {
  app, banner, bump, cli, cliVersion, commitAndTag, confirm, fail, gradle, hostApiVersion, info, ok,
  parseArgs, preflight, readJson, requireCommand, requireMajorMatchesHostApi, root, run, spawnTool,
  step, tagExists, tagIsOnHead, warn, writePackageVersion,
} from "./release-lib.mjs";

const args = parseArgs(process.argv.slice(2));
const dryRun = args["dry-run"] === true;

main().catch((error) => {
  console.error(`\n  x ${error.message}\n`);
  process.exit(1);
});

async function main() {
  banner("CLI", dryRun);

  // ---- 1. preflight ------------------------------------------------------
  step("checking the working tree");
  preflight({ dryRun, force: args.force });

  if (!dryRun) {
    requireCommand("npm", ["--version"], "npm is required to publish.");
    // Checked here rather than at the end, because the worst release is one
    // that fails after the tag has been pushed. A token in ~/.npmrc that has
    // expired fails exactly like never having logged in.
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

  const current = cliVersion();
  const version = args.bump ? bump(current, args.bump) : args.version || current;
  if (!/^\d+\.\d+\.\d+(-[\w.]+)?$/.test(version)) fail(`"${version}" is not a valid version.`);

  info(`CLI ${current}  ->  ${version}`);
  const api = requireMajorMatchesHostApi(version, "CLI");
  info(`Host API ${api}`);
  warnIfNoHostRelease(api);

  const tag = `cli-v${version}`;
  if (!dryRun) {
    if (publishedVersions().includes(version)) {
      fail(`${version} is already on npm.`, "A version is published once, and npm does not allow reuse. Bump it.");
    }
    if (tagExists(tag) && !tagIsOnHead(tag)) {
      fail(
        `The tag ${tag} exists and points somewhere else.`,
        `    git tag -d ${tag} && git push --delete origin ${tag}`,
      );
    }
  }

  // A release that already tagged this commit but never reached the registry
  // has done everything except the publish. Rebuilding and re-testing it would
  // only burn the thirty seconds a one-time password is valid for.
  if (!dryRun && tagIsOnHead(tag)) {
    warn(`${tag} is already on this commit and npm has no ${version}.`);
    warn("Resuming at the publish; nothing else is left to do.");
    step("publishing to npm");
    publishToNpm();
    finish(version, api);
    return;
  }

  if (!dryRun && !args.yes) await confirm(`Release CLI ${version}?`);

  // ---- 2. version --------------------------------------------------------
  step("writing the version");
  const restore = [
    writePackageVersion(path.join(cli, "package.json"), version),
    // The workspace root is never published, but npm reads it as the workspace
    // definition and a stale number there is a trap for the next reader.
    writePackageVersion(path.join(root, "package.json"), version),
  ];
  const undo = () => [...restore].reverse().forEach((put) => put());
  info(`zeta-cli/package.json, package.json -> ${version}`);

  // ---- 3. build ----------------------------------------------------------
  step("building the contract");
  gradle([":plugin-api:apiJar"]);

  step("copying artifacts into the CLI");
  run("node", [path.join(root, "scripts", "sync-assets.mjs")]);

  // ---- 4. test -----------------------------------------------------------
  if (!args["skip-tests"]) {
    step("running the CLI tests");
    // Through npm, so the test glob is the one the package itself declares -
    // and not allowed to fail: shipping a CLI with red tests is not a release.
    run("npm", ["test"], { cwd: cli });
  }

  // ---- 5. pack -----------------------------------------------------------
  step("packing the npm tarball");
  const packed = JSON.parse(run("npm", ["pack", "--dry-run", "--json"], { cwd: cli, capture: true }))[0];
  info(`${packed.filename}  ${(packed.unpackedSize / 1024 / 1024).toFixed(1)} MB unpacked, ${packed.entryCount} files`);

  const contract = packed.files.find((f) => f.path.includes(`zetaforge-api-${api}.jar`));
  if (!contract) {
    fail(`The contract jar for Host API ${api} is not in the npm package.`, "Run: npm run sync:assets");
  }
  if (packed.unpackedSize > 20 * 1024 * 1024) {
    fail("The npm package is over 20 MB.", "Something large slipped into zeta-cli/. Check the `files` field.");
  }

  if (dryRun) {
    // Only the files this script wrote are put back. Never `git checkout -- .`:
    // a build tool must not be able to destroy work it did not create.
    undo();
    console.log("\n  + dry run: nothing was committed, tagged or published\n");
    return;
  }

  // ---- 6. tag, then publish ---------------------------------------------
  step("committing and tagging");
  commitAndTag(tag, `release(cli): ${version} (Host API ${api})`);

  step("publishing to npm");
  publishToNpm();

  info("the tag triggers the Release (CLI) workflow, which attaches the contract jar");
  finish(version, api);
}

function finish(version, api) {
  ok(`released CLI ${version} (Host API ${api})`);
  console.log(`    npm      npm install -g zetaforge-cli@${version}`);
  console.log(`    try it   npx zetaforge-cli@${version} doctor\n`);
}

/**
 * A CLI with no Host to talk to is publishable but useless: `zeta host install`
 * resolves the newest Host release sharing this major, and if none exists it
 * can only report that. Worth knowing before publishing, not after.
 */
function warnIfNoHostRelease(api) {
  const result = spawnTool("gh", ["release", "list", "--limit", "100"], { capture: true });
  if (result.status !== 0) return;
  const hasHost = result.stdout
    .split("\n")
    .some((line) => new RegExp(`host-v${api}\\.\\d+\\.\\d+`).test(line));
  if (!hasHost) {
    warn(`No host-v${api}.x.y release exists yet.`);
    warn("`zeta host install` will have nothing to download until one is published.");
  }
}

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

/**
 * Publishes, through the browser when two-factor authentication is on.
 *
 * ### Why the terminal is not where a one-time password belongs
 * The first version piped npm's output so that its refusals could be turned
 * into instructions. That is also what made the browser flow impossible: npm
 * asks for the second factor by printing a link and *waiting*, and a child
 * process whose output is being captured can neither show the link nor be
 * answered. The result was a release that had built, tested, packed, committed
 * and tagged, and then died at the last step asking for a code that expires
 * before it can be typed.
 *
 * The fix is the terminal, not a flag: npm already defaults to `auth-type=web`
 * and prints the link by itself, but only when it has a TTY to print it into.
 * So the default path hands stdio straight through - the link appears, the
 * browser approves it, the publish continues - and `--auth-type=web` is passed
 * explicitly only to survive a machine where that config was set to `legacy`.
 * Only the `--otp` path keeps the old capture-and-classify behaviour, because
 * there the answer is already known and nothing has to be interactive.
 */
function publishToNpm() {
  // `parseArgs` turns a bare flag into `true`, so the negative form arrives as
  // `no-web` rather than as `web: false`.
  const web = args["no-web"] !== true && !args.otp;

  const publishArgs = ["publish", "--access", "public"];
  if (args.otp) publishArgs.push("--otp", String(args.otp));
  if (web) publishArgs.push("--auth-type", "web");

  if (web) {
    info("two-factor authentication goes through the browser;");
    info("npm prints a link below - open it and approve, publishing continues by itself");
    // Inherited, not captured: this is the whole point. npm needs a terminal to
    // print the link into and to wait on.
    const result = spawnTool("npm", publishArgs, { cwd: cli, stdio: "inherit" });
    if (result.status === 0) return;
    fail(
      "npm publish failed.",
      "npm's own output is above. If it still asked for a code rather than a link,\n" +
        "    the token in ~/.npmrc does not support the web flow - run once:\n" +
        "        npm login --auth-type=web\n" +
        "    and release again. To type the code instead:\n" +
        "        npm run release:cli -- --otp 123456",
    );
  }

  const result = spawnTool("npm", publishArgs, { cwd: cli, stdio: "pipe" });
  process.stdout.write(result.stdout || "");
  if (result.status === 0) return;

  const output = `${result.stdout || ""}${result.stderr || ""}`;
  process.stderr.write(output);
  const name = readJson(path.join(cli, "package.json")).name;

  if (/one-time pass|EOTP|otp/i.test(output)) {
    fail(
      "npm wants a one-time password.",
      "The code given was wrong or had already expired. Either:\n" +
        "    let the browser do it:  npm run release:cli\n" +
        "    or try again with a fresh code:  npm run release:cli -- --otp 123456",
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
    fail(
      "npm asked for payment: a scoped package defaults to private.",
      'publishConfig.access is already "public" - check it was not removed.',
    );
  }
  fail("npm publish failed.", "The output above is npm's.");
}
