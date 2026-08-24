/**
 * The Markdown the documentation is written in, and nothing more.
 *
 * This is not a CommonMark implementation and does not try to be. It supports
 * exactly the constructs these docs use — which is the whole argument for it
 * existing: the site has no dependencies at all, so it cannot break because
 * something three levels down published a new major, and anyone can read the
 * renderer in ten minutes.
 *
 * Supported: ATX headings, paragraphs, fenced code (with a language and an
 * optional title), ordered and unordered lists with nesting, tables,
 * blockquotes, `:::` callouts, horizontal rules, and inline code, emphasis,
 * links, images and autolinks.
 */
import { highlight, escapeHtml } from "./highlight.mjs";

const CALLOUTS = {
  note: { label: "Note", icon: "i" },
  tip: { label: "Tip", icon: "★" },
  warning: { label: "Warning", icon: "!" },
  danger: { label: "Careful", icon: "!" },
};

/**
 * Turns a heading into a URL fragment.
 *
 * Stable across builds and readable in an address bar, because these end up in
 * links people paste to each other.
 */
export function slugify(text) {
  return text
    .toLowerCase()
    .replace(/`/g, "")
    .replace(/[^\w\s-]/g, "")
    .trim()
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-");
}

/**
 * @param {string} source markdown
 * @param {{ resolveLink?: (href: string) => string }} options
 * @returns {{ html: string, headings: Array<{ level: number, text: string, id: string }> }}
 */
export function render(source, options = {}) {
  const resolveLink = options.resolveLink ?? ((href) => href);
  const lines = source.replace(/\r\n/g, "\n").split("\n");
  const headings = [];
  const out = [];
  const seen = new Map();

  let i = 0;

  /** Makes an id unique without making it ugly for the common case. */
  const uniqueId = (base) => {
    const count = seen.get(base) ?? 0;
    seen.set(base, count + 1);
    return count === 0 ? base : `${base}-${count}`;
  };

  const inline = (text) => renderInline(text, resolveLink);

  while (i < lines.length) {
    const line = lines[i];

    // --- blank ------------------------------------------------------------
    if (!line.trim()) {
      i++;
      continue;
    }

    // --- fenced code ------------------------------------------------------
    const fence = line.match(/^```(.*)$/);
    if (fence) {
      const info = fence[1].trim();
      const language = info.split(/\s+/)[0] ?? "";
      const titleMatch = info.match(/title="([^"]+)"/);
      const body = [];
      i++;
      while (i < lines.length && !/^```\s*$/.test(lines[i])) body.push(lines[i++]);
      i++; // the closing fence
      const code = body.join("\n");
      const title = titleMatch
        ? `<div class="code-title">${escapeHtml(titleMatch[1])}</div>`
        : "";
      out.push(
        `<div class="code-block" data-language="${escapeHtml(language || "text")}">${title}` +
          `<button class="code-copy" type="button" aria-label="Copy code">Copy</button>` +
          `<pre><code>${highlight(code, language)}</code></pre></div>`,
      );
      continue;
    }

    // --- cards ------------------------------------------------------------
    // A link grid, written as a list so the links still resolve like every
    // other link on the site — raw HTML would not get the deployment prefix.
    if (/^:::\s*cards\s*$/.test(line)) {
      const body = [];
      i++;
      while (i < lines.length && !/^:::\s*$/.test(lines[i])) body.push(lines[i++]);
      i++;
      const cards = body
        .map((item) => item.replace(/^\s*[-*+]\s+/, "").trim())
        .filter(Boolean)
        .map((item) => {
          const link = item.match(/^\[([^\]]+)\]\(([^)]+)\)\s*(?:[—-]\s*(.*))?$/);
          if (!link) return "";
          const [, label, href, blurb] = link;
          return (
            `<a class="card" href="${resolveLink(href)}">` +
            `<h3>${inline(label)} <span aria-hidden="true">&rarr;</span></h3>` +
            (blurb ? `<p>${inline(blurb)}</p>` : "") +
            `</a>`
          );
        })
        .join("");
      out.push(`<div class="cards">${cards}</div>`);
      continue;
    }

    // --- callout ----------------------------------------------------------
    const callout = line.match(/^:::\s*(\w+)\s*(.*)$/);
    if (callout && CALLOUTS[callout[1]]) {
      const kind = callout[1];
      const heading = callout[2].trim();
      const body = [];
      i++;
      while (i < lines.length && !/^:::\s*$/.test(lines[i])) body.push(lines[i++]);
      i++;
      const inner = render(body.join("\n"), options).html;
      const meta = CALLOUTS[kind];
      out.push(
        `<aside class="callout callout-${kind}">` +
          `<div class="callout-head"><span class="callout-icon" aria-hidden="true">${meta.icon}</span>` +
          `<span class="callout-label">${escapeHtml(heading || meta.label)}</span></div>` +
          `<div class="callout-body">${inner}</div></aside>`,
      );
      continue;
    }

    // --- heading ----------------------------------------------------------
    const heading = line.match(/^(#{1,6})\s+(.*)$/);
    if (heading) {
      const level = heading[1].length;
      const text = heading[2].trim();
      const id = uniqueId(slugify(text));
      if (level >= 2 && level <= 3) headings.push({ level, text: stripInline(text), id });
      const anchor =
        level === 1
          ? ""
          : `<a class="anchor" href="#${id}" aria-label="Link to this section">#</a>`;
      out.push(`<h${level} id="${id}">${inline(text)}${anchor}</h${level}>`);
      i++;
      continue;
    }

    // --- horizontal rule --------------------------------------------------
    if (/^(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      out.push("<hr>");
      i++;
      continue;
    }

    // --- table ------------------------------------------------------------
    if (line.trim().startsWith("|") && i + 1 < lines.length && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1])) {
      const header = splitRow(lines[i]);
      const alignments = splitRow(lines[i + 1]).map((cell) => {
        const left = cell.startsWith(":");
        const right = cell.endsWith(":");
        if (left && right) return "center";
        if (right) return "right";
        return "left";
      });
      i += 2;
      const rows = [];
      while (i < lines.length && lines[i].trim().startsWith("|")) rows.push(splitRow(lines[i++]));

      const head = header
        .map((cell, index) => `<th style="text-align:${alignments[index] ?? "left"}">${inline(cell)}</th>`)
        .join("");
      const body = rows
        .map(
          (row) =>
            "<tr>" +
            row
              .map(
                (cell, index) =>
                  `<td style="text-align:${alignments[index] ?? "left"}">${inline(cell)}</td>`,
              )
              .join("") +
            "</tr>",
        )
        .join("");
      out.push(`<div class="table-wrap"><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`);
      continue;
    }

    // --- blockquote -------------------------------------------------------
    if (/^>\s?/.test(line)) {
      const body = [];
      while (i < lines.length && /^>\s?/.test(lines[i])) body.push(lines[i++].replace(/^>\s?/, ""));
      out.push(`<blockquote>${render(body.join("\n"), options).html}</blockquote>`);
      continue;
    }

    // --- list -------------------------------------------------------------
    if (isListItem(line)) {
      const [html, next] = renderList(lines, i, options);
      out.push(html);
      i = next;
      continue;
    }

    // --- paragraph --------------------------------------------------------
    const paragraph = [];
    while (
      i < lines.length &&
      lines[i].trim() &&
      !isListItem(lines[i]) &&
      !/^(#{1,6}\s|```|>\s?|:::)/.test(lines[i]) &&
      !/^(-{3,}|\*{3,}|_{3,})\s*$/.test(lines[i]) &&
      !lines[i].trim().startsWith("|")
    ) {
      paragraph.push(lines[i++]);
    }
    if (paragraph.length) out.push(`<p>${inline(paragraph.join("\n"))}</p>`);
    else i++;
  }

  return { html: out.join("\n"), headings };
}

