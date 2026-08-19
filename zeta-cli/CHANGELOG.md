# Changelog

All notable changes to the `zetaforge` CLI. The major version is the Host API
version it targets — see [docs/versioning.md](docs/versioning.md).

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
