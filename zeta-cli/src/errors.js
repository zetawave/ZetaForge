/**
 * The failure model.
 *
 * A build tool is judged by its errors more than by its successes. Every
 * failure this CLI raises carries a `hint`: the next thing to type or change.
 * Anything without one is a bug in the CLI, not in the user's project.
 */
import { ui } from "./ui.js";

export class ZetaError extends Error {
  /**
   * @param {string} message what went wrong, in one line
   * @param {object} [options]
   * @param {string} [options.hint] what to do about it
   * @param {string} [options.where] file:line the user should open
   * @param {string} [options.detail] raw output worth showing (compiler, adb)
   * @param {string} [options.docs] path under docs/ that explains the topic
   */
  constructor(message, options = {}) {
    super(message);
    this.name = "ZetaError";
    this.hint = options.hint;
    this.where = options.where;
    this.detail = options.detail;
    this.docs = options.docs;
  }
}

/** Prints a failure the way a person can act on, and returns the exit code. */
export function reportError(error) {
  ui.plain();
  if (error instanceof ZetaError) {
    ui.plain(`${ui.red("error")}  ${error.message}`);
    if (error.where) ui.plain(`       ${ui.dim("at")} ${error.where}`);
    if (error.detail) {
      ui.plain();
      for (const line of String(error.detail).trimEnd().split("\n").slice(-40)) {
        ui.plain(`  ${ui.dim("│")} ${line}`);
      }
    }
    if (error.hint) {
      ui.plain();
      ui.plain(`${ui.cyan("hint")}   ${error.hint}`);
    }
    if (error.docs) {
      ui.plain(`${ui.dim("docs")}   https://github.com/zetaforge/zetaforge/blob/main/zeta-cli/docs/${error.docs}`);
    }
  } else {
    ui.plain(`${ui.red("internal error")}  ${error.message}`);
    ui.plain();
    ui.plain(ui.dim(error.stack || ""));
    ui.plain();
    ui.plain(`${ui.cyan("hint")}   This is a bug in the zeta CLI. Please report it with the output above:`);
    ui.plain("       https://github.com/zetaforge/zetaforge/issues/new");
  }
  ui.plain();
  return 1;
}
