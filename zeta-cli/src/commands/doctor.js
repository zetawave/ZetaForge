/**
 * `zeta doctor` — answers "why does this not work on my machine?" before the
 * question is asked.
 *
 * Every failing row carries the exact next action. A diagnostic that only says
 * "missing" has done half a job.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { ui, bytes } from "../ui.js";
import { paths, pkg, HOST_API_VERSION, CACHE_DIR } from "../config.js";
import { findJava, resetJavaCache, JAVA_MINIMUM_MAJOR } from "../env/java.js";
import { findAdb, findAndroidJar, findAndroidSdk, listDevices } from "../env/android.js";
import { isHostInstalled, hostVersion } from "../device.js";
import { ZetaError } from "../errors.js";

export const spec = {
  name: "doctor",
  summary: "Check that this machine can build and test plugins",
  usage: "zeta doctor [options]",
  options: [
    ["--install-jdk", "download a JDK into the zeta cache if none is usable"],
    ["--install-host", "install the ZetaForge app on the connected device"],
  ],
};

export async function run(options) {
  ui.banner(`zeta ${pkg.version}  (Host API ${HOST_API_VERSION})`);
  ui.plain();

  if (options["install-jdk"]) await installJdk();

  const rows = [];
  const problems = [];

  // Node
  rows.push(["node", process.version, "ok"]);

  // Java
  const java = findJava({ optional: true });
  if (java) {
    rows.push(["java", `${java.major}  ${ui.dim(java.home)}`, "ok"]);
  } else {
    rows.push(["java", `not found (need ${JAVA_MINIMUM_MAJOR}+)`, "bad"]);
    problems.push([
      "Install a JDK 17 or newer:",
      "  https://adoptium.net    — or let the CLI fetch one:",
      "  zeta doctor --install-jdk",
    ]);
  }

  // Android SDK
  const sdk = findAndroidSdk();
  rows.push(["android sdk", sdk || "not found", sdk ? "ok" : "bad"]);
  if (!sdk) {
    problems.push([
      "Install the Android SDK, then point ANDROID_HOME at it:",
      "  https://developer.android.com/studio  (or just the command line tools)",
    ]);
  }

  const adb = findAdb({ optional: true });
  rows.push(["adb", adb || "not found", adb ? "ok" : "bad"]);
  if (!adb) {
    problems.push([
      "Install platform-tools:",
      "  sdkmanager \"platform-tools\"",
      "  https://developer.android.com/tools/releases/platform-tools",
    ]);
  }

  const androidJar = findAndroidJar({ optional: true });
  rows.push([
    "android.jar",
    androidJar ? `API ${androidJar.api}` : "no platform installed",
    androidJar ? "ok" : "bad",
  ]);
  if (!androidJar) {
    problems.push([
      "Install one SDK platform to compile against:",
      "  sdkmanager \"platforms;android-35\"",
    ]);
  }

  // The contract shipped with this CLI
  const contract = fs.existsSync(paths.contractJar);
  rows.push([
    "contract",
    contract ? `zetaforge-api-${HOST_API_VERSION}.jar  ${ui.dim(bytes(fs.statSync(paths.contractJar).size))}` : "MISSING",
    contract ? "ok" : "bad",
  ]);
  if (!contract) {
    problems.push(["The CLI installation is incomplete. Reinstall:", "  npm install -g zetaforge-cli"]);
  }

  // Device
  let device = null;
  if (adb) {
    const connected = listDevices(adb).filter((d) => d.state === "device");
    device = connected[0] || null;
    rows.push([
      "device",
      connected.length === 0
        ? "none connected"
        : connected.map((d) => `${d.serial}${d.model ? ` (${d.model})` : ""}`).join(", "),
      connected.length ? "ok" : "warn",
    ]);
  }

  // Host app
  if (device) {
    const installed = isHostInstalled(adb, device.serial);
    const version = installed ? hostVersion(adb, device.serial) : null;
    rows.push(["zetaforge app", installed ? version || "installed" : "not installed", installed ? "ok" : "warn"]);
    if (!installed && options["install-host"]) {
      const { host } = await import("./device-commands.js");
      ui.plain();
      await host({ device: device.serial }, ["install"]);
    } else if (!installed) {
      problems.push(["The ZetaForge app is needed to test on the device:", "  zeta host install"]);
    }
  }

  rows.push(["cache", CACHE_DIR]);

  ui.table(rows);

  if (problems.length === 0) {
    ui.plain();
    ui.done("everything is ready");
    ui.plain();
    ui.info("next:  zeta new my-plugin");
    ui.plain();
    return 0;
  }

  ui.plain();
  ui.heading(`${problems.length} thing${problems.length > 1 ? "s" : ""} to fix`);
  for (const lines of problems) {
    ui.plain();
    for (const line of lines) ui.plain(`  ${line}`);
  }
  ui.plain();
  return 1;
}

/**
 * Fetches a Temurin JDK into the cache.
 *
 * Uses the system `tar`, which handles both .zip and .tar.gz on Windows 10+,
 * macOS and Linux — so the CLI needs no archive dependency of its own.
 */
async function installJdk() {
  const platform = { win32: "windows", darwin: "mac", linux: "linux" }[process.platform];
  const arch = { x64: "x64", arm64: "aarch64" }[os.arch()];
  if (!platform || !arch) {
    throw new ZetaError(`No prebuilt JDK for ${process.platform}/${os.arch()}.`, {
      hint: "Install a JDK 17+ manually and set JAVA_HOME.",
    });
  }

  const url =
    `https://api.adoptium.net/v3/binary/latest/21/ga/${platform}/${arch}/jdk/hotspot/normal/eclipse`;
  const archive = path.join(paths.jdk, platform === "windows" ? "jdk.zip" : "jdk.tar.gz");
  fs.mkdirSync(paths.jdk, { recursive: true });

  ui.step("downloading a JDK (about 190 MB, once)");
  const { downloadTo } = await import("../download.js");
  await downloadTo(url, archive);

  ui.step("extracting");
  const result = spawnSync("tar", ["-xf", archive, "-C", paths.jdk], { encoding: "utf8" });
  if (result.status !== 0) {
    throw new ZetaError("Could not extract the JDK.", {
      detail: `${result.stdout || ""}${result.stderr || ""}`,
      hint: `Extract ${archive} by hand into ${paths.jdk}, then set JAVA_HOME.`,
    });
  }
  fs.rmSync(archive, { force: true });
  resetJavaCache();

  const java = findJava({ optional: true });
  if (java) ui.done("JDK ready", java.home);
  else ui.fail("The JDK was extracted but no usable java was found.");
  ui.plain();
}
