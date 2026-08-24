# Changelog

All notable changes to the `zetaforge-cli` package. The major version is the Host API
version it targets — see [docs/versioning.md](docs/versioning.md).

## 4.0.0

**Host API 4.** Plugins can now be a *screen*, and the package is published to
npm as `zetaforge-cli` rather than `zetaforge`.

**Added**

- **Screens.** A plugin can implement `com.zetaforge.sdk.ui.ZetaUiPlugin` and
  return a `@Composable`, which the Host draws inside a container Activity of
  its own. Declared with a `[ui]` block in `zetaplugin.toml`; `only = true`
  marks a plugin that is nothing but its screen.
- Compose is provided by the Host and compiled against as `compileOnly`, exactly
  like the contract and the Kotlin runtime. The generated build wires it up when
  a `[ui]` block is present.
- A separate screen-contract version (`ui.uiApi`, `ZetaSdk.UI_API_VERSION`),
  counted apart from the Host API because it moves with Compose. A Host
  implementing an older one refuses to open the screen and says so, instead of
  failing mid-frame.
- Package format 4: the `ui` block. Older packages keep parsing unchanged.
- The documentation site at <https://zetawave.github.io/ZetaForge/>.

**Changed**

- **The npm package is now `zetaforge-cli`.** The plain `zetaforge` name belongs
  to an unrelated project. The command is still `zeta`.
- `zeta build` verifies the boundary by reading the DEX's *class definitions*
  rather than its string table, and refuses to package a plugin that compiles in
  the SDK, Kotlin, coroutines or Compose. The error names the offending classes.
- `zeta build` reports whether the package declares a screen.
- Repository URLs now point at `zetawave/ZetaForge`, which is where the releases
  actually are — `zeta host install` could not find them before.

**Notes for plugin authors**

Nothing is required of an existing plugin. A package built for Host API 1–3 runs
unchanged on this Host; `maxHostApi` below 4 is a note, not a warning.

## 3.0.0

The first published release: the CLI, the toolchain and the documentation.

**Added**

- `zeta new` with the `basic` and `network` templates
- `zeta build`: Kotlin/JVM → d8 → verified `.zeta`, without the Android Gradle
  Plugin. Android `.aar` dependencies are unwrapped automatically.
- `zeta dev`: rebuild, install and run on every save
- `zeta test`: JVM tests with no device involved
- `zeta install`, `zeta run`, `zeta logs`, `zeta devices`
- `zeta inspect`: identity, permissions, settings, dependencies and DEX contents
  of any package
- `zeta host install`: fetches and installs the ZetaForge app
- `zeta doctor`: checks Java, the Android SDK, adb, a device and the app, and
  can download a JDK
- `zetaplugin.toml` as the whole project configuration — no Gradle file to
  maintain
- the plugin contract ships inside the package, so nothing has to be resolved
  from a Maven repository

**Verification built into the build**

- the DEX is parsed and the entry point must be defined in it
- packaging is refused if the contract, the Kotlin standard library or
  coroutines would be bundled, since those must be shared with the Host
