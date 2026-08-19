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
  hostApk: path.join(CACHE_DIR, "host", `zetaforge-${pkg.version}.apk`),
  jdk: path.join(CACHE_DIR, "jdk"),
};

/** Where the Host APK and other large artifacts are published. */
export const RELEASES = {
  repo: process.env.ZETA_REPO || "zetaforge/zetaforge",
  /** Overridable so you can point the CLI at a locally built Host. */
  hostApkUrl:
    process.env.ZETA_HOST_APK_URL ||
    `https://github.com/${process.env.ZETA_REPO || "zetaforge/zetaforge"}/releases/download/v${pkg.version}/zetaforge-host-${pkg.version}.apk`,
};

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

export const MANIFEST_FORMAT_VERSION = 3;
