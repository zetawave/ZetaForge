#!/usr/bin/env node
/**
 * Fails if the built site contains an internal link that goes nowhere.
 *
 * Broken links are the failure mode documentation is most prone to and least
 * likely to notice: a page is renamed, six other pages still point at the old
 * slug, and nobody finds out until a reader does. This runs in CI before the
 * deploy, so the site that ships is internally consistent.
 *
 * External links are not checked — a network call per link would make the build
 * slow and flaky, and would fail on somebody else's outage.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const dist = path.join(here, "..", "dist");

if (!fs.existsSync(dist)) {
  console.error("  ✗ dist/ does not exist — run the build first");
  process.exit(1);
}

/** Every HTML file in the output, with its path relative to the site root. */
function pages(dir = dist, prefix = "") {
  const found = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) found.push(...pages(full, `${prefix}${entry.name}/`));
    else if (entry.name.endsWith(".html")) found.push({ file: full, route: prefix });
  }
  return found;
}

const html = pages();

/** What actually exists, as the set of URLs a browser could request. */
const existing = new Set();
for (const { route } of html) {
  existing.add(`/${route}`);
}
function assetsIn(dir = dist, prefix = "") {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) assetsIn(path.join(dir, entry.name), `${prefix}${entry.name}/`);
    else existing.add(`/${prefix}${entry.name}`);
  }
}
assetsIn();

// The base the site was built with, recovered from any absolute link in it.
const sample = fs.readFileSync(html[0].file, "utf8");
const baseMatch = sample.match(/href="(\/[^"]*?)assets\/styles\.css"/);
const base = baseMatch ? baseMatch[1] : "/";

const problems = [];

for (const { file } of html) {
  const source = fs.readFileSync(file, "utf8");
  const relative = path.relative(dist, file).replace(/\\/g, "/");

  // Anchors declared on this page, for checking same-page fragments.
  const ids = new Set([...source.matchAll(/\sid="([^"]+)"/g)].map((match) => match[1]));

  for (const match of source.matchAll(/href="([^"]+)"/g)) {
    const href = match[1];

    if (/^(https?:|mailto:|#)/.test(href)) {
      // A same-page fragment still has to point at something.
      if (href.startsWith("#") && href !== "#content" && !ids.has(href.slice(1))) {
        problems.push(`${relative}: fragment ${href} has no matching id`);
      }
      continue;
    }
    if (!href.startsWith("/")) {
      problems.push(`${relative}: relative link "${href}" — links must be site-absolute`);
      continue;
    }

    const [target, fragment] = href.split("#");
    const withoutBase = `/${target.slice(base.length)}`;

    if (!existing.has(withoutBase) && !existing.has(`${withoutBase}index.html`)) {
      problems.push(`${relative}: ${href} does not exist`);
      continue;
    }

    if (fragment) {
      const targetFile = path.join(dist, withoutBase.replace(/^\//, ""), "index.html");
      if (fs.existsSync(targetFile)) {
        const targetSource = fs.readFileSync(targetFile, "utf8");
        if (!targetSource.includes(`id="${fragment}"`)) {
          problems.push(`${relative}: ${href} — the page exists, the anchor does not`);
        }
      }
    }
  }
}

if (problems.length) {
  console.error(`\n  ✗ ${problems.length} broken link(s)\n`);
  for (const problem of problems) console.error(`    ${problem}`);
  console.error("");
  process.exit(1);
}

console.log(`  ✓ ${html.length} pages, every internal link resolves`);
