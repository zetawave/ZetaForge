# CLI reference

Every command takes `--help`. Run `zeta` with no arguments for the list.

## zeta doctor

Checks that this machine can build and test plugins, and prints the exact
command to fix anything missing.

```
--install-jdk     download a Temurin JDK into ~/.zetaforge if none is usable
--install-host    install the ZetaForge app on the connected device
```

Run it first, and again whenever something behaves strangely.

## zeta new &lt;name&gt;

Scaffolds a project into `./<name>/`.

```
--template <name>   basic (default) or network
--id <id>           plugin id (default: com.example.<name>)
--author <name>
--list-templates
```

The name must be lowercase letters, digits and dashes. `weather-sync` becomes
the class `WeatherSyncPlugin` and the id `com.example.weathersync`.

## zeta build

Compiles, dexes, verifies and packages into `dist/`.

```
--verbose      show the full build output
--offline      do not touch the network
--out <dir>    write the package somewhere else
```

Before writing the package it parses the DEX and checks that the entry point
really exists and that no shared class was bundled. Both failures are reported
with the line to change.

## zeta dev

The loop. Builds, installs and runs; then watches `src/` and `zetaplugin.toml`
and does it again on every save.

```
--device <serial>   which device
--no-run            build and install, do not execute
--once              one pass, then exit
```

A failed build does not end the session: the error is printed and the watcher
keeps going.

## zeta test

Runs the JVM tests in `test/`. No device, no emulator, no Android. See
[testing.md](testing.md).

## zeta install [file.zeta]

Builds if needed, then hands the package to the Host and waits for it to be
accepted.

```
--device <serial>
--no-build      import what is already in dist/
```

## zeta run

Executes the plugin on the device and prints its log and result, then exits with
0 on success and 1 on failure — so it can be used in a script.

```
--device <serial>
--timeout <seconds>   default 120
--quiet               only the final result
```

## zeta logs

Follows the ZetaForge log stream, formatted. Ctrl+C to stop.

## zeta inspect &lt;file.zeta&gt;

Shows what is inside a package: identity, permissions, settings, bundled
dependencies, DEX size and class count.

```
--classes     also list every class in the DEX
```

Works on any `.zeta`, including one you did not build — a good way to see what a
plugin someone sent you actually contains, before importing it.

## zeta devices

Lists what adb can see, and whether each device is ready.

## zeta host &lt;install|version|uninstall&gt;

Manages the ZetaForge app itself.

```
--force           reinstall even if present
--apk <path>      install a local APK instead of downloading
--device <serial>
```

`zeta host install` downloads the app matching this CLI's version and caches it
in `~/.zetaforge/`.

## zeta clean

Deletes `.zeta/` and `dist/`.

## Environment variables

| | |
|---|---|
| `ZETA_HOME` | cache location (default `~/.zetaforge`) |
| `ZETA_JAVA_HOME` | JDK to use, before `JAVA_HOME` |
| `ZETA_ANDROID_HOME` | Android SDK, before `ANDROID_HOME` |
| `ZETA_HOST_APK_URL` | where to download the Host from |
| `ZETA_REPO` | GitHub repository used for releases |

## Exit codes

`0` success, `1` failure. Every failure prints one line saying what went wrong
and, underneath it, what to do about it.
