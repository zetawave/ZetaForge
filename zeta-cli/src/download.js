/**
 * HTTP downloads with redirects and a progress line. Small on purpose: the CLI
 * fetches exactly two things, the Host APK and (optionally) a JDK.
 */
import fs from "node:fs";
import https from "node:https";
import { ui } from "./ui.js";
import { ZetaError } from "./errors.js";
import { pkg } from "./config.js";

export function downloadTo(url, destination, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 5) return reject(new ZetaError("Too many redirects while downloading."));

    https
      .get(url, { headers: { "user-agent": `zeta-cli/${pkg.version}` } }, (response) => {
        if ([301, 302, 303, 307, 308].includes(response.statusCode)) {
          response.resume();
          return resolve(downloadTo(response.headers.location, destination, redirects + 1));
        }
        if (response.statusCode !== 200) {
          response.resume();
          return reject(new ZetaError(`Download failed (HTTP ${response.statusCode}).`, {
            hint: `Could not fetch ${url}`,
          }));
        }

        const total = Number(response.headers["content-length"] || 0);
        let received = 0;
        const file = fs.createWriteStream(`${destination}.part`);

        response.on("data", (chunk) => {
          received += chunk.length;
          if (total) ui.progress(`downloading  ${Math.round((received / total) * 100)}%`);
        });
        response.pipe(file);

        file.on("error", (error) => reject(new ZetaError(`Could not write ${destination}: ${error.message}`)));
        file.on("finish", () =>
          file.close(() => {
            ui.clearProgress();
            fs.renameSync(`${destination}.part`, destination);
            resolve(destination);
          }),
        );
      })
      .on("error", (error) =>
        reject(new ZetaError(`Download failed: ${error.message}`, {
          hint: "Check your network connection or proxy settings.",
        })),
      );
  });
}
