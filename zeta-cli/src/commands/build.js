/**
 * `zeta build` — from sources to a verified `.zeta`.
 */
import fs from "node:fs";
import path from "node:path";
import { ui, duration, bytes } from "../ui.js";
import { PROJECT } from "../config.js";
import { loadProject } from "../project/manifest.js";
import { prepareWorkspace, runGradle } from "../project/toolchain.js";
import { assemblePackage } from "../project/package.js";

export const spec = {
  name: "build",
  summary: "Compile the plugin and package it as a .zeta",
  usage: "zeta build [options]",
  options: [
    ["--verbose", "show the full build output"],
    ["--offline", "do not touch the network (dependencies must be cached)"],
    ["--out <dir>", "where to write the package (default: dist/)"],
  ],
};

/** Shared by `build` and `dev`: returns the assembled package. */
export async function buildProject(options = {}) {
  const project = loadProject();
  const started = Date.now();

  const { work } = prepareWorkspace(project);
  const compile = await runGradle(work, ["zetaDex"], options);

  const dexDir = path.join(work, "build", "dex");
  const result = assemblePackage(project, dexDir, { outDir: options.out });

  return { project, result, ms: Date.now() - started, compileMs: compile.ms };
}

export async function run(options) {
  const { project, result, ms } = await buildProject(options);

  ui.plain();
  ui.done(`${project.plugin.name} ${project.plugin.version}`, duration(ms));
  ui.table([
    ["package", path.relative(process.cwd(), result.file)],
    ["size", bytes(result.size)],
    ["classes", `${result.classes}`],
    ["entry point", project.plugin.entryPoint, "ok"],
    ["settings", result.manifest.settings.length ? result.manifest.settings.map((s) => s.key).join(", ") : "none"],
    ["permissions", result.manifest.permissions.length
      ? result.manifest.permissions.map((p) => p.name.split(".").pop()).join(", ")
      : "none"],
  ]);
  ui.plain();
  ui.info("next:  zeta install    (put it on a device)");
  ui.plain();
  return 0;
}

/** Removes generated build state. Used by `zeta clean`. */
export function clean(root) {
  for (const dir of [PROJECT.workDir, PROJECT.outputDir]) {
    const target = path.join(root, dir);
    if (fs.existsSync(target)) fs.rmSync(target, { recursive: true, force: true });
  }
}
