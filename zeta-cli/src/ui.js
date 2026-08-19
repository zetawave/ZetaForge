/**
 * Everything the CLI prints.
 *
 * One module, so the whole tool speaks with one voice: an error always looks
 * like an error, a hint always looks like a hint, and there is exactly one
 * place to change if that ever needs to be true of a log file too.
 */
import pc from "picocolors";

const isTTY = process.stdout.isTTY === true;

export const ui = {
  /** A step that is happening now. */
  step(message) {
    console.log(`  ${pc.cyan("›")} ${message}`);
  },

  /** A step that finished, with an optional measurement. */
  done(message, detail) {
    const suffix = detail ? pc.dim(`  ${detail}`) : "";
    console.log(`  ${pc.green("✓")} ${message}${suffix}`);
  },

  fail(message, detail) {
    const suffix = detail ? pc.dim(`  ${detail}`) : "";
    console.log(`  ${pc.red("✗")} ${message}${suffix}`);
  },

  warn(message) {
    console.log(`  ${pc.yellow("!")} ${message}`);
  },

  info(message) {
    console.log(`  ${pc.dim(message)}`);
  },

  plain(message = "") {
    console.log(message);
  },

  heading(message) {
    console.log(`\n${pc.bold(message)}`);
  },

  /** The banner shown by long-running commands. */
  banner(version) {
    console.log(`\n${pc.bold("ZetaForge")} ${pc.dim(version)}`);
  },

  /**
   * A key/value block, aligned. Used by doctor and inspect, where the reader is
   * scanning for one line rather than reading prose.
   */
  table(rows) {
    const width = Math.max(...rows.map(([k]) => k.length));
    for (const [key, value, tone] of rows) {
      const painted =
        tone === "ok" ? pc.green(value)
          : tone === "bad" ? pc.red(value)
            : tone === "warn" ? pc.yellow(value)
              : value;
      console.log(`  ${key.padEnd(width)}  ${painted}`);
    }
  },

  /** Progress on one line, only when a human is watching. */
  progress(message) {
    if (!isTTY) return;
    process.stdout.write(`\r  ${pc.cyan("›")} ${message}${" ".repeat(10)}`);
  },

  clearProgress() {
    if (!isTTY) return;
    process.stdout.write(`\r${" ".repeat(process.stdout.columns || 80)}\r`);
  },

  bold: pc.bold,
  dim: pc.dim,
  cyan: pc.cyan,
  green: pc.green,
  red: pc.red,
  yellow: pc.yellow,
};

/** Milliseconds as something a person reads without converting. */
export function duration(ms) {
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60_000)}m ${Math.round((ms % 60_000) / 1000)}s`;
}

/** Bytes as something a person reads without counting digits. */
export function bytes(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
