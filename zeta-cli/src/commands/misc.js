/**
 * `zeta test`, `zeta inspect`, `zeta clean` — the smaller commands.
 */
import fs from "node:fs";
import path from "node:path";
import { ui, bytes } from "../ui.js";
import { ZetaError } from "../errors.js";
import { PROJECT } from "../config.js";
import { loadProject } from "../project/manifest.js";
import { prepareWorkspace, runGradle } from "../project/toolchain.js";
import { readZip } from "../zip.js";
import { readDexClasses } from "../dex.js";

// --- zeta test -------------------------------------------------------------

export const testSpec = {
  name: "test",
  summary: "Run the plugin's unit tests on the JVM, without a device",
  usage: "zeta test [options]",
  options: [["--verbose", "show the full test output"]],
};

export async function test(options) {
  const project = loadProject();
  const testDir = path.join(project.root, PROJECT.testDir);

  if (!fs.existsSync(testDir) || walkCount(testDir) === 0) {
    ui.plain();
    ui.warn(`No tests in ${PROJECT.testDir}/.`);
    ui.info("A plugin's execute() is an ordinary suspend function: it can be tested here,");
    ui.info("in milliseconds, without a phone. See docs/testing.md");
    ui.plain();
    return 0;
  }

  const { work } = prepareWorkspace(project);
  ui.step("running tests");
  const result = await runGradle(work, ["test"], { ...options, verbose: true });

  ui.plain();
  ui.done("tests passed", `${result.ms}ms`);
  ui.plain();
  return 0;
}

function walkCount(dir) {
  let count = 0;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) count += walkCount(path.join(dir, entry.name));
    else if (/\.(kt|java)$/.test(entry.name)) count++;
  }
  return count;
}

// --- zeta inspect ----------------------------------------------------------

export const inspectSpec = {
  name: "inspect",
  summary: "Show what is inside a .zeta",
  usage: "zeta inspect <file.zeta>",
  options: [["--classes", "also list every class in the DEX"]],
};

export async function inspect(options, args) {
  const file = args[0];
  if (!file) {
    throw new ZetaError("Which package?", { hint: "zeta inspect dist/my-plugin-1.0.0.zeta" });
  }
  if (!fs.existsSync(file)) {
    throw new ZetaError(`No such file: ${file}`);
  }

  const entries = readZip(file);
  const manifestEntry = entries.get("manifest.json");
  if (!manifestEntry) {
    throw new ZetaError("This is a ZIP, but not a .zeta: there is no manifest.json.", {
      hint: "Rebuild it with:  zeta build",
    });
  }
  const manifest = JSON.parse(manifestEntry.toString("utf8"));

  ui.plain();
  ui.table([
    ["name", `${manifest.name} ${manifest.version}`],
    ["id", manifest.pluginId],
    ["author", manifest.author || ui.dim("—")],
    ["license", manifest.license || ui.dim("—")],
    ["entry point", manifest.entryPoint],
    ["format", `v${manifest.formatVersion}`],
    ["host api", `${manifest.minHostApi} … ${manifest.maxHostApi}`],
    ["min sdk", String(manifest.minSdk)],
    ["package size", bytes(fs.statSync(file).size)],
  ]);

  if (manifest.permissions?.length) {
    ui.heading("Permissions");
    ui.table(manifest.permissions.map((p) => [
      p.name.replace("android.permission.", ""),
      `${p.reason}${p.optional ? ui.dim("  (optional)") : ""}`,
    ]));
  }
  if (manifest.specialAccess?.length) {
    ui.heading("Special access");
    ui.table(manifest.specialAccess.map((s) => [s.id, s.reason]));
  }
  if (manifest.settings?.length) {
    ui.heading("Settings");
    ui.table(manifest.settings.map((s) => [
      s.key,
      `${s.type}${s.default !== undefined && s.default !== null ? ui.dim(`  default ${JSON.stringify(s.default)}`) : ""}`,
    ]));
  }
  if (manifest.dependencies?.bundled?.length) {
    ui.heading("Bundled dependencies");
    ui.table(manifest.dependencies.bundled.map((d) => [d, ""]));
  }

  ui.heading("Code");
  const dexRows = [];
  for (const dex of manifest.code.dex) {
    const bytesOfDex = entries.get(dex.path);
    const classes = bytesOfDex ? readDexClasses(bytesOfDex) : [];
    dexRows.push([dex.path, `${bytes(dex.size)}  ${classes.length} classes  dex ${dex.dexVersion}`]);
    if (options.classes) {
      for (const cls of classes.sort()) {
        dexRows.push(["", ui.dim(cls.slice(1, -1).replace(/\//g, "."))]);
      }
    }
  }
  dexRows.push(["sources", `${manifest.code.source?.length || 0} file(s) shipped for the user to read`]);
  ui.table(dexRows);
  ui.plain();
  return 0;
}

// --- zeta clean ------------------------------------------------------------

export const cleanSpec = {
  name: "clean",
  summary: "Delete generated build state",
  usage: "zeta clean",
};

export async function clean() {
  const project = loadProject();
  let removed = 0;
  for (const dir of [PROJECT.workDir, PROJECT.outputDir]) {
    const target = path.join(project.root, dir);
    if (fs.existsSync(target)) {
      fs.rmSync(target, { recursive: true, force: true });
      removed++;
      ui.done(`removed ${dir}/`);
    }
  }
  if (removed === 0) ui.info("nothing to clean");
  ui.plain();
  return 0;
}
