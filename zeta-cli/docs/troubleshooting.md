# Troubleshooting

Start with `zeta doctor`. It checks every prerequisite and prints the command to
fix whatever is missing. Most of what follows is what it cannot know.

## Setup

**"No Java installation found"** — install a JDK 17+, or let the CLI fetch one
with `zeta doctor --install-jdk` (about 190 MB, once, into `~/.zetaforge`). If
you have a JDK the CLI does not see, point `ZETA_JAVA_HOME` at it. Android
Studio's bundled JBR is found automatically.

**"adb not found"** — install the Android SDK platform-tools and set
`ANDROID_HOME` to the SDK folder. On Windows that is usually
`%LOCALAPPDATA%\Android\Sdk`.

**"No Android platform found"** — you have the SDK but no platform to compile
against: `sdkmanager "platforms;android-35"`, or tick one in Android Studio's
SDK Manager.

## Devices

**"No device connected"** — with the cable plugged in, check `zeta devices`. A
device shown as `unauthorized` means the USB debugging prompt is waiting on the
phone's screen; `offline` usually clears with `adb kill-server`.

**"2 devices connected — pick one"** — add `--device <serial>`, or close the
emulator.

**"Could not hand the package to ZetaForge"** — the installed app is a release
build, which does not accept packages from the CLI. Install the developer build
with `zeta host install --force`.

**"A differently signed ZetaForge is already installed"** — Android will not
replace an app with one signed by a different key. Remove it first:
`adb uninstall com.zetaforge.app`. This deletes its plugins and their settings.

## Building

**"Unresolved reference"** — an ordinary Kotlin compile error; the CLI shows the
file and line. If it names something from a library you declared, check the
coordinate in `[dependencies]` and rebuild.

**"Dependency not found"** — the coordinate does not exist. It must be
`group:artifact:version`, and the artifact has to be on Maven Central or
Google's repository.

**"Could not download dependencies"** — the first build of a project needs the
network. Behind a proxy, set `HTTP_PROXY` and `HTTPS_PROXY`. Afterwards
`zeta build --offline` works from the cache.

**"The entry point … is not in the compiled code"** — `entryPoint` in the
descriptor must be the fully qualified name of a class that exists. The error
lists the classes it did find, and usually the right one is among them.

**"The package would bundle the Kotlin standard library"** — a dependency is
dragging it in. The CLI excludes `org.jetbrains.kotlin` from every declared
dependency by default, so this normally means a transitive artifact repackaged
Kotlin inside itself. `zeta inspect --classes` shows what actually landed in the
DEX. See [dependencies.md](dependencies.md).

## Running

**Nothing happens after `zeta run`** — look at the phone: a permission dialog is
probably waiting. The Host asks at START, every time.

**"Permission … is not declared by the Host"** — permissions are frozen into an
app when it is built, so a permission the Host does not declare can never be
granted to a plugin. See [permissions.md](permissions.md).

**"Package format version N is newer than the one supported by this Host"** —
the app is older than the CLI that built the package. Update it with
`zeta host install --force`.

**The plugin stalls when the screen goes off** — report progress with
`ZetaProgress.report(...)`. While a plugin reports progress the Host keeps a
foreground service alive, which is what stops Android from freezing the process.

**A long run stops after several hours** — on Android 15+ a data-sync foreground
service is capped at roughly six hours a day. The Host stops the run cleanly and
notifies; the plugin has to be resumable. See [testing.md](testing.md).

## Getting unstuck

`zeta build --verbose` prints the full Gradle output. `zeta logs` shows
everything the Host and the runtime say, including the class loader trace.

If the CLI itself crashes with an internal error, that is a bug — please open an
issue with the output: https://github.com/zetawave/ZetaForge/issues
