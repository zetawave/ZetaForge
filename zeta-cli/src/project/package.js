/**
 * Assembling the `.zeta`, and refusing to ship one that is wrong.
 *
 * The package is not trusted just because the build succeeded: the DEX is
 * parsed and the entry point is looked up inside it. Nearly every "the plugin
 * does not load" report traces back to an entry point that does not exist, or
 * to a shared class that was bundled when it should have been borrowed from the
 * Host — both are catchable here, on the developer's machine, in milliseconds.
 */
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { ZetaError } from "../errors.js";
import { PROJECT } from "../config.js";
import { buildManifest } from "./manifest.js";
import { ZipWriter } from "../zip.js";
import { readDexClasses, dexVersion } from "../dex.js";

/** Classes that must come from the Host, never from the package. */
const FORBIDDEN_PREFIXES = [
  { prefix: "Lcom/zetaforge/sdk/", what: "the ZetaForge contract" },
  { prefix: "Lkotlin/", what: "the Kotlin standard library" },
  { prefix: "Lkotlinx/coroutines/", what: "Kotlin coroutines" },
];

export function assemblePackage(project, dexDir, options = {}) {
  const dexFiles = fs
    .readdirSync(dexDir)
    .filter((f) => f.endsWith(".dex"))
    .sort();

  if (dexFiles.length === 0) {
    throw new ZetaError("The build produced no DEX file.", {
      hint: "This is unexpected. Run `zeta build --verbose` and report the output.",
    });
  }

  const entryDescriptor = `L${project.plugin.entryPoint.replace(/\./g, "/")};`;
  const allClasses = new Set();
  const code = { dex: [], source: [] };

  for (const name of dexFiles) {
    const file = path.join(dexDir, name);
    const bytes = fs.readFileSync(file);
    for (const cls of readDexClasses(bytes)) allClasses.add(cls);
    code.dex.push({
      path: `dex/${name}`,
      size: bytes.length,
      sha256: crypto.createHash("sha256").update(bytes).digest("hex"),
      dexVersion: dexVersion(bytes),
    });
  }

  verifyEntryPoint(project, entryDescriptor, allClasses);
  verifyBoundary(allClasses);

  const sources = collectSources(project.root);
  code.source = sources.map((s) => ({ path: `source/${s.relative}`, displayName: s.relative }));

  const manifest = buildManifest(project, code);
  const zip = new ZipWriter();
  zip.add("manifest.json", `${JSON.stringify(manifest, null, 2)}\n`);
  for (const name of dexFiles) {
    // Already-compact bytecode: storing it keeps import fast and the size honest.
    zip.addFile(`dex/${name}`, path.join(dexDir, name));
  }
  for (const s of sources) zip.addFile(`source/${s.relative}`, s.absolute);

  const assetsDir = path.join(project.root, PROJECT.assetsDir);
  if (fs.existsSync(assetsDir)) {
    for (const asset of walk(assetsDir)) {
      zip.add(`assets/${asset.relative}`, fs.readFileSync(asset.absolute));
    }
  }

  const outDir = options.outDir || path.join(project.root, PROJECT.outputDir);
  fs.mkdirSync(outDir, { recursive: true });
  const outFile = path.join(outDir, `${path.basename(project.root)}-${project.plugin.version}.zeta`);
  const size = zip.writeTo(outFile);

  const sha = crypto.createHash("sha256").update(fs.readFileSync(outFile)).digest("hex");
  fs.writeFileSync(`${outFile}.sha256`, `${sha}  ${path.basename(outFile)}\n`);

  return { file: outFile, size, manifest, classes: allClasses.size, sha256: sha };
}

function verifyEntryPoint(project, descriptor, classes) {
  if (classes.has(descriptor)) return;

  // A wrong entry point is the most common mistake, so try to be useful about it.
  const candidates = [...classes]
    .filter((c) => c.endsWith("Plugin;") && !c.startsWith("Lkotlin"))
    .map((c) => c.slice(1, -1).replace(/\//g, "."));

  throw new ZetaError(
    `The entry point "${project.plugin.entryPoint}" is not in the compiled code.`,
    {
      where: project.file,
      hint: candidates.length
        ? `Did you mean one of these?\n       ${candidates.slice(0, 5).join("\n       ")}`
        : "Check that the class exists, is public, and that its package matches entryPoint.",
      docs: "plugin-anatomy.md",
    },
  );
}

function verifyBoundary(classes) {
  for (const { prefix, what } of FORBIDDEN_PREFIXES) {
    const offender = [...classes].find((c) => c.startsWith(prefix));
    if (!offender) continue;
    throw new ZetaError(`The package would bundle ${what}.`, {
      hint:
        "The Host and the plugin must share those classes, not each carry a copy —\n" +
        "       otherwise the Host cannot even cast your plugin to ZetaPlugin.\n" +
        "       A dependency is dragging it in: add excludeKotlin, or check [dependencies].",
      detail: `First offending class: ${offender.slice(1, -1).replace(/\//g, ".")}`,
      docs: "dependencies.md",
    });
  }
}

/** The sources shipped inside the package, so a user can read what they run. */
function collectSources(root) {
  const dir = path.join(root, PROJECT.sourceDir);
  if (!fs.existsSync(dir)) return [];
  return walk(dir)
    .filter((f) => f.relative.endsWith(".kt") || f.relative.endsWith(".java"))
    .map((f) => ({ ...f, relative: `${PROJECT.sourceDir}/${f.relative}` }));
}

function walk(dir, base = dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const absolute = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(absolute, base));
    else out.push({ absolute, relative: path.relative(base, absolute).replace(/\\/g, "/") });
  }
  return out;
}
