/**
 * Syntax highlighting, small enough to read in one sitting.
 *
 * The whole documentation site has no dependencies, and this is the piece that
 * usually drags one in. A real highlighter parses the language; this one
 * tokenises the four things that actually carry meaning when you skim a code
 * block — comments, strings, keywords and numbers — and leaves everything else
 * alone. On a page of Kotlin it is indistinguishable from the heavyweight
 * option, and it costs 4 KB instead of 400.
 *
 * Tokenising in one pass with a single alternation matters: highlighting
 * keywords first and strings second would colour the word `fun` inside
 * "a fun example". Whichever pattern matches earliest in the source wins, which
 * is what a lexer would do anyway.
 */

const KEYWORDS = {
  kotlin: `abstract actual annotation as break by catch class companion const constructor continue crossinline
    data delegate do dynamic else enum expect external false final finally for fun get if import in infix init
    inline inner interface internal is lateinit noinline null object open operator out override package private
    protected public reified return sealed set super suspend tailrec this throw true try typealias typeof val var
    vararg when where while it`,
  java: `abstract assert boolean break byte case catch char class const continue default do double else enum
    extends final finally float for if implements import instanceof int interface long native new package private
    protected public return short static super switch synchronized this throw throws transient try void volatile
    while true false null var record sealed`,
  javascript: `async await break case catch class const continue debugger default delete do else export extends
    false finally for from function if import in instanceof let new null of return static super switch this throw
    true try typeof undefined var void while with yield`,
  bash: `if then else elif fi for while do done case esac function return in export local readonly source echo cd
    exit set unset trap shift eval exec test`,
  gradle: `plugins dependencies android implementation compileOnly testImplementation api val var fun by set get
    listOf mapOf true false null id alias project platform files exclude group`,
  toml: `true false`,
  json: `true false null`,
  xml: "",
  text: "",
};

/** Aliases, so a fence can say what a human would say. */
const ALIASES = {
  kt: "kotlin",
  kts: "gradle",
  "gradle.kts": "gradle",
  js: "javascript",
  mjs: "javascript",
  sh: "bash",
  shell: "bash",
  console: "bash",
  yml: "yaml",
  html: "xml",
  properties: "toml",
  ini: "toml",
  "": "text",
};

function escapeHtml(value) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

/**
 * One alternation per language family, ordered so the greediest context wins.
 * Capture groups are deliberately avoided inside: the token type is decided by
 * which named branch matched, tested cheaply afterwards.
 */
function patternFor(language) {
  const parts = [];

  if (language === "bash") {
    parts.push("#[^\\n]*");
  } else if (language === "toml") {
    parts.push("#[^\\n]*");
  } else if (language === "xml") {
    parts.push("<!--[\\s\\S]*?-->");
  } else if (language !== "json" && language !== "text") {
    parts.push("//[^\\n]*", "/\\*[\\s\\S]*?\\*/");
  }

  // Strings: triple-quoted first, then the ordinary two.
  parts.push('"""[\\s\\S]*?"""', '"(?:\\\\.|[^"\\\\\\n])*"', "'(?:\\\\.|[^'\\\\\\n])*'");

  if (language === "xml") {
    parts.push("</?[A-Za-z][\\w:.-]*", "/?>");
  }

  // Annotations and decorators read as their own thing in Kotlin and Java.
  if (language === "kotlin" || language === "java" || language === "gradle") {
    parts.push("@[A-Za-z_]\\w*");
  }

  parts.push("\\b\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFdDlL]?\\b");
  parts.push("\\b[A-Za-z_$][\\w$]*\\b");

  return new RegExp(parts.join("|"), "g");
}

const CACHE = new Map();

function tokenPattern(language) {
  if (!CACHE.has(language)) CACHE.set(language, patternFor(language));
  const pattern = CACHE.get(language);
  pattern.lastIndex = 0;
  return pattern;
}

function keywordSet(language) {
  const words = KEYWORDS[language] ?? "";
  return new Set(words.split(/\s+/).filter(Boolean));
}

const KEYWORD_CACHE = new Map();

/**
 * Highlights one block of code and returns HTML.
 *
 * Unknown languages fall through to plain escaped text rather than guessing:
 * a wrongly coloured block is worse than an uncoloured one.
 */
export function highlight(code, rawLanguage = "") {
  const requested = rawLanguage.trim().toLowerCase();
  const language = ALIASES[requested] ?? requested;

  if (!(language in KEYWORDS)) return escapeHtml(code);
  if (language === "text") return escapeHtml(code);

  if (!KEYWORD_CACHE.has(language)) KEYWORD_CACHE.set(language, keywordSet(language));
  const keywords = KEYWORD_CACHE.get(language);

  const pattern = tokenPattern(language);
  let out = "";
  let last = 0;
  let match;

  while ((match = pattern.exec(code)) !== null) {
    const token = match[0];
    out += escapeHtml(code.slice(last, match.index));
    last = match.index + token.length;
    out += classify(token, language, keywords, code, match.index);
  }
  out += escapeHtml(code.slice(last));
  return out;
}

function classify(token, language, keywords, source, index) {
  const escaped = escapeHtml(token);
  const first = token[0];

  if (first === "#" || token.startsWith("//") || token.startsWith("/*") || token.startsWith("<!--")) {
    return `<span class="tok-comment">${escaped}</span>`;
  }
  if (first === '"' || first === "'") {
    return `<span class="tok-string">${escaped}</span>`;
  }
  if (first === "@") {
    return `<span class="tok-annotation">${escaped}</span>`;
  }
  if (first === "<" || token === ">" || token === "/>") {
    return `<span class="tok-tag">${escaped}</span>`;
  }
  if (/^\d/.test(token)) {
    return `<span class="tok-number">${escaped}</span>`;
  }
  if (keywords.has(token)) {
    return `<span class="tok-keyword">${escaped}</span>`;
  }

  // A word immediately followed by "(" is being called; one starting with a
  // capital is a type. Neither is certain, and both are right often enough to
  // be worth the colour.
  const after = source.slice(index + token.length);
  if (/^\s*\(/.test(after) && !keywords.has(token)) {
    return `<span class="tok-function">${escaped}</span>`;
  }
  if (/^[A-Z]/.test(token)) {
    return `<span class="tok-type">${escaped}</span>`;
  }
  return escaped;
}

export { escapeHtml };
