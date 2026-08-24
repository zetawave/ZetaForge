---
title: Troubleshooting
description: The errors you are most likely to hit, what causes them, and what to do about each.
---

# Troubleshooting

Start with:

```bash
zeta doctor
```

It checks every prerequisite and prints the command to fix whatever is missing.
Most of what follows is what it cannot know.

## Setup

**"No Java installation found"**
Install a JDK 17+, or let the CLI fetch one with `zeta doctor --install-jdk`
(about 190 MB, once, into `~/.zetaforge`). If you have a JDK the CLI does not
see, point `JAVA_HOME` at it. Android Studio's bundled JBR is found
automatically.

**"adb not found"**
Install the Android SDK platform-tools and set `ANDROID_HOME` to the SDK folder.
On Windows that is usually `%LOCALAPPDATA%\Android\Sdk`.

**"No Android platform found"**
You have the SDK but no platform to compile against:

```bash
sdkmanager "platforms;android-35"
```

or tick one in Android Studio's SDK Manager.

## Devices

**"No device connected"**
With the cable plugged in, check `zeta devices`. A device shown as
`unauthorized` means the USB debugging prompt is waiting on the phone's screen.
`offline` usually clears with `adb kill-server`.

**"2 devices connected — pick one"**
Add `--device <serial>`, or close the emulator.

**"Could not hand the package to ZetaForge"**
The installed app is a release build, which does not accept packages from the
CLI. Install the developer build with `zeta host install --force`.

**"A differently signed ZetaForge is already installed"**
Android will not replace an app with one signed by a different key. Remove it
first:

```bash
adb uninstall com.zetaforge.app
```

::: warning
That deletes every installed plugin, its settings and its schedule.
:::

## Building

**"Unresolved reference"**
An ordinary Kotlin compile error; the CLI shows the file and line. If it names
something from a library you declared, check the coordinate in `[dependencies]`
and rebuild.

**"Dependency not found"**
The coordinate does not exist. It must be `group:artifact:version`, and the
artifact has to be on Maven Central or Google's repository.

**"Could not download dependencies"**
The first build of a project needs the network. Behind a proxy, set `HTTP_PROXY`
and `HTTPS_PROXY`. Afterwards `zeta build --offline` works from the cache.

**"The entry point … is not in the compiled code"**
`entryPoint` in the descriptor must be the fully qualified name of a class that
exists. The error lists the classes it did find, and usually the right one is
among them.

**"The package would bundle the Kotlin standard library"**
A dependency is dragging it in. The CLI excludes `org.jetbrains.kotlin` from
every declared dependency by default, so this normally means a transitive
artifact repackaged Kotlin inside itself.

```bash
zeta inspect dist/x.zeta --classes
```

shows what actually landed in the DEX. See [Dependencies](dependencies.md).

**"This plugin bundles N class(es) the Host must own"**
The same problem, for the whole boundary: the SDK, Kotlin, coroutines or
Compose. Whatever the message names must be `compileOnly`. If you added Compose
to `[dependencies]` for a [screen](screens.md), remove it — the host provides
it. See [class loading](class-loading.md#the-shared-contract-rule).

## Running

**Nothing happens after `zeta run`**
Look at the phone: a permission dialog is probably waiting. The host asks at
START, every time.

**"Permission … is not declared by the Host"**
Permissions are frozen into an app when it is built, so a permission the host
does not declare can never be granted to a plugin. No amount of asking helps;
the host has to be rebuilt with it added. See [Permissions](permissions.md).

**"Package format version N is newer than the one supported by this Host"**
The app is older than the CLI that built the package:

```bash
zeta host install --force
```

**"Plugin incompatible with this Host: requires API [5..5], Host implements 4"**
Same cause, seen through the contract rather than the format. Update the app.

**The plugin stalls when the screen goes off**
Report progress with `ZetaProgress.report(...)`. While a plugin reports
progress, the host keeps a foreground service alive, which is what stops Android
from freezing the process. See
[Logging and progress](logging-and-progress.md#why-it-is-more-than-cosmetic).

**A long run stops after several hours**
On Android 15+ a data-sync foreground service is capped at roughly six hours a
day. The host stops the run cleanly and notifies; the plugin has to be
resumable. See [Lifecycle](lifecycle.md#being-resumable).

**A scheduled plugin never runs, or runs hours late**
Almost always battery optimisation. The app's readiness panel checks it and
takes you to the exact switch. See [Scheduling](scheduling.md#the-two-settings-that-decide-whether-any-of-this-works).

## Screens

**The screen shows "This screen cannot be opened"**
The message names the reason. `UI_API_TOO_NEW` means the app is older than the
screen contract the package was built against — update the app.
`UI_NOT_IMPLEMENTED` means the descriptor declares `[ui]` but the entry point
does not implement `ZetaUiPlugin`.

**`ClassCastException: androidx.compose.runtime.Composer cannot be cast to
androidx.compose.runtime.Composer`**
The plugin bundled its own Compose. It must be `compileOnly`. The build check
catches this, so seeing it means the package was built some other way.

**The screen closed and showed an error report**
Your plugin threw. The report carries the exception and the top of its stack;
`zeta logs` has the whole thing. The host is unaffected and the plugin was
unloaded — opening it again starts from a fresh instance.

**Everything is lost after the app is killed in the background**
Expected. A screen cannot save its own types in instance state. See
[Screens](screens.md#what-a-screen-cannot-keep).

## Getting unstuck

```bash
zeta build --verbose      # the full Gradle output
zeta logs                 # everything the host and the runtime say
zeta inspect dist/x.zeta --classes
```

`zeta logs` includes the runtime's own narrative — which class loader was used,
which entry point was instantiated, which permissions were evaluated and why a
run was blocked. It is usually faster than guessing.

If the CLI itself crashes with an internal error, that is a bug. Please
[open an issue](https://github.com/zetawave/ZetaForge/issues) with the output.

## Next

[FAQ →](faq.md)
