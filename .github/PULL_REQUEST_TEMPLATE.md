## What this changes

<!-- One or two sentences. What is different after this is merged? -->

## Why

<!-- The problem it solves. Link the issue if there is one: Fixes #123 -->

## How to check it

<!-- What a reviewer should run or look at. -->

---

- [ ] One thing, not several
- [ ] Tested where testing is cheap (runtime parsing, CLI validation, engine logic)
- [ ] Documentation updated under `zetaforge-doc/content/` if this is user-visible
- [ ] `npm test` passes

**Compatibility** — tick anything this touches, and say what it means for
already published plugins:

- [ ] The plugin contract (`app/plugin-api/`)
- [ ] The `.zeta` package format
- [ ] The host permission superset
- [ ] The shared boundary (SDK / Kotlin / coroutines / Compose)
- [ ] None of the above

<!--
By opening this pull request you agree your contribution is licensed under the
licence covering the files you touched: Apache-2.0 for zeta-cli/ and
zetaforge-doc/, PolyForm Strict for app/. See CONTRIBUTING.md.
-->
