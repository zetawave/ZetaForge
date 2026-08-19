/**
 * Argument parsing and dispatch.
 *
 * No argument-parsing library: the surface is small, and hand-written parsing
 * means the help text and the behaviour cannot drift apart.
 */
import { ui } from "./ui.js";
import { reportError, ZetaError } from "./errors.js";
import { pkg, HOST_API_VERSION } from "./config.js";

import * as buildCmd from "./commands/build.js";
import * as newCmd from "./commands/new.js";
import * as devCmd from "./commands/dev.js";
import * as doctorCmd from "./commands/doctor.js";
import * as deviceCmd from "./commands/device-commands.js";
import * as miscCmd from "./commands/misc.js";

const context = { buildProject: buildCmd.buildProject };

const COMMANDS = [
  { spec: newCmd.spec, run: (o, a) => newCmd.run(o, a) },
  { spec: buildCmd.spec, run: (o) => buildCmd.run(o) },
  { spec: devCmd.spec, run: (o, a) => devCmd.run(o, a, context) },
  { spec: deviceCmd.installSpec, run: (o, a) => deviceCmd.install(o, a, context) },
  { spec: deviceCmd.runSpec, run: (o) => deviceCmd.runCommand(o) },
  { spec: deviceCmd.logsSpec, run: (o) => deviceCmd.logs(o) },
  { spec: miscCmd.testSpec, run: (o) => miscCmd.test(o) },
  { spec: miscCmd.inspectSpec, run: (o, a) => miscCmd.inspect(o, a) },
  { spec: deviceCmd.devicesSpec, run: () => deviceCmd.devices() },
  { spec: deviceCmd.hostSpec, run: (o, a) => deviceCmd.host(o, a) },
  { spec: doctorCmd.spec, run: (o) => doctorCmd.run(o) },
  { spec: miscCmd.cleanSpec, run: () => miscCmd.clean() },
];

/** Options that take a value rather than being a flag. */
const VALUED = new Set(["template", "id", "author", "device", "out", "timeout", "apk"]);

export function parseArgs(argv) {
  const options = {};
  const args = [];

  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (!token.startsWith("--")) { args.push(token); continue; }

    const body = token.slice(2);
    if (body.includes("=")) {
      const [key, value] = body.split(/=(.*)/s);
      options[key] = value;
    } else if (body.startsWith("no-")) {
      options[body.slice(3)] = false;
    } else if (VALUED.has(body)) {
      options[body] = argv[++i];
    } else {
      options[body] = true;
    }
  }
  return { options, args };
}

function help(commandName) {
  if (commandName) {
    const command = COMMANDS.find((c) => c.spec.name === commandName);
    if (!command) throw unknown(commandName);
    const { spec } = command;
    ui.plain();
    ui.plain(`  ${ui.bold(spec.summary)}`);
    ui.plain();
    ui.plain(`  ${ui.dim("usage")}  ${spec.usage}`);
    if (spec.options?.length) {
      ui.plain();
      ui.table(spec.options);
    }
    ui.plain();
    return 0;
  }

  ui.banner(`${pkg.version}  ${ui.dim(`Host API ${HOST_API_VERSION}`)}`);
  ui.plain();
  ui.plain(`  ${ui.dim("usage")}  zeta <command> [options]`);
  ui.plain();
  ui.table(COMMANDS.map((c) => [c.spec.name, c.spec.summary]));
  ui.plain();
  ui.plain(`  ${ui.dim("zeta help <command>")}  for details on one command`);
  ui.plain(`  ${ui.dim("docs")}                 https://github.com/zetaforge/zetaforge/tree/main/zeta-cli/docs`);
  ui.plain();
  return 0;
}

function unknown(name) {
  const known = COMMANDS.map((c) => c.spec.name);
  const close = known.filter((k) => k.startsWith(name[0]) || k.includes(name));
  return new ZetaError(`Unknown command "${name}".`, {
    hint: close.length ? `Did you mean:  zeta ${close[0]}` : `Run "zeta help" to see the commands.`,
  });
}

export async function run(argv) {
  const { options, args } = parseArgs(argv);
  const name = args.shift();

  try {
    if (options.version || name === "version") {
      ui.plain(pkg.version);
      return finish(0);
    }
    if (!name || name === "help" || options.help) {
      return finish(help(name === "help" ? args[0] : undefined));
    }

    const command = COMMANDS.find((c) => c.spec.name === name);
    if (!command) throw unknown(name);

    const code = await command.run(options, args);
    return finish(code ?? 0);
  } catch (error) {
    return finish(reportError(error));
  }
}

function finish(code) {
  process.exitCode = code;
  return code;
}
