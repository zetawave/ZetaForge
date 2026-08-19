/**
 * Finding a usable JVM.
 *
 * This is the single most common reason a JVM-backed CLI fails on someone
 * else's machine, so it looks in every place a JDK realistically lives instead
 * of demanding JAVA_HOME and giving up.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { ZetaError } from "../errors.js";
import { paths } from "../config.js";

const EXE = process.platform === "win32" ? ".exe" : "";
const MINIMUM_MAJOR = 17;

/** Candidate java executables, best first. */
function candidates() {
  const found = [];
  const add = (home) => {
    if (!home) return;
    const bin = path.join(home, "bin", `java${EXE}`);
    if (fs.existsSync(bin)) found.push(bin);
  };

  add(process.env.ZETA_JAVA_HOME);
  add(process.env.JAVA_HOME);
  add(path.join(paths.jdk, "current"));

  // A JDK the CLI downloaded earlier, whatever its folder is called.
  if (fs.existsSync(paths.jdk)) {
    for (const entry of fs.readdirSync(paths.jdk)) {
      add(path.join(paths.jdk, entry));
    }
  }

  // Android Studio ships a perfectly good JBR, and Android developers have it.
  const studio = {
    win32: [
      "C:\\Program Files\\Android\\Android Studio\\jbr",
      "C:\\Program Files\\Android\\Android Studio\\jre",
    ],
    darwin: [
      "/Applications/Android Studio.app/Contents/jbr/Contents/Home",
      "/Applications/Android Studio.app/Contents/jre/Contents/Home",
    ],
    linux: [
      "/opt/android-studio/jbr",
      path.join(os.homedir(), "android-studio/jbr"),
    ],
  }[process.platform] || [];
  studio.forEach(add);

  // Whatever is on PATH, last: it is the least predictable.
  const which = spawnSync(process.platform === "win32" ? "where" : "which", ["java"], {
    encoding: "utf8",
  });
  if (which.status === 0) {
    const first = which.stdout.split("\n")[0].trim();
    if (first && fs.existsSync(first)) found.push(first);
  }

  return [...new Set(found)];
}

/** `java -version` writes to stderr, and the format has changed over the years. */
function versionOf(javaBin) {
  const result = spawnSync(javaBin, ["-version"], { encoding: "utf8" });
  const output = `${result.stderr || ""}${result.stdout || ""}`;
  const match = output.match(/version "(\d+)(?:\.(\d+))?/);
  if (!match) return null;
  const major = Number(match[1]) === 1 ? Number(match[2]) : Number(match[1]);
  return { major, raw: output.split("\n")[0].trim(), bin: javaBin };
}

let cached;

/**
 * @param {object} [options]
 * @param {boolean} [options.optional] return null instead of throwing
 * @returns {{major:number, raw:string, bin:string, home:string}|null}
 */
export function findJava(options = {}) {
  if (cached !== undefined) return cached;

  const seen = [];
  for (const bin of candidates()) {
    const version = versionOf(bin);
    if (!version) continue;
    seen.push(version);
    if (version.major >= MINIMUM_MAJOR) {
      cached = { ...version, home: path.resolve(path.dirname(bin), "..") };
      return cached;
    }
  }

  cached = null;
  if (options.optional) return null;

  const tooOld = seen.length > 0;
  throw new ZetaError(
    tooOld
      ? `Java ${seen[0].major} found, but Java ${MINIMUM_MAJOR} or newer is required.`
      : "No Java installation found.",
    {
      hint: tooOld
        ? `Point ZETA_JAVA_HOME at a newer JDK, or run: zeta doctor --install-jdk`
        : "Install a JDK 17+ (https://adoptium.net), or let the CLI fetch one: zeta doctor --install-jdk",
      detail: tooOld ? seen.map((s) => `${s.raw}\n  at ${s.bin}`).join("\n") : undefined,
      docs: "troubleshooting.md",
    },
  );
}

/** Forgets the cached lookup — used after installing a JDK. */
export function resetJavaCache() {
  cached = undefined;
}

export { MINIMUM_MAJOR as JAVA_MINIMUM_MAJOR };
