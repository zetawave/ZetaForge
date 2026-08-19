/**
 * Finding the Android SDK: `adb` to talk to devices, and `android.jar` to
 * compile against the framework.
 *
 * Both come from the SDK, and a plugin author needs adb anyway to test on a
 * device, so requiring the SDK costs nothing extra — but the CLI must find it
 * without being told, and say precisely what is missing when it cannot.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { ZetaError } from "../errors.js";

const EXE = process.platform === "win32" ? ".exe" : "";

/** Where an SDK usually is, in the order worth trying. */
function sdkCandidates() {
  const home = os.homedir();
  const list = [
    process.env.ZETA_ANDROID_HOME,
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    {
      win32: path.join(home, "AppData", "Local", "Android", "Sdk"),
      darwin: path.join(home, "Library", "Android", "sdk"),
      linux: path.join(home, "Android", "Sdk"),
    }[process.platform],
    path.join(home, "Android", "Sdk"),
  ];
  return [...new Set(list.filter(Boolean))].filter(
    (dir) => fs.existsSync(path.join(dir, "platform-tools")) || fs.existsSync(path.join(dir, "platforms")),
  );
}

let cachedSdk;

export function findAndroidSdk() {
  if (cachedSdk !== undefined) return cachedSdk;
  cachedSdk = sdkCandidates()[0] || null;
  return cachedSdk;
}

/**
 * @param {object} [options]
 * @param {boolean} [options.optional]
 * @returns {string|null} absolute path to adb
 */
export function findAdb(options = {}) {
  const sdk = findAndroidSdk();
  const fromSdk = sdk && path.join(sdk, "platform-tools", `adb${EXE}`);
  if (fromSdk && fs.existsSync(fromSdk)) return fromSdk;

  const which = spawnSync(process.platform === "win32" ? "where" : "which", ["adb"], {
    encoding: "utf8",
  });
  if (which.status === 0) {
    const first = which.stdout.split("\n")[0].trim();
    if (first && fs.existsSync(first)) return first;
  }

  if (options.optional) return null;
  throw new ZetaError("adb not found.", {
    hint:
      "Install the Android SDK platform-tools and make sure ANDROID_HOME points at the SDK:\n" +
      "       https://developer.android.com/tools/releases/platform-tools",
    docs: "troubleshooting.md",
  });
}

/**
 * The newest installed platform's android.jar. Plugins compile against it but
 * never bundle it, so "newest available" is the right choice: it only widens
 * what the code can reference, and `minSdk` is what actually gates a device.
 *
 * @param {object} [options]
 * @param {boolean} [options.optional]
 * @returns {{jar:string, api:number}|null}
 */
export function findAndroidJar(options = {}) {
  const sdk = findAndroidSdk();
  const platforms = sdk && path.join(sdk, "platforms");

  if (platforms && fs.existsSync(platforms)) {
    const installed = fs
      .readdirSync(platforms)
      .map((name) => ({ name, api: Number((name.match(/android-(\d+)/) || [])[1]) }))
      .filter((p) => Number.isFinite(p.api))
      .filter((p) => fs.existsSync(path.join(platforms, p.name, "android.jar")))
      .sort((a, b) => b.api - a.api);

    if (installed.length > 0) {
      return { jar: path.join(platforms, installed[0].name, "android.jar"), api: installed[0].api };
    }
  }

  if (options.optional) return null;
  throw new ZetaError("No Android platform found (android.jar is missing).", {
    hint:
      "Install one with the SDK manager, for example:\n" +
      "       sdkmanager \"platforms;android-35\"\n" +
      "       or open Android Studio > SDK Manager and tick an SDK Platform.",
    docs: "troubleshooting.md",
  });
}

/** Devices adb can currently see. */
export function listDevices(adb = findAdb()) {
  const result = spawnSync(adb, ["devices", "-l"], { encoding: "utf8" });
  if (result.status !== 0) return [];
  return result.stdout
    .split("\n")
    .slice(1)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith("*"))
    .map((line) => {
      const [serial, state, ...rest] = line.split(/\s+/);
      const model = (rest.find((r) => r.startsWith("model:")) || "").replace("model:", "");
      return { serial, state, model: model || undefined, emulator: serial.startsWith("emulator-") };
    })
    .filter((d) => d.serial);
}

/**
 * Picks the device to work with.
 * @param {string} [preferred] a serial from --device
 */
export function resolveDevice(preferred) {
  const adb = findAdb();
  const devices = listDevices(adb).filter((d) => d.state === "device");

  if (preferred) {
    const match = devices.find((d) => d.serial === preferred);
    if (!match) {
      throw new ZetaError(`Device "${preferred}" is not connected.`, {
        hint: devices.length
          ? `Connected right now: ${devices.map((d) => d.serial).join(", ")}`
          : "Run: zeta devices",
      });
    }
    return { adb, device: match };
  }

  if (devices.length === 0) {
    const offline = listDevices(adb).filter((d) => d.state !== "device");
    throw new ZetaError("No device connected.", {
      hint: offline.length
        ? `A device is visible but not ready (${offline[0].state}). Unlock the phone and accept the USB debugging prompt.`
        : "Connect a phone with USB debugging on, or start an emulator, then run: zeta devices",
      docs: "troubleshooting.md",
    });
  }

  if (devices.length > 1) {
    throw new ZetaError(`${devices.length} devices connected — pick one.`, {
      hint: `Add --device <serial>. Connected: ${devices.map((d) => d.serial).join(", ")}`,
    });
  }

  return { adb, device: devices[0] };
}
