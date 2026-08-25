/**
 * Constants and paths. Nothing here reads the network or the disk beyond
 * resolving locations, so it is safe to import from anywhere.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const CLI_ROOT = path.resolve(fileURLToPath(new URL("..", import.meta.url)));

export const pkg = JSON.parse(fs.readFileSync(path.join(CLI_ROOT, "package.json"), "utf8"));

/** The npm major version IS the Host API version. See docs/versioning.md. */
export const HOST_API_VERSION = Number(pkg.version.split(".")[0]);

/** Where downloads and toolchains are cached, shared by every project. */
export const CACHE_DIR =
  process.env.ZETA_HOME || path.join(os.homedir(), ".zetaforge");

export const paths = {
  cli: CLI_ROOT,
  assets: path.join(CLI_ROOT, "assets"),
  templates: path.join(CLI_ROOT, "templates"),
  docs: path.join(CLI_ROOT, "docs"),
  contractJar: path.join(CLI_ROOT, "assets", `zetaforge-api-${HOST_API_VERSION}.jar`),
  gradleRecipe: path.join(CLI_ROOT, "assets", "gradle"),
  cache: CACHE_DIR,
  hostApkDir: path.join(CACHE_DIR, "host"),
  jdk: path.join(CACHE_DIR, "jdk"),
};

/** Where a downloaded Host APK is cached, named after the Host's own version. */
export function hostApkPath(hostVersion) {
  return path.join(paths.hostApkDir, `zetaforge-${hostVersion}.apk`);
}

/**
 * Where the Host APK and other large artifacts are published.
 *
 * The Host and the CLI are released separately and their minor and patch
 * numbers drift apart, so the APK is found by asking which Host releases exist
 * for this Host API version - never by assuming it carries the CLI's own
 * version number. Only the major is shared, and only the major means anything.
 */
export const RELEASES = {
  repo: process.env.ZETA_REPO || "zetawave/ZetaForge",
  /** Tag prefix of the Host release train. See docs/versioning.md. */
  hostTagPrefix: "host-v",
  /** Set to point the CLI at one specific APK, local build included. */
  hostApkUrlOverride: process.env.ZETA_HOST_APK_URL || null,
};

/**
 * Assets a Host release publishes, by name.
 *
 * `debug` is what `zeta host install` fetches: importing a plugin from the CLI
 * goes through `run-as`, which only a debuggable build allows. The release
 * builds are the ones to hand to somebody who just wants to run the app.
 */
export function hostAssetName(hostVersion, kind = "debug") {
  return `zetaforge-host-${hostVersion}-${kind}.apk`;
}

/** The Host application, as installed on a device. */
export const HOST = {
  packageName: "com.zetaforge.app",
  activity: "com.zetaforge.app/.MainActivity",
  actionImport: "com.zetaforge.app.action.IMPORT_FILE",
  actionRun: "com.zetaforge.app.action.RUN_PLUGIN",
  extraPath: "path",
  extraPluginId: "pluginId",
  logTag: "ZetaForge",
};

/** Files and folders a plugin project owns. */
export const PROJECT = {
  manifestFile: "zetaplugin.toml",
  sourceDir: "src",
  testDir: "test",
  assetsDir: "assets",
  outputDir: "dist",
  workDir: ".zeta",
};

export const MANIFEST_FORMAT_VERSION = 4;

/**
 * Version of the *screen* contract a plugin with a `[ui]` block is built
 * against. Separate from the Host API because it moves with Compose: see
 * com.zetaforge.sdk.ZetaSdk.UI_API_VERSION.
 */
export const UI_API_VERSION = 1;
