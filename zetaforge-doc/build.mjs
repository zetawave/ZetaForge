#!/usr/bin/env node
/**
 * Builds the ZetaForge documentation into a folder of static HTML.
 *
 * No dependencies, on purpose. A documentation site that cannot be built five
 * years from now is not documentation, and the surest way to get there is a
 * tree of 900 packages that has to resolve before anyone can fix a typo. What
 * this produces is plain files: any static host serves them, GitHub Pages
 * included, and `python -m http.server` is a perfectly good preview.
 *
 *   node build.mjs                     -> dist/, for GitHub Pages
 *   node build.mjs --base /            -> dist/, for a root domain or local preview
 *   node build.mjs --serve             -> build, then serve on :4173 and watch
 */
import fs from "node:fs";
import path from "node:path";
import http from "node:http";
import { fileURLToPath } from "node:url";
import { render, slugify, stripInline } from "./lib/markdown.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const contentDir = path.join(here, "content");
const themeDir = path.join(here, "theme");
const outDir = path.join(here, "dist");

const args = process.argv.slice(2);
const flag = (name) => args.includes(`--${name}`);
const value = (name, fallback) => {
  const index = args.indexOf(`--${name}`);
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
};

/**
 * Where the site will live. A GitHub project page is served from a
 * subdirectory, so every internal link has to carry that prefix — getting this
 * wrong is the single most common way a Pages deploy ends up with a working
 * home page and broken everything else.
 */
const BASE = normaliseBase(value("base", process.env.DOCS_BASE ?? "/ZetaForge/"));

const SITE = {
  name: "ZetaForge",
  tagline: "An Android plugin runtime. Real Kotlin, loaded at run time.",
  description:
    "ZetaForge loads real Kotlin plugins into a running Android app: their own " +
    "libraries, their own permissions, their own screens — compiled outside the " +
    "app and shipped as a single .zeta file.",
  repo: "https://github.com/zetawave/ZetaForge",
  editBase: "https://github.com/zetawave/ZetaForge/edit/main/zetaforge-doc/content/",
  url: "https://zetawave.github.io/ZetaForge/",
};

function normaliseBase(input) {
  let base = input;

  // Git Bash and MSYS rewrite a lone "/" argument into their install directory,
  // so `node build.mjs --base /` there silently produces a site whose every
  // asset points at C:/Program Files/Git/. Caught rather than tolerated: the
  // failure is otherwise a site that builds cleanly and renders unstyled.
  if (/^[A-Za-z]:[\\/]/.test(base) || base.includes("Program Files")) {
    console.warn(`  ! --base "${base}" looks like a shell-mangled path; using "/"`);
    base = "/";
  }

  if (!base.startsWith("/")) base = `/${base}`;
  if (!base.endsWith("/")) base += "/";
  return base;
}

