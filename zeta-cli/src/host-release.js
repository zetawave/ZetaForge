/**
 * Finding the Host APK that belongs to this CLI.
 *
 * The Host and the CLI ship on separate trains, so their versions drift: CLI
 * 4.3.1 may well be the right partner for Host 4.1.0. What binds them is the
 * major, which is the Host API version - a plugin built by CLI 4.x runs on any
 * Host 4.x and on no other.
 *
 * So the APK is resolved, not computed: ask GitHub which Host releases exist,
 * take the newest one whose major matches, and download its asset.
 */
import https from "node:https";
import { RELEASES, HOST_API_VERSION, hostAssetName, pkg } from "./config.js";
import { ZetaError } from "./errors.js";

/**
 * The newest published Host release built for this CLI's Host API version.
 *
 * @returns {Promise<{version: string, tag: string, apkUrl: string}>}
 */
export async function resolveHostRelease(kind = "debug") {
  if (RELEASES.hostApkUrlOverride) {
    return { version: "override", tag: "override", apkUrl: RELEASES.hostApkUrlOverride };
  }

  const releases = await getJson(
    `https://api.github.com/repos/${RELEASES.repo}/releases?per_page=100`,
  );

  const prefix = RELEASES.hostTagPrefix;
  const candidates = releases
    .filter((release) => !release.draft && !release.prerelease)
    .map((release) => ({ release, version: hostVersionOf(release.tag_name, prefix) }))
    .filter((entry) => entry.version && major(entry.version) === HOST_API_VERSION)
    .sort((a, b) => compareVersions(b.version, a.version));

  if (candidates.length === 0) {
    throw new ZetaError(`No Host release found for Host API ${HOST_API_VERSION}.`, {
      hint:
        `The CLI is ${pkg.version}, so it needs a ${prefix}${HOST_API_VERSION}.x.y release.\n` +
        "       Build one yourself and install it with:  zeta host install --apk <path>",
    });
  }

  const { release, version } = candidates[0];
  const wanted = hostAssetName(version, kind);
  const asset = (release.assets || []).find((a) => a.name === wanted);
  if (!asset) {
    throw new ZetaError(`${release.tag_name} does not publish ${wanted}.`, {
      hint: "Available: " + (release.assets || []).map((a) => a.name).join(", "),
    });
  }

  return { version, tag: release.tag_name, apkUrl: asset.browser_download_url };
}

/** `host-v4.1.0` -> `4.1.0`; anything else -> null. */
function hostVersionOf(tag, prefix) {
  if (typeof tag !== "string" || !tag.startsWith(prefix)) return null;
  const rest = tag.slice(prefix.length);
  return /^\d+\.\d+\.\d+$/.test(rest) ? rest : null;
}

const major = (version) => Number(version.split(".")[0]);

function compareVersions(a, b) {
  const left = a.split(".").map(Number);
  const right = b.split(".").map(Number);
  for (let i = 0; i < 3; i += 1) {
    if (left[i] !== right[i]) return left[i] - right[i];
  }
  return 0;
}

function getJson(url, redirects = 0) {
  return new Promise((resolve, reject) => {
    if (redirects > 5) return reject(new ZetaError("Too many redirects asking GitHub for releases."));
    https
      .get(
        url,
        {
          headers: {
            "user-agent": `zeta-cli/${pkg.version}`,
            accept: "application/vnd.github+json",
            // Lifts the 60-per-hour anonymous limit where a token is around;
            // nothing here needs one, so its absence is not an error.
            ...(process.env.GITHUB_TOKEN ? { authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {}),
          },
        },
        (response) => {
          if ([301, 302, 307, 308].includes(response.statusCode)) {
            response.resume();
            return resolve(getJson(response.headers.location, redirects + 1));
          }
          if (response.statusCode !== 200) {
            response.resume();
            return reject(
              new ZetaError(`GitHub answered HTTP ${response.statusCode} for the release list.`, {
                hint:
                  response.statusCode === 403
                    ? "Anonymous API calls are rate limited. Set GITHUB_TOKEN, or pass --apk <path>."
                    : `Could not read ${url}`,
              }),
            );
          }
          let body = "";
          response.setEncoding("utf8");
          response.on("data", (chunk) => (body += chunk));
          response.on("end", () => {
            try {
              resolve(JSON.parse(body));
            } catch (error) {
              reject(new ZetaError(`GitHub returned something that is not JSON: ${error.message}`));
            }
          });
        },
      )
      .on("error", (error) => reject(new ZetaError(`Could not reach GitHub: ${error.message}`)));
  });
}
