/**
 * `zeta dev` — the loop that decides whether anyone keeps using this tool.
 *
 * Save a file, and a few seconds later the result is on screen. Everything else
 * in the CLI is in service of this command being fast and quiet.
 */
import fs from "node:fs";
import path from "node:path";
import { ui, duration } from "../ui.js";
import { PROJECT } from "../config.js";
import { loadProject } from "../project/manifest.js";
import { resolveDevice } from "../env/android.js";
import {
  clearLogs, importPackage, runPlugin, followLogs, prettyLogLine, findOutcome, isHostInstalled,
} from "../device.js";
import { ZetaError } from "../errors.js";

export const spec = {
  name: "dev",
  summary: "Rebuild, install and run on every save",
  usage: "zeta dev [options]",
  options: [
    ["--device <serial>", "which device to use"],
    ["--no-run", "build and install, but do not execute"],
    ["--once", "do one pass and exit"],
  ],
};

const DEBOUNCE_MS = 250;

export async function run(options, args, { buildProject }) {
  const project = loadProject();
  const { adb, device } = resolveDevice(options.device);

  if (!isHostInstalled(adb, device.serial)) {
    throw new ZetaError("ZetaForge is not installed on the device.", {
      hint: "Install it with:  zeta host install",
    });
  }

  ui.plain();
  ui.info(`${project.plugin.name} → ${device.model || device.serial}`);

  await pass(project, options, { buildProject, adb, device });
  if (options.once) return 0;

  const watched = [
    path.join(project.root, PROJECT.sourceDir),
    path.join(project.root, PROJECT.manifestFile),
  ].filter((p) => fs.existsSync(p));

  ui.plain();
  ui.info("watching for changes — Ctrl+C to stop");

  let timer = null;
  let running = false;
  let queued = false;

  const trigger = () => {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      if (running) { queued = true; return; }
      running = true;
      try {
        const reloaded = loadProject(project.root);
        await pass(reloaded, options, { buildProject, adb, device });
      } catch (error) {
        printFailure(error);
      } finally {
        running = false;
        ui.plain();
        ui.info("watching for changes — Ctrl+C to stop");
        if (queued) { queued = false; trigger(); }
      }
    }, DEBOUNCE_MS);
  };

  for (const target of watched) {
    fs.watch(target, { recursive: fs.statSync(target).isDirectory() }, (_event, filename) => {
      if (filename && /\.(kt|java|toml)$/.test(filename)) trigger();
      else if (!filename) trigger();
    });
  }

  // Keep the process alive until interrupted.
  await new Promise(() => {});
  return 0;
}

async function pass(project, options, { buildProject, adb, device }) {
  const started = Date.now();
  ui.plain();

  const built = await buildProject(options);
  ui.done("build", duration(built.ms));

  clearLogs(adb, device.serial);
  const importLines = await followLogs(adb, device.serial, {
    onReady: () => importPackage(adb, device.serial, built.result.file),
    until: (line) => /Installed in |Import failed|Validation failed|refused/i.test(line),
    timeoutMs: 30_000,
  });
  const refused = importLines.find((l) => /Import failed|Validation failed|refused/i.test(l));
  if (refused) {
    ui.fail("import refused", prettyLogLine(refused)?.message || "");
    return;
  }
  ui.done("install");

  if (options.run === false) {
    ui.info(`ready in ${duration(Date.now() - started)}`);
    return;
  }

  ui.plain();
  clearLogs(adb, device.serial);

  let outcome = null;
  await followLogs(adb, device.serial, {
    timeoutMs: 120_000,
    onReady: () => runPlugin(adb, device.serial, project.plugin.id),
    onLine: (line) => {
      const parsed = prettyLogLine(line);
      if (!parsed || parsed.source === "Runtime") return;
      const tone = parsed.level === "E" ? ui.red : parsed.level === "W" ? ui.yellow : ui.dim;
      ui.plain(`    ${tone(parsed.source.padEnd(14))} ${parsed.message}`);
    },
    until: (line) => {
      const parsed = prettyLogLine(line);
      if (!parsed || parsed.source !== "Runtime") return false;
      if (/^SUCCESS/.test(parsed.message)) { outcome = { ok: true, message: parsed.message }; return true; }
      if (/^(FAILURE|ERROR)/.test(parsed.message)) { outcome = { ok: false, message: parsed.message }; return true; }
      return false;
    },
  });

  if (!outcome) outcome = findOutcome(adb, device.serial, project.plugin.id);

  ui.plain();
  if (!outcome) ui.warn(`no result yet — check the phone screen for a permission prompt`);
  else if (outcome.ok) ui.done(outcome.message, duration(Date.now() - started));
  else ui.fail(outcome.message, duration(Date.now() - started));
}

/** In watch mode a failure must not end the session, only report itself. */
function printFailure(error) {
  ui.plain();
  ui.fail(error.message);
  if (error.where) ui.info(`at ${error.where}`);
  if (error.hint) ui.info(error.hint);
}
