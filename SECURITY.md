# Security policy

## Reporting a vulnerability

**Please do not open a public issue.**

Report privately through GitHub's
[security advisory form](https://github.com/zetawave/ZetaForge/security/advisories/new),
which is the preferred route because it keeps the discussion attached to the
repository and allows a fix to be prepared before disclosure.

Please include:

- what the issue is, and which component it affects (host, runtime, CLI, docs);
- the versions involved — `zeta --version` and `zeta host version`;
- steps to reproduce, or a proof of concept;
- what an attacker gains.

**What to expect:** an acknowledgement within a few days, an assessment of
severity and a plan, and credit in the release notes unless you prefer
otherwise. This is a small project, not a company with a response team; the
timelines are best effort and honest about it.

## Scope — read this before reporting

ZetaForge loads and runs arbitrary code by design. Several things that look like
vulnerabilities are the documented, intended behaviour, and reporting them is
not useful.

### Not vulnerabilities

**A plugin can do anything the host can do.** Plugin code runs in the host
process with the host's UID and permissions. It can read the host's private
files, use its network access and call any Android API the host may call. This
is the [trust boundary](https://zetawave.github.io/ZetaForge/security/), stated
throughout the documentation and in the app itself.

**Plugins are not sandboxed from each other.** Separate class loaders isolate
*classes*, not files. Everything runs under one UID.

**Packages are unsigned.** `manifest.signature` is always `null`, and the
verifier reports it as a warning on every import. Trust in a `.zeta` comes from
its provenance. Signing is a known gap with a seam already in place, not an
oversight.

**The host declares a broad permission set.** It is the ceiling for what any
plugin may request, so it is necessarily wide. A user grants only what they
choose to grant.

**A plugin screen can open a dialog over the window.** Android provides no way
to prevent it. The plugin cannot alter the identity bar the host draws above its
content, and that is the guarantee that is made.

**`secret = true` settings are not encrypted at rest.** Documented as masking in
the form, nothing more.

### In scope, and worth reporting

- A way for a plugin to be loaded or executed **without the user importing it**
  — for example, through an exported component, an intent, or a file path the
  host reads automatically.
- A bypass of the **import verification**: a package that fails validation but
  is installed anyway, or a checksum that is not actually checked.
- A way to make the host **execute code from outside app-private storage**.
- A way for a plugin to obtain a **permission the host does not declare**, or to
  escape the permission gate entirely.
- A way to make the host **run a plugin the user uninstalled**, or to leave code
  behind after uninstall.
- A plugin screen that can **alter or convincingly replace the host's own UI
  chrome** — the identity bar, permission dialogs, or the app's other screens.
- **Path traversal** during package extraction: a `.zeta` writing outside its
  own directory.
- Anything in the **CLI** that executes attacker-controlled input on the
  developer's machine — a malicious `zetaplugin.toml`, or a crafted `.zeta`
  passed to `zeta inspect`.
- Anything in the **documentation site** build that would inject content into
  published pages.

## Supported versions

The latest released major line receives fixes. Given the versioning scheme —
the CLI's major version is the Host API version — that means the current Host
API and the CLI major matching it.

| Version | Supported |
|---|---|
| 4.x | yes |
| < 4 | no |

## For people installing plugins

The most effective protection available to you is not in the code:

- **know where the file came from** — there is no signature to check;
- **read the source**, with `VIEW CODE`; it ships inside every package;
- **read the permissions** on the card before pressing START;
- **uninstall removes everything** — the plugin, its settings, its schedule.

See the [security model](https://zetawave.github.io/ZetaForge/security/) for the
full picture, including what the runtime does and does not protect against.