/** A site-root-relative URL, with the deployment prefix applied exactly once. */
function url(target = "") {
  return BASE + String(target).replace(/^\//, "");
}

// ---------------------------------------------------------------------------
// Content
// ---------------------------------------------------------------------------

const nav = JSON.parse(fs.readFileSync(path.join(contentDir, "nav.json"), "utf8"));

/** Every page, flattened in reading order, for prev/next links. */
const pages = nav.flatMap((section) =>
  section.items.map((item) => ({ ...item, section: section.title })),
);

const bySlug = new Map(pages.map((page) => [page.slug, page]));

/**
 * Front matter, kept deliberately tiny: a title and a one-line description.
 * Everything else a page needs to say, it says in prose.
 */
function readPage(slug) {
  const file = path.join(contentDir, `${slug}.md`);
  if (!fs.existsSync(file)) throw new Error(`nav.json lists "${slug}" but ${file} does not exist`);
  // Normalised on read: a CRLF checkout would otherwise fail to match the
  // front matter, and the whole block would be rendered into the page as prose.
  const raw = fs.readFileSync(file, "utf8").replace(/\r\n/g, "\n");
  const match = raw.match(/^---\n([\s\S]*?)\n---\n?/);
  const meta = {};
  let body = raw;
  if (match) {
    for (const line of match[1].split("\n")) {
      const separator = line.indexOf(":");
      if (separator > 0) {
        meta[line.slice(0, separator).trim()] = line
          .slice(separator + 1)
          .trim()
          .replace(/^["']|["']$/g, "");
      }
    }
    body = raw.slice(match[0].length);
  }
  return { meta, body };
}

/**
 * Rewrites links written the way an author thinks of them.
 *
 * In the source a cross-reference is `[settings](settings.md)`, which is a
 * working link on GitHub too. Here it becomes a clean site URL.
 */
function resolveLink(href) {
  if (/^(https?:|mailto:|#|\/\/)/.test(href)) return href;
  const [target, fragment] = href.split("#");
  if (!target) return `#${fragment}`;
  if (target.endsWith(".md")) {
    const slug = target.replace(/\.md$/, "").replace(/^\.\//, "");
    return url(`${slug}/`) + (fragment ? `#${fragment}` : "");
  }
  return url(target.replace(/^\.\//, ""));
}

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

function sidebar(activeSlug) {
  return nav
    .map((section) => {
      const items = section.items
        .map((item) => {
          const active = item.slug === activeSlug ? ' class="active" aria-current="page"' : "";
          return `<li><a href="${url(`${item.slug}/`)}"${active}>${escape(item.title)}</a></li>`;
        })
        .join("");
      return `<div class="nav-section"><h3>${escape(section.title)}</h3><ul>${items}</ul></div>`;
    })
    .join("");
}

function tableOfContents(headings) {
  if (headings.length < 2) return "";
  const items = headings
    .map(
      (heading) =>
        `<li class="toc-h${heading.level}"><a href="#${heading.id}">${escape(heading.text)}</a></li>`,
    )
    .join("");
  return `<nav class="toc" aria-label="On this page"><h4>On this page</h4><ul>${items}</ul></nav>`;
}

function pager(index) {
  const previous = pages[index - 1];
  const next = pages[index + 1];
  if (!previous && !next) return "";
  const left = previous
    ? `<a class="pager-link pager-prev" href="${url(`${previous.slug}/`)}">
         <span class="pager-label">Previous</span>
         <span class="pager-title">${escape(previous.title)}</span></a>`
    : "<span></span>";
  const right = next
    ? `<a class="pager-link pager-next" href="${url(`${next.slug}/`)}">
         <span class="pager-label">Next</span>
         <span class="pager-title">${escape(next.title)}</span></a>`
    : "<span></span>";
  return `<nav class="pager">${left}${right}</nav>`;
}

function escape(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function layout({ page, index, html, headings }) {
  const title = index === 0 ? `${SITE.name} — ${SITE.tagline}` : `${page.title} · ${SITE.name}`;
  const description = page.description || SITE.description;
  const canonical = SITE.url + (page.slug === "introduction" ? "" : `${page.slug}/`);

  return `<!doctype html>
<html lang="en" data-theme="dark">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title)}</title>
<meta name="description" content="${escape(description)}">
<link rel="canonical" href="${escape(canonical)}">
<meta property="og:type" content="website">
<meta property="og:title" content="${escape(title)}">
<meta property="og:description" content="${escape(description)}">
<meta property="og:url" content="${escape(canonical)}">
<meta name="theme-color" content="#0B1020">
<link rel="icon" href="${url("assets/favicon.svg")}" type="image/svg+xml">
<link rel="stylesheet" href="${url("assets/styles.css")}">
<script>
  // Applied before the first paint, so a reader who chose light does not get a
  // dark flash on every navigation.
  try {
    var stored = localStorage.getItem("zf-theme");
    if (stored) document.documentElement.dataset.theme = stored;
  } catch (e) {}
  window.DOCS_BASE = ${JSON.stringify(BASE)};
</script>
</head>
<body>
<a class="skip-link" href="#content">Skip to content</a>

<header class="topbar">
  <button class="menu-toggle" aria-label="Open navigation" aria-expanded="false">
    <span></span><span></span><span></span>
  </button>
  <a class="brand" href="${url("introduction/")}">
    <img src="${url("assets/logo.svg")}" alt="" width="28" height="28">
    <span class="brand-name">${SITE.name}</span>
    <span class="brand-badge">docs</span>
  </a>
  <div class="search">
    <input type="search" id="search-input" placeholder="Search the docs" autocomplete="off"
           aria-label="Search the documentation" spellcheck="false">
    <kbd class="search-hint">/</kbd>
    <div id="search-results" class="search-results" hidden></div>
  </div>
  <nav class="topbar-links">
    <a href="${url("cli-reference/")}">CLI</a>
    <a href="${url("architecture/")}">Architecture</a>
    <a href="${SITE.repo}" target="_blank" rel="noopener noreferrer" aria-label="GitHub repository">GitHub</a>
  </nav>
  <button class="theme-toggle" aria-label="Switch between light and dark">
    <svg class="icon-sun" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><circle cx="12" cy="12" r="4.2"/><g class="rays"><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.6 4.6l2.1 2.1M17.3 17.3l2.1 2.1M19.4 4.6l-2.1 2.1M6.7 17.3l-2.1 2.1"/></g></svg>
    <svg class="icon-moon" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z"/></svg>
  </button>
</header>

<div class="shell">
  <aside class="sidebar" id="sidebar">
    <nav aria-label="Documentation">${sidebar(page.slug)}</nav>
  </aside>
  <div class="sidebar-backdrop" hidden></div>

  <main id="content">
    <article class="prose">
      <div class="breadcrumb">${escape(page.section)}</div>
      ${html}
      <footer class="page-footer">
        <a class="edit-link" href="${SITE.editBase}${page.slug}.md" target="_blank" rel="noopener noreferrer">
          Edit this page on GitHub
        </a>
      </footer>
      ${pager(index)}
    </article>
  </main>

  ${tableOfContents(headings)}
</div>

<script src="${url("assets/docs.js")}" defer></script>
</body>
</html>
`;
}

// ---------------------------------------------------------------------------
// Build
// ---------------------------------------------------------------------------

function build() {
  const started = Date.now();
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.mkdirSync(path.join(outDir, "assets"), { recursive: true });

  const searchIndex = [];

  pages.forEach((page, index) => {
    const { meta, body } = readPage(page.slug);
    const { html, headings } = render(body, { resolveLink });
    const merged = { ...page, ...meta };

    const dir = path.join(outDir, page.slug);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(
      path.join(dir, "index.html"),
      layout({ page: merged, index, html, headings }),
    );

    searchIndex.push({
      slug: page.slug,
      title: merged.title,
      section: page.section,
      description: merged.description ?? "",
      headings: headings.map((heading) => ({ text: heading.text, id: heading.id })),
      // Enough text to match on, not enough to make the index heavy.
      text: stripInline(body.replace(/```[\s\S]*?```/g, " ").replace(/[#>|*_\-]/g, " "))
        .replace(/\s+/g, " ")
        .slice(0, 1800),
    });
  });

  // The home page is the introduction, served from the site root as well, so
  // both / and /introduction/ work and neither is a redirect.
  const first = pages[0];
  const { meta, body } = readPage(first.slug);
  const { html, headings } = render(body, { resolveLink });
  fs.writeFileSync(
    path.join(outDir, "index.html"),
    layout({ page: { ...first, ...meta }, index: 0, html, headings }),
  );

  // A project page has no server-side routing: an unknown path must still land
  // somewhere useful rather than on GitHub's own 404.
  fs.writeFileSync(path.join(outDir, "404.html"), notFoundPage());

  for (const file of fs.readdirSync(themeDir)) {
    fs.copyFileSync(path.join(themeDir, file), path.join(outDir, "assets", file));
  }

  fs.writeFileSync(path.join(outDir, "assets", "search-index.json"), JSON.stringify(searchIndex));
  // Without this, Pages runs the output through Jekyll, which drops any file or
  // folder whose name starts with an underscore.
  fs.writeFileSync(path.join(outDir, ".nojekyll"), "");
  fs.writeFileSync(path.join(outDir, "sitemap.xml"), sitemap());
  fs.writeFileSync(
    path.join(outDir, "robots.txt"),
    `User-agent: *\nAllow: /\nSitemap: ${SITE.url}sitemap.xml\n`,
  );

  const bytes = directorySize(outDir);
  console.log(
    `  built ${pages.length} pages in ${Date.now() - started} ms  ` +
      `(${(bytes / 1024).toFixed(0)} KB, base ${BASE})`,
  );
}

function notFoundPage() {
  return `<!doctype html>
<html lang="en" data-theme="dark">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Not found · ${SITE.name}</title>
<link rel="stylesheet" href="${url("assets/styles.css")}">
<link rel="icon" href="${url("assets/favicon.svg")}" type="image/svg+xml">
</head>
<body class="centred">
<main class="notfound">
  <p class="notfound-code">404</p>
  <h1>That page moved, or never existed.</h1>
  <p>The documentation was reorganised more than once before its first release.</p>
  <p><a class="button" href="${url("introduction/")}">Start from the introduction</a></p>
</main>
</body>
</html>
`;
}

function sitemap() {
  const entries = pages
    .map(
      (page) =>
        `  <url><loc>${SITE.url}${page.slug}/</loc><changefreq>weekly</changefreq></url>`,
    )
    .join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
  <url><loc>${SITE.url}</loc><changefreq>weekly</changefreq><priority>1.0</priority></url>
${entries}
</urlset>
`;
}

function directorySize(dir) {
  let total = 0;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    total += entry.isDirectory() ? directorySize(full) : fs.statSync(full).size;
  }
  return total;
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".xml": "application/xml",
  ".txt": "text/plain; charset=utf-8",
  ".png": "image/png",
};

function serve(port = 4173) {
  const server = http.createServer((request, response) => {
    let pathname = decodeURIComponent(new URL(request.url, "http://localhost").pathname);
    if (BASE !== "/" && pathname.startsWith(BASE)) pathname = pathname.slice(BASE.length - 1);

    let file = path.join(outDir, pathname);
    if (file.endsWith("/") || !path.extname(file)) file = path.join(file, "index.html");

    // Nothing outside dist/ is ever served, however the path was written.
    if (!file.startsWith(outDir)) {
      response.writeHead(403).end("Forbidden");
      return;
    }
    if (!fs.existsSync(file)) {
      response.writeHead(404, { "content-type": MIME[".html"] });
      response.end(fs.readFileSync(path.join(outDir, "404.html")));
      return;
    }
    response.writeHead(200, {
      "content-type": MIME[path.extname(file)] ?? "application/octet-stream",
      "cache-control": "no-cache",
    });
    response.end(fs.readFileSync(file));
  });

  server.listen(port, () => {
    console.log(`  preview  http://localhost:${port}${BASE}`);
    console.log("  watching content/ and theme/ — Ctrl+C to stop\n");
  });

  let rebuilding = null;
  for (const dir of [contentDir, themeDir, path.join(here, "lib")]) {
    fs.watch(dir, { recursive: true }, () => {
      clearTimeout(rebuilding);
      rebuilding = setTimeout(() => {
        try {
          build();
        } catch (error) {
          console.error(`  ✗ ${error.message}`);
        }
      }, 80);
    });
  }
}

try {
  build();
  if (flag("serve")) serve(Number(value("port", 4173)));
} catch (error) {
  console.error(`\n  ✗ ${error.message}\n`);
  process.exit(1);
}
