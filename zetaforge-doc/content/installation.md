---
title: Installation
description: Install the zeta CLI, check your machine with zeta doctor, and put the ZetaForge app on a device.
---

# Installation

There are two halves to install: the **CLI** on your computer, which builds
plugins, and the **app** on an Android device, which runs them.

## Requirements

| | |
|---|---|
| **Node.js** | 18.17 or newer — the CLI is a Node program |
| **A JDK** | 17 or newer. `zeta doctor --install-jdk` fetches one if you have none |
| **Android SDK** | for `android.jar` and `adb`. Android Studio installs both |
| **A device** | Android 8.0 (API 26) or newer, or an emulator |

You do **not** need Android Studio, Gradle, or the Android Gradle Plugin
installed by hand. The CLI generates and drives everything a plugin build needs.

## Install the CLI

```bash
npm install -g zetaforge-cli
```

The command it installs is `zeta`:

```bash
zeta --version
```

::: note The package name and the command differ
The npm package is `zetaforge-cli`; the command is `zeta`. The plain `zetaforge`
name on npm belongs to an unrelated project.
:::

If you would rather not install anything globally, every command works through
`npx`:

```bash
npx zetaforge-cli doctor
```

### Pin a version

The **major version of the CLI is the Host API version it builds for**. This is
the one versioning rule worth internalising, and [Versioning](versioning.md)
explains why.

```bash
npm install -g zetaforge-cli@4    # builds plugins for Host API 4
```

## Check the machine

```bash
zeta doctor
```

`doctor` is not a formality — it is the fastest path out of most setup problems.
It reports, in order: Node, the JDK it will use, the Android SDK and
`android.jar`, `adb`, connected devices, whether the ZetaForge app is installed
on them, and whether the contract jar shipped with the CLI is intact.

Anything it cannot find, it tells you how to get:

```bash
zeta doctor --install-jdk      # download a JDK into the zeta cache
zeta doctor --install-host     # install the app on the connected device
```

## Install the app

The app is not on Google Play. It loads arbitrary code, which is precisely the
thing app stores exist to prevent, so it is distributed as an APK.

```bash
zeta host install
```

That downloads the APK matching your CLI version from the GitHub release and
installs it over adb. To check what is on the device:

```bash
zeta host version
```

### By hand

Take a `host-v*` release from the
[releases page](https://github.com/zetawave/ZetaForge/releases). Each one
publishes several APKs:

| File | When to take it |
|---|---|
| `zetaforge-host-<v>-universal.apk` | anything. Take this one if unsure. |
| `zetaforge-host-<v>-<abi>.apk` | one architecture only, a few tens of KB smaller |
| `zetaforge-host-<v>-debug.apk` | building plugins: `zeta install` needs a debuggable app |

```bash
adb install -r zetaforge-host-4.0.0-universal.apk
```

Or copy it to the phone and open it — Android will ask you to allow installs
from that source, which is expected.

::: warning Android will warn you
Installing an APK from outside a store produces a warning, and the app then
asks for permissions that look broad, because they are the ceiling for what any
plugin may use. Both are honest signals. See [the security model](security.md)
before you dismiss them.
:::

## Set up a device

Enable **Developer options** and **USB debugging**:

1. Settings → About phone → tap *Build number* seven times
2. Settings → System → Developer options → **USB debugging**
3. Connect over USB and accept the fingerprint prompt

Then:

```bash
zeta devices
```

An emulator works just as well and needs no cable. Anything from API 26 up will
do; API 34+ is what development happens on.

### Two settings that matter for scheduled plugins

If your plugin runs on a schedule rather than on a button press, Android's
battery management will interfere. The app has a readiness panel that checks
this and takes you to the right screen — but it is worth knowing why:

* **Battery optimisation** — while the app is "optimised", Android may defer an
  alarm by hours and cut its network access.
* **Notifications** — a background run with no notification is invisible, and
  Android is more willing to kill a process nobody can see.

[Scheduling](scheduling.md) covers this in full.

## Updating

```bash
npm install -g zetaforge-cli@latest
zeta host install --force
```

Keep the two in step. A plugin built by a CLI newer than the app declares a
`minHostApi` the app does not implement, and the app will refuse it with a
message saying exactly that.

## Next

[Quick start →](quick-start.md)
