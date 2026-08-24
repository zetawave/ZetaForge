# ZetaForge documentation

The site published at **<https://zetawave.github.io/ZetaForge/>**.

Static HTML with **no dependencies**. The whole build is a few hundred lines of
Node in `build.mjs` and `lib/`, so it needs no `npm install`, cannot break
because something three levels down published a new major, and will still build
in five years.

## Working on it

```bash
cd zetaforge-doc
npm run dev
```

Builds the site, serves it at <http://localhost:4173/>, and rebuilds on every
save to `content/`, `theme/` or `lib/`. Refresh the browser to see a change.

```bash
npm run build          # build into dist/ for GitHub Pages (base /ZetaForge/)
npm run build:local    # build with base /, to serve dist/ directly
npm run clean
```

## Layout

```text
zetaforge-doc/
├── build.mjs          markdown -> static HTML, the layout, the dev server
├── lib/
│   ├── markdown.mjs   the renderer
│   └── highlight.mjs  syntax highlighting
├── content/
│   ├── nav.json       the sidebar: sections and their pages, in order
│   └── *.md           one file per page
├── theme/
│   ├── styles.css     the whole design, dark and light
│   ├── docs.js        theme toggle, search, TOC, copy buttons
│   ├── logo.svg
│   └── favicon.svg
└── dist/              build output (git-ignored)
```

## Writing a page

1. Add `content/<slug>.md`.
2. Add it to `content/nav.json`, in the section and position it belongs.

```markdown
---
title: The page title
description: One sentence. Used for search results and social previews.
---

# The page title

Prose.
```

Cross-references are written as `[settings](settings.md)`, so they work both on
the site and when reading the files on GitHub. The build rewrites them to clean
URLs with the deployment prefix.

## Markdown supported

Headings, paragraphs, lists (nested), tables, blockquotes, horizontal rules,
inline code, bold, italic, links, images, autolinks — plus two additions.

**Fenced code** takes a language and an optional title:

````markdown
```kotlin title="WeatherPlugin.kt"
class WeatherPlugin : ZetaPlugin { … }
```
````

Highlighted languages: `kotlin`, `java`, `javascript`, `bash`, `gradle`/`kts`,
`toml`/`properties`, `json`, `xml`/`html`. Anything else renders as plain text,
which is deliberate — a wrongly coloured block is worse than an uncoloured one.

**Callouts**:

```markdown
::: note
Something worth knowing.
:::

::: tip / ::: warning / ::: danger
```

**Card grids**, for landing sections:

```markdown
::: cards
- [Install](installation.md) — Get the CLI and the app onto your machine.
- [Quick start](quick-start.md) — A plugin running in five minutes.
:::
```

## Deployment

`.github/workflows/docs.yml` builds this folder and publishes it to GitHub Pages
on every push to `main` that touches it. Nothing is committed to the repository:
`dist/` is generated in CI.

To point the site somewhere else, change `SITE` at the top of `build.mjs` and
pass `--base`.

## Conventions

**Write for someone deciding, not someone already convinced.** Pages should say
what a thing does *and* what it does not.

**Show the real output.** Log lines, error messages and command output are
copied from actual runs rather than invented.

**One idea per section**, with a heading that says what it is. The right-hand
table of contents is generated from `##` and `###`, so headings are navigation.

**Link forward.** Every page ends with the next one.
