---
title: CLI reference
description: Every zeta command, its options, and what it actually does.
---

# CLI reference

```bash
zeta <command> [options]
zeta help <command>
```

The package is `zetaforge-cli`; the command is `zeta`.

| Command | |
|---|---|
| [`new`](#zeta-new) | Create a new plugin project |
| [`build`](#zeta-build) | Compile the plugin and package it as a `.zeta` |
| [`dev`](#zeta-dev) | Rebuild, install and run on every save |
| [`install`](#zeta-install) | Build if needed, then import into the host |
| [`run`](#zeta-run) | Execute on the device and print the result |
| [`logs`](#zeta-logs) | Follow the ZetaForge log stream |
| [`test`](#zeta-test) | Run the JVM tests, without a device |
| [`inspect`](#zeta-inspect) | Show what is inside a `.zeta` |
| [`devices`](#zeta-devices) | List the devices adb can see |
| [`host`](#zeta-host) | Manage the ZetaForge app on the device |
| [`doctor`](#zeta-doctor) | Check that this machine can build and test |
| [`clean`](#zeta-clean) | Delete generated build state |

Every command that talks to a device accepts `--device <serial>`. Without it,
the CLI uses the only connected device, or asks when there is more than one.

## zeta new

```bash
zeta new <name> [options]
```

Creates a project directory containing a descriptor, sources and tests.

| Option | |
|---|---|
| `--template <name>` | `basic` (default), `network`, `background` |
| `--id <id>` | Plugin id, e.g. `com.example.myplugin` |
| `--author <name>` | Author name |
| `--list-templates` | Show what is available |

```bash
zeta new weather --template network --id com.example.weather --author "Jane Doe"
```

**basic** — a plugin with two settings, doing nothing that needs the network.
**network** — Retrofit, a real HTTP call, error handling.
**background** — long work, progress reporting, cooperative cancellation.

## zeta build

```bash
zeta build [options]
```

Compiles `src/` and packages the result as `dist/<name>-<version>.zeta`.

| Option | |
|---|---|
| `--verbose` | Show the full build output |
| `--offline` | Do not touch the network; dependencies must be cached |
| `--out <dir>` | Where to write the package (default `dist/`) |

What it does, in order: generates a Gradle build into `.zeta/` from your
descriptor, resolves dependencies, runs the Kotlin compiler, runs D8 over the
result, checks the entry point really exists in the produced DEX, verifies that
no boundary class was bundled, writes `manifest.json` and seals the archive.

Nothing in `.zeta/` is yours and it can be deleted at any time.

## zeta dev

```bash
zeta dev [options]
```

Watches `src/` and, on every save, rebuilds, reinstalls and runs — printing the
result and the plugin's log lines.

| Option | |
|---|---|
| `--device <serial>` | Which device to use |
| `--no-run` | Build and install, but do not execute |
| `--once` | Do one pass and exit |

## zeta install

```bash
zeta install [file.zeta] [options]
```

Builds if needed, then imports the package into the host app on the device.

| Option | |
|---|---|
| `--device <serial>` | Which device to use |
| `--no-build` | Import the existing package without rebuilding |

With a file argument it installs that package instead of the current project's,
which is how you try someone else's plugin.

## zeta run

```bash
zeta run [options]
```

Executes the plugin on the device and prints the structured result.

| Option | |
|---|---|
| `--device <serial>` | Which device to use |
| `--timeout <seconds>` | How long to wait for the result (default 120) |
| `--quiet` | Only print the final result |

Exits 0 on success, 1 on failure — so it can gate a script.

## zeta logs

```bash
zeta logs [--device <serial>]
```

Follows the host's log stream: the runtime's own lines and everything your
plugin writes with `ZetaLog`. Ctrl+C to stop.

## zeta test

```bash
zeta test [--verbose]
```

Runs the tests in `test/` on the JVM. No device, no emulator, no Android.
See [Testing](testing.md).

## zeta inspect

```bash
zeta inspect <file.zeta> [--classes]
```

Reads a package and prints what is inside it: entry point, format and API
versions, DEX size and version, SHA-256, declared settings and permissions, and
bundled dependencies. `--classes` also lists every class in the DEX.

Works on any `.zeta`, including one you did not build.

## zeta devices

```bash
zeta devices
```

Lists what adb can see, with the Android version and whether the ZetaForge app
is installed on each.

## zeta host

```bash
zeta host <install|version|uninstall> [options]
```

| Option | |
|---|---|
| `--device <serial>` | Which device to use |
| `--force` | Reinstall even if it is already there |
| `--apk <path>` | Install a locally built APK instead of downloading |

`install` downloads the APK matching your CLI version from the GitHub release
and installs it over adb. `--apk` is how you install a host you built yourself.

## zeta doctor

```bash
zeta doctor [--install-jdk] [--install-host]
```

Checks Node, the JDK, the Android SDK and `android.jar`, `adb`, connected
devices, whether the app is installed, and the integrity of the contract jar
shipped with the CLI. Prints exactly what it looked for and where.

This is the first thing to run when anything behaves oddly.

## zeta clean

```bash
zeta clean
```

Deletes `.zeta/` and `dist/`. Your sources and descriptor are untouched.

## Environment variables

| | |
|---|---|
| `ZETA_HOME` | Where downloads and toolchains are cached (default `~/.zetaforge`) |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | Where to find `android.jar` and `adb` |
| `JAVA_HOME` | Which JDK to use |
| `ZETA_REPO` | The GitHub repository releases are fetched from |
| `ZETA_HOST_APK_URL` | A specific APK URL, overriding the release lookup |

## Exit codes

| | |
|---|---|
| `0` | Success |
| `1` | The command failed — a build error, a failed run, a missing device |

Errors are written as a sentence, a hint, and where relevant a link to the page
of these docs that covers it.

## Next

[SDK reference →](sdk-reference.md)
