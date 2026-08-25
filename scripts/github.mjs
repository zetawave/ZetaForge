/**
 * Creating a GitHub release, with or without the GitHub CLI.
 *
 * `gh` is the pleasant path: it is already authenticated, so nothing here has
 * to handle a token. But requiring it means a release cannot happen on a
 * machine that does not have it - a fresh laptop, a CI runner - so the same
 * work is also implemented against the REST API, which needs nothing but Node
 * and a token in the environment.
 *
 * Whichever is available is used, and the caller does not care which.
 */
import fs from "node:fs";
import path from "node:path";
import { fail, git, info, run, spawnTool } from "./release-lib.mjs";

const API = "https://api.github.com";

/** `owner/name`, read from the origin remote rather than hardcoded. */
export function repoSlug() {
  const url = git(["remote", "get-url", "origin"]).trim();
  const match = url.match(/github\.com[:/](?<owner>[^/]+)\/(?<name>[^/]+?)(?:\.git)?$/);
  if (!match) fail(`Cannot tell the GitHub repository from the origin remote: ${url}`);
  return `${match.groups.owner}/${match.groups.name}`;
}

export function hasGh() {
  return spawnTool("gh", ["--version"], { capture: true }).status === 0;
}

function token() {
  return process.env.GH_TOKEN || process.env.GITHUB_TOKEN || null;
}

/**
 * How this process will talk to GitHub, or why it cannot.
 *
 * Checked before anything is built or tagged: discovering there is no way to
 * upload the result *after* pushing the tag is exactly the failure the release
 * scripts are written to avoid.
 */
export function requireGitHubAccess() {
  if (hasGh()) {
    if (spawnTool("gh", ["auth", "status"], { capture: true }).status !== 0) {
      fail("gh is installed but not authenticated.", "Run: gh auth login");
    }
    return "gh";
  }
  if (token()) return "api";
  fail(
    "No way to reach GitHub.",
    "Either install the GitHub CLI and log in:\n" +
      "        winget install GitHub.cli    (or: choco install gh)\n" +
      "        gh auth login\n" +
      "    Or export a token with `repo` scope, from\n" +
      "    https://github.com/settings/tokens :\n" +
      '        $env:GH_TOKEN = "ghp_..."     (PowerShell)\n' +
      '        export GH_TOKEN=ghp_...       (bash)',
  );
}

export async function releaseExists(tag) {
  if (hasGh()) return spawnTool("gh", ["release", "view", tag], { capture: true }).status === 0;
  const response = await api(`/repos/${repoSlug()}/releases/tags/${encodeURIComponent(tag)}`);
  return response.status === 200;
}

/** Creates the release and attaches every file. */
export async function createRelease({ tag, name, notes, files }) {
  if (hasGh()) {
    run("gh", ["release", "create", tag, ...files, "--title", name, "--notes", notes]);
    return `https://github.com/${repoSlug()}/releases/tag/${tag}`;
  }
  return createReleaseViaApi({ tag, name, notes, files });
}

async function createReleaseViaApi({ tag, name, notes, files }) {
  const slug = repoSlug();
  info("gh is not installed; using the REST API");

  const created = await api(`/repos/${slug}/releases`, {
    method: "POST",
    body: JSON.stringify({ tag_name: tag, name, body: notes, draft: false, prerelease: false }),
  });
  if (created.status !== 201) {
    fail(`GitHub refused to create the release (HTTP ${created.status}).`, describe(created));
  }

  // The template suffix is GitHub's, not a query string we may keep.
  const uploadBase = created.json.upload_url.replace(/\{.*$/, "");

  for (const file of files) {
    const bytes = fs.readFileSync(file);
    const uploaded = await api(`${uploadBase}?name=${encodeURIComponent(path.basename(file))}`, {
      method: "POST",
      body: bytes,
      contentType: "application/octet-stream",
      absolute: true,
    });
    if (uploaded.status !== 201) {
      fail(`Could not upload ${path.basename(file)} (HTTP ${uploaded.status}).`, describe(uploaded));
    }
    info(`uploaded  ${path.basename(file)}`);
  }

  return created.json.html_url;
}

function describe(response) {
  const message = response.json?.message || response.text?.slice(0, 300) || "";
  if (response.status === 401 || response.status === 403) {
    return `${message}\n    The token needs the \`repo\` scope (or Contents: write on a fine-grained token).`;
  }
  return message;
}

async function api(pathOrUrl, options = {}) {
  const auth = token();
  if (!auth) fail("No GitHub token.", "Set GH_TOKEN, or install the GitHub CLI and run: gh auth login");

  const response = await fetch(options.absolute ? pathOrUrl : `${API}${pathOrUrl}`, {
    method: options.method || "GET",
    headers: {
      accept: "application/vnd.github+json",
      authorization: `Bearer ${auth}`,
      "user-agent": "zetaforge-release",
      "x-github-api-version": "2022-11-28",
      ...(options.body ? { "content-type": options.contentType || "application/json" } : {}),
    },
    body: options.body,
  });

  const text = await response.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    // Not every response is JSON; the status is what matters.
  }
  return { status: response.status, json, text };
}
