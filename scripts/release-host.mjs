#!/usr/bin/env node
/**
 * Releases the Host, and nothing else.
 *
 *   npm run release:host:patch     4.1.0 -> 4.1.1
 *   npm run release:host:minor     4.1.0 -> 4.2.0
 *   npm run release:host:major     4.1.0 -> 5.0.0   (a new Host API)
 *   npm run release:host:dry       everything except tagging and uploading
 *
 * Flags: --skip-tests, --yes, --force, --version <v>, --bump <kind>
 *
 * What comes out is one GitHub release, tagged `host-v<version>`, carrying:
 *
 *   zetaforge-host-<v>-universal.apk    signed release build, every ABI
 *   zetaforge-host-<v>-<abi>.apk        signed release build, one ABI
 *   zetaforge-host-<v>-debug.apk        debuggable build, for the CLI
 *   SHA256SUMS.txt
 *
 * The debug build is published on purpose. `zeta install` hands a package to
 * the app through `run-as`, which Android allows only for a debuggable one, so
 * the developer loop needs it - and it is what `zeta host install` fetches. The
 * release builds are the ones for anybody who just wants to run the app.
 *
 * The APKs are built and uploaded from here rather than from CI because they
 * are signed with a keystore that exists only on this machine. That is the same
 * reason npm is published from the releaser's own credentials.
 */
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import {
  root, app, banner, bump, commitAndTag, confirm, fail, gradle, hostVersion, info,
  ok, parseArgs, preflight, readProperty, requireMajorMatchesHostApi, step,
  tagIsOnHead, tagExists, warn, writeProperty,
} from "./release-lib.mjs";
import { createRelease, releaseExists, requireGitHubAccess } from "./github.mjs";

const ABIS = ["armeabi-v7a", "arm64-v8a", "x86", "x86_64"];

const args = parseArgs(process.argv.slice(2));
const dryRun = args["dry-run"] === true;

main().catch((error) => {
  console.error(`\n  x ${error.message}\n`);
  process.exit(1);
});

async function main() {
  banner("Host", dryRun);

  // ---- 1. preflight ------------------------------------------------------
  step("checking the working tree");
  preflight({ dryRun, force: args.force });
  // Established before anything is built or tagged: finding out there is no way
  // to upload the result *after* pushing the tag is the failure this script is
  // written to avoid.
  if (!dryRun) info(`GitHub access: ${requireGitHubAccess()}`);

  const properties = path.join(app, "zetaforge.properties");
  const current = hostVersion();
  const version = args.bump ? bump(current, args.bump) : args.version || current;
  if (!/^\d+\.\d+\.\d+$/.test(version)) fail(`"${version}" is not a valid version.`);

  info(`Host ${current}  ->  ${version}`);
  const api = requireMajorMatchesHostApi(version, "Host");
  info(`Host API ${api}`);

  const tag = `host-v${version}`;
  if (!dryRun && tagExists(tag) && !tagIsOnHead(tag)) {
    fail(
      `The tag ${tag} exists and points somewhere else.`,
      `    git tag -d ${tag} && git push --delete origin ${tag}`,
    );
  }
  if (!dryRun && (await releaseExists(tag))) {
    fail(`${tag} is already published on GitHub.`, "Bump the version: a release is published once.");
  }

  if (!dryRun && !args.yes) await confirm(`Release Host ${version}?`);

  // ---- 2. version --------------------------------------------------------
  step("writing the version");
  const restore = [];
  restore.push(writeProperty(properties, "zetaforge.versionName", version));
  // Android compares builds by versionCode, never by name: without raising it,
  // the new APK would refuse to install over the old one.
  const versionCode = Number(readProperty(properties, "zetaforge.versionCode")) + 1;
  restore.push(writeProperty(properties, "zetaforge.versionCode", String(versionCode)));
  info(`versionName=${version}  versionCode=${versionCode}`);

  // Undone last-written-first: both writes land in the same file, so each
  // snapshot contains the edits made before it. Replaying them forwards would
  // put one of those edits back.
  const undo = () => [...restore].reverse().forEach((put) => put());

  // ---- 3. build ----------------------------------------------------------
  step("building the signed release APKs");
  gradle([":host:assembleRelease"]);

  step("building the debuggable APK the CLI installs");
  gradle([":host:assembleDebug"]);

  const staged = stage(version);

  // ---- 4. test -----------------------------------------------------------
  if (!args["skip-tests"]) {
    step("running the runtime tests");
    gradle([":runtime:test", ":host:testDebugUnitTest"]);
  }

  if (dryRun) {
    // Only the files this script wrote are put back. Never `git checkout -- .`:
    // a build tool must not be able to destroy work it did not create.
    undo();
    console.log(`\n  + dry run: ${staged.length} artifact(s) built, nothing tagged or uploaded\n`);
    return;
  }

  // ---- 5. tag and publish ------------------------------------------------
  if (tagIsOnHead(tag)) {
    warn(`${tag} is already on this commit; uploading the release only`);
  } else {
    step("committing and tagging");
    commitAndTag(tag, `release(host): ${version} (Host API ${api})`);
  }

  step("creating the GitHub release");
  const url = await publish(tag, version, api, staged);

  ok(`released Host ${version}`);
  console.log(`    apk      ${url}`);
  console.log(`    install  zeta host install --force\n`);
}

