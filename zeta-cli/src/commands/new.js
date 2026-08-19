/**
 * `zeta new` — scaffolding.
 *
 * The generated project is deliberately tiny: one descriptor, one source file,
 * one test. Everything else — build files, wrapper, dependency wiring — is
 * generated on demand into `.zeta/` and never has to be read or maintained.
 */
import fs from "node:fs";
import path from "node:path";
import { ui } from "../ui.js";
import { ZetaError } from "../errors.js";
import { paths, HOST_API_VERSION } from "../config.js";

export const spec = {
  name: "new",
  summary: "Create a new plugin project",
  usage: "zeta new <name> [options]",
  options: [
    ["--template <name>", "basic (default), network, background"],
    ["--id <id>", "plugin id, e.g. com.example.myplugin"],
    ["--author <name>", "author name"],
    ["--list-templates", "show the available templates"],
  ],
};

const NAME_PATTERN = /^[a-z][a-z0-9-]*$/;

export function availableTemplates() {
  if (!fs.existsSync(paths.templates)) return [];
  return fs
    .readdirSync(paths.templates, { withFileTypes: true })
    .filter((e) => e.isDirectory())
    .map((e) => {
      const meta = path.join(paths.templates, e.name, "template.json");
      const info = fs.existsSync(meta) ? JSON.parse(fs.readFileSync(meta, "utf8")) : {};
      return { name: e.name, description: info.description || "" };
    });
}

export async function run(options, args) {
  if (options["list-templates"]) {
    ui.heading("Templates");
    ui.table(availableTemplates().map((t) => [t.name, t.description]));
    ui.plain();
    return 0;
  }

  const name = args[0];
  if (!name) {
    throw new ZetaError("A name is required.", {
      hint: "For example:  zeta new weather",
    });
  }
  if (!NAME_PATTERN.test(name)) {
    throw new ZetaError(`"${name}" is not a valid project name.`, {
      hint: "Use lowercase letters, digits and dashes, starting with a letter:  my-plugin",
    });
  }

  const target = path.resolve(process.cwd(), name);
  if (fs.existsSync(target) && fs.readdirSync(target).length > 0) {
    throw new ZetaError(`${name}/ already exists and is not empty.`, {
      hint: "Pick another name, or remove the folder first.",
    });
  }

  const templateName = options.template || "basic";
  const templateDir = path.join(paths.templates, templateName);
  if (!fs.existsSync(templateDir)) {
    throw new ZetaError(`Unknown template "${templateName}".`, {
      hint: `Available: ${availableTemplates().map((t) => t.name).join(", ")}`,
    });
  }

  const camel = name.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
  const className = camel.charAt(0).toUpperCase() + camel.slice(1) + "Plugin";
  const id = options.id || `com.example.${camel.toLowerCase()}`;
  const packageName = id;

  const values = {
    NAME: name,
    DISPLAY_NAME: name.replace(/-/g, " ").replace(/\b\w/g, (c) => c.toUpperCase()),
    PLUGIN_ID: id,
    PACKAGE: packageName,
    CLASS: className,
    AUTHOR: options.author || "",
    HOST_API: String(HOST_API_VERSION),
    YEAR: String(new Date().getFullYear()),
  };

  const packagePath = packageName.split(".").join("/");
  let count = 0;
  for (const entry of walk(templateDir)) {
    if (entry.relative === "template.json") continue;
    const relative = entry.relative
      .replace(/__package__/g, packagePath)
      .replace(/__CLASS__/g, className)
      .replace(/\.tmpl$/, "");
    const destination = path.join(target, relative);
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.writeFileSync(destination, substitute(fs.readFileSync(entry.absolute, "utf8"), values));
    count++;
  }

  ui.plain();
  ui.done(`Created ${name}/`, `${count} files, template "${templateName}"`);
  ui.plain();
  ui.table([
    ["plugin id", id],
    ["entry point", `${packageName}.${className}`],
  ]);
  ui.plain();
  ui.info("next:");
  ui.info(`  cd ${name}`);
  ui.info("  zeta dev        build, install and run on every save");
  ui.plain();
  return 0;
}

function substitute(content, values) {
  return content.replace(/\{\{(\w+)\}\}/g, (match, key) =>
    Object.prototype.hasOwnProperty.call(values, key) ? values[key] : match,
  );
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
