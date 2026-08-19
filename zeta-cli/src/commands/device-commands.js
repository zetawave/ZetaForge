/**
 * `zeta install`, `zeta run`, `zeta logs`, `zeta devices`, `zeta host`.
 *
 * Everything that needs a phone or an emulator lives here, so the rules about
 * choosing a device and reporting its state are written once.
 */
import fs from "node:fs";
import path from "node:path";
import https from "node:https";
import { ui, duration, bytes } from "../ui.js";
import { ZetaError } from "../errors.js";
import { paths, HOST, RELEASES, pkg, PROJECT } from "../config.js";
import { resolveDevice, listDevices, findAdb } from "../env/android.js";
import { loadProject } from "../project/manifest.js";
import {
  importPackage, runPlugin, clearLogs, followLogs, prettyLogLine, findOutcome,
  isHostInstalled, hostVersion, installHost,
} from "../device.js";

// --- zeta devices ----------------------------------------------------------

export const devicesSpec = {
  name: "devices",
  summary: "List the devices adb can see",
  usage: "zeta devices",
};

export async function devices() {
  const adb = findAdb();
  const found = listDevices(adb);
  ui.plain();
  if (found.length === 0) {
    ui.warn("No devices.");
    ui.info("Connect a phone with USB debugging enabled, or start an emulator.");
    ui.plain();
    return 1;
  }
  ui.table(
    found.map((d) => [
      d.serial,
      `${d.state}${d.model ? `  ${d.model}` : ""}${d.emulator ? "  (emulator)" : ""}`,
      d.state === "device" ? "ok" : "warn",
    ]),
  );
  ui.plain();
  return 0;
}

// --- zeta install ----------------------------------------------------------

export const installSpec = {
  name: "install",
  summary: "Build if needed, then import the plugin into the Host",
  usage: "zeta install [file.zeta] [options]",
  options: [
    ["--device <serial>", "which device to use"],
    ["--no-build", "import the existing package without rebuilding"],
  ],
};

export async function install(options, args, { buildProject }) {
  const { adb, device } = resolveDevice(options.device);

  let file = args[0];
  let project = null;
  if (!file) {
    if (options.build === false) {
      project = loadProject();
      file = latestPackage(project);
    } else {
      const built = await buildProject(options);
      project = built.project;
      file = built.result.file;
      ui.done(`built ${project.plugin.name} ${project.plugin.version}`, duration(built.ms));
    }
  }
  if (!fs.existsSync(file)) {
    throw new ZetaError(`Package not found: ${file}`, { hint: "Run:  zeta build" });
  }

  ui.step(`installing on ${device.model || device.serial}`);
  clearLogs(adb, device.serial);
  const lines = await followLogs(adb, device.serial, {
    onReady: () => importPackage(adb, device.serial, file),
    until: (line) => /Installed in |Import failed|Validation failed|refused/i.test(line),
    timeoutMs: 30_000,
  });

  const failure = lines.find((l) => /Import failed|Validation failed|refused/i.test(l));
  if (failure) {
    const parsed = prettyLogLine(failure);
    throw new ZetaError(parsed ? parsed.message : "The Host refused the package.", {
      detail: lines.map((l) => prettyLogLine(l)?.message).filter(Boolean).join("\n"),
      docs: "troubleshooting.md",
    });
  }

  ui.done("imported", path.basename(file));
  ui.plain();
  ui.info("next:  zeta run");
  ui.plain();
  return 0;
}

function latestPackage(project) {
  const dir = path.join(project.root, PROJECT.outputDir);
  if (!fs.existsSync(dir)) {
    throw new ZetaError("Nothing built yet.", { hint: "Run:  zeta build" });
  }
  const files = fs.readdirSync(dir).filter((f) => f.endsWith(".zeta"));
  if (files.length === 0) throw new ZetaError("Nothing built yet.", { hint: "Run:  zeta build" });
  return path.join(dir, files.sort().pop());
}

// --- zeta run --------------------------------------------------------------

export const runSpec = {
  name: "run",
  summary: "Execute the plugin on the device and print the result",
  usage: "zeta run [options]",
  options: [
    ["--device <serial>", "which device to use"],
    ["--timeout <seconds>", "how long to wait for the result (default 120)"],
    ["--quiet", "only print the final result"],
  ],
};

export async function runCommand(options) {
  const project = loadProject();
  const { adb, device } = resolveDevice(options.device);

  if (!isHostInstalled(adb, device.serial)) {
    throw new ZetaError("ZetaForge is not installed on the device.", {
      hint: "Install it with:  zeta host install",
    });
  }

  ui.step(`running ${project.plugin.name}`);
  ui.plain();
  clearLogs(adb, device.serial);

  const started = Date.now();
  let outcome = null;

  await followLogs(adb, device.serial, {
    timeoutMs: Number(options.timeout || 120) * 1000,
    onReady: () => runPlugin(adb, device.serial, project.plugin.id),
    onLine: (line) => {
      const parsed = prettyLogLine(line);
      if (!parsed || options.quiet) return;
      if (parsed.source === "Runtime" && /^(SUCCESS|FAILURE)/.test(parsed.message)) return;
      const tone = parsed.level === "E" ? ui.red : parsed.level === "W" ? ui.yellow : ui.dim;
      ui.plain(`  ${tone(parsed.source.padEnd(14))} ${parsed.message}`);
    },
    until: (line) => {
      const parsed = prettyLogLine(line);
      if (!parsed || parsed.source !== "Runtime") return false;
      if (/^SUCCESS/.test(parsed.message)) { outcome = { ok: true, message: parsed.message }; return true; }
      if (/^(FAILURE|ERROR)/.test(parsed.message)) { outcome = { ok: false, message: parsed.message }; return true; }
      return false;
    },
  });

  // The run can finish faster than the log stream attaches: check the buffer.
  if (!outcome) outcome = findOutcome(adb, device.serial, project.plugin.id);

  ui.plain();
  if (!outcome) {
    ui.warn(`No result after ${duration(Date.now() - started)}.`);
    ui.info("The plugin may be waiting for a permission on screen, or still working.");
    ui.info("Follow it with:  zeta logs");
    ui.plain();
    return 1;
  }

  if (outcome.ok) ui.done(outcome.message, duration(Date.now() - started));
  else ui.fail(outcome.message, duration(Date.now() - started));
  ui.plain();
  return outcome.ok ? 0 : 1;
}