/**
 * Collects the APKs under release names.
 *
 * Gradle names its outputs after the module and the variant; what a release
 * carries has to say the product and the version instead, because it is
 * downloaded on its own and sits in somebody's Downloads folder afterwards.
 */
function stage(version) {
  step("staging the artifacts");
  const outputs = path.join(app, "host", "build", "outputs", "apk");
  const target = path.join(root, "build", "release-host");
  fs.rmSync(target, { recursive: true, force: true });
  fs.mkdirSync(target, { recursive: true });

  const wanted = [
    { from: path.join(outputs, "release", "host-universal-release.apk"), to: `zetaforge-host-${version}-universal.apk` },
    ...ABIS.map((abi) => ({
      from: path.join(outputs, "release", `host-${abi}-release.apk`),
      to: `zetaforge-host-${version}-${abi}.apk`,
    })),
    { from: path.join(outputs, "debug", "host-universal-debug.apk"), to: `zetaforge-host-${version}-debug.apk` },
  ];

  const staged = [];
  for (const item of wanted) {
    if (!fs.existsSync(item.from)) {
      fail(`Missing build output: ${path.relative(root, item.from)}`, "The build did not produce every split.");
    }
    const destination = path.join(target, item.to);
    fs.copyFileSync(item.from, destination);
    staged.push(destination);
    info(`${item.to}  ${(fs.statSync(destination).size / 1024 / 1024).toFixed(1)} MB`);
  }

  requireSigned(staged.filter((file) => !file.endsWith("-debug.apk")));

  const sums = staged
    .map((file) => `${sha256(file)}  ${path.basename(file)}`)
    .join("\n");
  const sumsFile = path.join(target, "SHA256SUMS.txt");
  fs.writeFileSync(sumsFile, `${sums}\n`);
  staged.push(sumsFile);

  return staged;
}

/**
 * An unsigned APK cannot be installed at all, and Gradle produces one quietly
 * when keystore.properties is missing. Catching it here beats publishing a
 * release nobody can use.
 */
function requireSigned(apks) {
  for (const apk of apks) {
    const zip = fs.readFileSync(apk);
    // Every signing scheme leaves a v1 signature block or an APK Signing Block
    // marker; the cheap check is for the v2+ magic, which all our configs emit.
    if (!zip.includes("APK Sig Block 42")) {
      fail(
        `${path.basename(apk)} is not signed.`,
        "Create app/keystore.properties (see app/scripts/make-keystore) and build again.",
      );
    }
  }
  info(`${apks.length} release APK(s) signed`);
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

async function publish(tag, version, api, files) {
  const notes = [
    `ZetaForge Host ${version} (Host API ${api}).`,
    "",
    "**Which file do I want?**",
    "",
    `- \`zetaforge-host-${version}-universal.apk\` - works on any device. Take this one if unsure.`,
    `- \`zetaforge-host-${version}-<abi>.apk\` - one architecture only, a few tens of KB smaller.`,
    `- \`zetaforge-host-${version}-debug.apk\` - the developer build. \`zeta install\` needs it, because`,
    "  handing a package to the app goes through `run-as`, which only a debuggable build allows.",
    "",
    "```bash",
    "zeta host install          # fetches the developer build for this Host API",
    "```",
  ].join("\n");

  return createRelease({ tag, name: `Host ${version}`, notes, files });
}