function splitRow(line) {
  return line
    .trim()
    .replace(/^\|/, "")
    .replace(/\|$/, "")
    .split("|")
    .map((cell) => cell.trim());
}

function isListItem(line) {
  return /^\s*([-*+]|\d+\.)\s+/.test(line);
}

function indentOf(line) {
  const match = line.match(/^(\s*)/);
  return match ? match[1].replace(/\t/g, "  ").length : 0;
}

/**
 * Lists, including nested ones.
 *
 * Nesting is decided by indentation relative to the first item of the list
 * being built, which is what people actually type, rather than by counting in
 * units of four.
 */
function renderList(lines, start, options) {
  const baseIndent = indentOf(lines[start]);
  const ordered = /^\s*\d+\./.test(lines[start]);
  const items = [];
  let i = start;

  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) {
      // A blank line ends the list unless the next line continues it.
      const next = lines[i + 1];
      if (!next || !next.trim() || indentOf(next) < baseIndent || !isListItem(next)) break;
      i++;
      continue;
    }
    if (!isListItem(line) || indentOf(line) < baseIndent) break;

    if (indentOf(line) > baseIndent) {
      const [nested, next] = renderList(lines, i, options);
      items[items.length - 1] += nested;
      i = next;
      continue;
    }

    const content = [line.replace(/^\s*([-*+]|\d+\.)\s+/, "")];
    i++;
    // Continuation lines: indented further, but not themselves list items.
    while (i < lines.length && lines[i].trim() && !isListItem(lines[i]) && indentOf(lines[i]) > baseIndent) {
      content.push(lines[i++].trim());
    }
    items.push(renderInline(content.join(" "), options.resolveLink ?? ((href) => href)));
  }

  const tag = ordered ? "ol" : "ul";
  return [`<${tag}>${items.map((item) => `<li>${item}</li>`).join("")}</${tag}>`, i];
}