// --- zeta logs -------------------------------------------------------------

export const logsSpec = {
  name: "logs",
  summary: "Follow the ZetaForge log stream",
  usage: "zeta logs [options]",
  options: [["--device <serial>", "which device to use"]],
};

export async function logs(options) {
  const { adb, device } = resolveDevice(options.device);
  ui.plain();
  ui.info(`following ${device.model || device.serial} — Ctrl+C to stop`);
  ui.plain();
  await followLogs(adb, device.serial, {
    timeoutMs: 24 * 60 * 60 * 1000,
    onLine: (line) => {
      const parsed = prettyLogLine(line);
      if (!parsed) return;
      const tone = parsed.level === "E" ? ui.red : parsed.level === "W" ? ui.yellow : ui.dim;
      ui.plain(`  ${tone(parsed.source.padEnd(14))} ${parsed.message}`);
    },
  });
  return 0;
}

// --- zeta host -------------------------------------------------------------

export const hostSpec = {
  name: "host",
  summary: "Manage the ZetaForge app on the device",
  usage: "zeta host <install|version|uninstall> [options]",
  options: [
    ["--device <serial>", "which device to use"],
    ["--force", "reinstall even if it is already there"],
    ["--apk <path>", "install a locally built APK instead of downloading"],
  ],
};

export async function host(options, args) {
  const action = args[0] || "version";
  const { adb, device } = resolveDevice(options.device);

  if (action === "version") {
    const version = isHostInstalled(adb, device.serial) ? hostVersion(adb, device.serial) : null;
    ui.plain();
    ui.table([
      ["device", device.model || device.serial],
      ["ZetaForge", version || "not installed", version ? "ok" : "warn"],
      ["CLI builds for", `Host API ${pkg.version.split(".")[0]}`],
    ]);
    ui.plain();
    return version ? 0 : 1;
  }

  if (action === "uninstall") {
    const { adbSync } = await import("../device.js");
    adbSync(adb, device.serial, ["uninstall", HOST.packageName], { allowFailure: true });
    ui.done("uninstalled");
    return 0;
  }

  if (action !== "install") {
    throw new ZetaError(`Unknown host command "${action}".`, {
      hint: "One of: install, version, uninstall",
    });
  }

  if (isHostInstalled(adb, device.serial) && !options.force) {
    ui.done(`already installed`, hostVersion(adb, device.serial) || "");
    ui.info("Reinstall with:  zeta host install --force");
    return 0;
  }

  const apk = options.apk || (await ensureHostApk());
  ui.step("installing ZetaForge");
  installHost(adb, device.serial, apk);
  ui.done("installed", hostVersion(adb, device.serial) || "");
  ui.plain();
  return 0;
}

/** Downloads the Host APK once and caches it. Too big for npm; lives in Releases. */
export async function ensureHostApk() {
  if (fs.existsSync(paths.hostApk)) return paths.hostApk;

  fs.mkdirSync(path.dirname(paths.hostApk), { recursive: true });
  ui.step(`downloading ZetaForge ${pkg.version}`);
  await download(RELEASES.hostApkUrl, paths.hostApk);
  ui.done("downloaded", bytes(fs.statSync(paths.hostApk).size));
  return paths.hostApk;
}

function download(url, destination, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 5) return reject(new ZetaError("Too many redirects while downloading."));
    https
      .get(url, { headers: { "user-agent": `zeta-cli/${pkg.version}` } }, (response) => {
        if ([301, 302, 303, 307, 308].includes(response.statusCode)) {
          response.resume();
          return resolve(download(response.headers.location, destination, redirects + 1));
        }
        if (response.statusCode !== 200) {
          response.resume();
          return reject(
            new ZetaError(`Download failed (HTTP ${response.statusCode}).`, {
              hint:
                `Could not fetch ${url}\n` +
                "       If you build the Host yourself, pass it directly:  zeta host install --apk <path>",
            }),
          );
        }
        const total = Number(response.headers["content-length"] || 0);
        let received = 0;
        const file = fs.createWriteStream(`${destination}.part`);
        response.on("data", (chunk) => {
          received += chunk.length;
          if (total) ui.progress(`downloading  ${Math.round((received / total) * 100)}%`);
        });
        response.pipe(file);
        file.on("finish", () => {
          file.close(() => {
            ui.clearProgress();
            fs.renameSync(`${destination}.part`, destination);
            resolve(destination);
          });
        });
      })
      .on("error", (error) =>
        reject(new ZetaError(`Download failed: ${error.message}`, {
          hint: "Check your network, or install a locally built APK with --apk <path>.",
        })),
      );
  });
}
