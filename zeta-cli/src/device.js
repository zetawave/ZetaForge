/**
 * Everything that talks to a device: installing the Host, importing a package,
 * running a plugin, following the log stream.
 *
 * The package is copied into the Host's own cache with `run-as` rather than
 * being handed over through the file picker: it is the only way to make the
 * loop scriptable, and it works with the debug-signed Host that plugin authors
 * install from the releases page.
 */
import fs from "node:fs";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { ZetaError } from "./errors.js";
import { HOST } from "./config.js";

/** Runs adb and returns stdout, throwing with adb's own words on failure. */
export function adbSync(adb, serial, args, options = {}) {
  const full = serial ? ["-s", serial, ...args] : args;
  const result = spawnSync(adb, full, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024, ...options });
  if (result.error) {
    throw new ZetaError(`Could not run adb: ${result.error.message}`, {
      hint: "Check that platform-tools is installed and on PATH.",
    });
  }
  if (result.status !== 0 && !options.allowFailure) {
    throw new ZetaError(`adb ${args[0]} failed.`, {
      detail: `${result.stdout || ""}${result.stderr || ""}`.trim(),
    });
  }
  return `${result.stdout || ""}`;
}

export function isHostInstalled(adb, serial) {
  const out = adbSync(adb, serial, ["shell", "pm", "list", "packages", HOST.packageName], {
    allowFailure: true,
  });
  return out.includes(HOST.packageName);
}

export function hostVersion(adb, serial) {
  const out = adbSync(adb, serial, ["shell", "dumpsys", "package", HOST.packageName], {
    allowFailure: true,
  });
  const name = out.match(/versionName=(\S+)/);
  return name ? name[1] : null;
}

export function installHost(adb, serial, apkPath) {
  if (!fs.existsSync(apkPath)) {
    throw new ZetaError(`Host APK not found at ${apkPath}`, {
      hint: "Run: zeta host install",
    });
  }
  const out = adbSync(adb, serial, ["install", "-r", "-g", apkPath], { allowFailure: true });
  if (!/Success/i.test(out)) {
    const conflict = /INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match/i.test(out);
    throw new ZetaError("Could not install the Host.", {
      detail: out.trim(),
      hint: conflict
        ? `A differently signed ZetaForge is already installed. Remove it first:\n       adb uninstall ${HOST.packageName}`
        : undefined,
    });
  }
}

/**
 * Copies a .zeta into the Host's cache and asks it to import.
 *
 * `run-as` needs a debuggable build — which is what the published developer
 * Host is. On a release Host this fails, and the message says so.
 */
export function importPackage(adb, serial, zetaFile) {
  if (!isHostInstalled(adb, serial)) {
    throw new ZetaError("ZetaForge is not installed on the device.", {
      hint: "Install it with:  zeta host install",
    });
  }

  const remoteTmp = "/data/local/tmp/zeta-import.zeta";
  const cachePath = `/data/data/${HOST.packageName}/cache/zeta-import.zeta`;

  adbSync(adb, serial, ["push", zetaFile, remoteTmp]);

  const copy = spawnSync(
    adb,
    [...(serial ? ["-s", serial] : []), "shell",
      `run-as ${HOST.packageName} sh -c 'cat > ${cachePath}' < ${remoteTmp}`],
    { encoding: "utf8" },
  );
  if (copy.status !== 0 || /run-as: |not debuggable/i.test(`${copy.stdout}${copy.stderr}`)) {
    throw new ZetaError("Could not hand the package to ZetaForge.", {
      detail: `${copy.stdout || ""}${copy.stderr || ""}`.trim(),
      hint:
        "The installed ZetaForge is a release build, which does not accept packages from the CLI.\n" +
        "       Install the developer build:  zeta host install --force",
      docs: "troubleshooting.md",
    });
  }

  adbSync(adb, serial, [
    "shell", "am", "start",
    "-n", HOST.activity,
    "-a", HOST.actionImport,
    "--es", HOST.extraPath, cachePath,
  ]);
}

export function runPlugin(adb, serial, pluginId) {
  adbSync(adb, serial, [
    "shell", "am", "start",
    "-n", HOST.activity,
    "-a", HOST.actionRun,
    "--es", HOST.extraPluginId, pluginId,
  ]);
}

export function clearLogs(adb, serial) {
  adbSync(adb, serial, ["logcat", "-c"], { allowFailure: true });
}

/** The ZetaForge lines from the log buffer, newest last. */
export function readLogs(adb, serial, { since } = {}) {
  const out = adbSync(adb, serial, ["logcat", "-d"], { allowFailure: true });
  return out
    .split("\n")
    .filter((line) => line.includes(HOST.logTag))
    .filter((line) => !since || line > since);
}

/**
 * Follows the log stream until the callback returns true, or the timeout.
 * @returns {Promise<string[]>} the lines seen
 */
export function followLogs(adb, serial, { onLine, until, onReady, timeoutMs = 120_000 }) {
  return new Promise((resolve) => {
    const args = [...(serial ? ["-s", serial] : []), "logcat"];
    const child = spawn(adb, args);
    const lines = [];
    let buffer = "";

    // adb needs a moment to attach; triggering the run any earlier can mean the
    // plugin finishes before the first line is seen.
    if (onReady) setTimeout(() => onReady(), 600);

    const finish = () => {
      clearTimeout(timer);
      child.kill();
      resolve(lines);
    };
    const timer = setTimeout(finish, timeoutMs);

    child.stdout.on("data", (chunk) => {
      buffer += chunk;
      const parts = buffer.split("\n");
      buffer = parts.pop() || "";
      for (const line of parts) {
        if (!line.includes(HOST.logTag)) continue;
        lines.push(line);
        onLine?.(line);
        if (until?.(line)) return finish();
      }
    });
    child.on("close", finish);
  });
}

/**
 * Strips logcat's prefix down to what a plugin author cares about.
 * `08-19 13:10:53.599 I ZetaForge/MediaCompressor: [id] message` -> `MediaCompressor  message`
 */
export function prettyLogLine(rawLine) {
  // adb on Windows ends lines with CRLF, and in JavaScript a dot does not
  // match a carriage return, so a stray one makes the pattern fail silently.
  const line = String(rawLine).replace(/[\r\n]+$/, "");
  const match = line.match(
    /(?:\s([VDIWE])\s+|\s([VDIWE])\/)ZetaForge\/([^\s:(]+)(?:\([^)]*\))?:\s*(?:\[[^\]]+\]\s*)?(.*)$/,
  );
  if (!match) return null;
  const [, levelA, levelB, source, message] = match;
  return { level: levelA || levelB, source, message: message.trim() };
}

/**
 * Looks for a finished run in the log buffer.
 *
 * The safety net under [followLogs]: a plugin can complete faster than a
 * process can be spawned, and the answer is still sitting in the buffer.
 */
export function findOutcome(adb, serial, pluginId) {
  const lines = readLogs(adb, serial).filter((l) => l.includes(`[${pluginId}]`));
  for (const line of lines.reverse()) {
    const parsed = prettyLogLine(line);
    if (!parsed || parsed.source !== "Runtime") continue;
    if (/^SUCCESS/.test(parsed.message)) return { ok: true, message: parsed.message };
    if (/^(FAILURE|ERROR)/.test(parsed.message)) return { ok: false, message: parsed.message };
  }
  return null;
}

export function pluginIdFromPackage(file) {
  // Used by `zeta run` when the project cannot be located.
  const name = path.basename(file);
  return name.replace(/-\d+\.\d+\.\d+.*\.zeta$/, "");
}