/** Removes inline markup, for places that need plain text (TOC, search). */
export function stripInline(text) {
  return text
    .replace(/`([^`]+)`/g, "$1")
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/\*([^*]+)\*/g, "$1")
    .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
    .trim();
}

/**
 * Inline markup.
 *
 * Code spans are extracted first and put back last, so that a backticked
 * `**not bold**` stays literal — the single most common thing a naive
 * implementation gets wrong in technical writing.
 */
function renderInline(text, resolveLink) {
  const codeSpans = [];
  let working = text.replace(/`([^`]+)`/g, (_, code) => {
    codeSpans.push(code);
    return ` CODE${codeSpans.length - 1} `;
  });

  working = escapeHtml(working);

  working = working
    // images before links: the syntax differs by one character
    .replace(/!\[([^\]]*)\]\(([^)\s]+)(?:\s+"([^"]*)")?\)/g, (_, alt, src, title) => {
      const titleAttr = title ? ` title="${title}"` : "";
      return `<img src="${resolveLink(src)}" alt="${alt}"${titleAttr} loading="lazy">`;
    })
    .replace(/\[([^\]]+)\]\(([^)\s]+)(?:\s+"([^"]*)")?\)/g, (_, label, href, title) => {
      const resolved = resolveLink(href);
      const external = /^https?:\/\//.test(resolved);
      const attrs = external ? ' target="_blank" rel="noopener noreferrer"' : "";
      const titleAttr = title ? ` title="${title}"` : "";
      return `<a href="${resolved}"${attrs}${titleAttr}>${label}</a>`;
    })
    .replace(/&lt;(https?:\/\/[^\s&]+)&gt;/g, '<a href="$1" target="_blank" rel="noopener noreferrer">$1</a>')
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/(^|[\s(])\*([^*\n]+)\*/g, "$1<em>$2</em>")
    .replace(/(^|[\s(])_([^_\n]+)_/g, "$1<em>$2</em>");

  return working.replace(/ CODE(\d+) /g, (_, index) => `<code>${escapeHtml(codeSpans[Number(index)])}</code>`);
}
