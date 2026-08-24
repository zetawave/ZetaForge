---
title: Testing
description: Three levels, cheapest first — JVM tests with no device, a real run on a device, and reading what you actually shipped.
---

# Testing

Three levels. Use the cheap ones for the logic, and the expensive one only for
what genuinely needs a device.

## 1. JVM tests — milliseconds, no device

`test/` is an ordinary Kotlin test source set, run by `zeta test`.

```kotlin title="test/BackupPlannerTest.kt"
class BackupPlannerTest {

    @Test
    fun `skips files already copied at the same size`() {
        val state = BackupState(mapOf("a.jpg" to 1024L))

        assertFalse(state.needsCopy("a.jpg", 1024L))
        assertTrue(state.needsCopy("a.jpg", 2048L))
    }

    @Test
    fun `a rename target never collides with an existing file`() {
        val taken = setOf("IMG_0001.jpg", "IMG_0001 (1).jpg")
        assertEquals("IMG_0001 (2).jpg", uniqueName("IMG_0001.jpg", taken))
    }
}
```

```bash
zeta test
zeta test --verbose
```

`kotlin-test` and `kotlinx-coroutines-test` are already on the test classpath.

### The trick that makes this possible

Keep the interesting logic **out** of `execute`, which needs a real `Context`,
and in plain functions and classes.

What to copy, what to skip, how to name the output, when to give up, how to
parse the response, what the next scheduled time is — none of that needs
Android, and all of it is where the bugs are.

```kotlin
// Testable: no Android anywhere.
internal fun plan(files: List<FileInfo>, done: Set<String>, limit: Int): List<FileInfo> =
    files.filterNot { it.id in done }.take(limit)

// Thin, and therefore not where mistakes hide.
override suspend fun execute(context: Context, input: Bundle): PluginResult {
    val work = plan(scan(context), ledger.read(), input.getInt("maxFiles", 500))
    ...
}
```

A screen benefits from the same split: its state machine is ordinary Kotlin, and
only the drawing needs Compose. See [Screens](screens.md#structuring-one).

## 2. On a device — the real thing

```bash
zeta dev            # build, install and run on every save
zeta run            # one execution, with the log
zeta logs           # follow the stream on its own
```

`zeta dev` is the loop you will spend most time in. It rebuilds on save,
reinstalls, runs, and prints the result and your log lines, without an IDE.

What genuinely needs a device: permissions, `MediaStore` queries, `MediaCodec`,
real file system behaviour, anything touching `ContentResolver`, and how your
plugin behaves when the screen goes off.

`zeta run` exits 0 on success and 1 on failure, so it composes:

```bash
zeta build && zeta install && zeta run --quiet || echo "the plugin failed"
```

### Trying a different configuration

`zeta run` executes with the settings saved on the device, so a different
configuration is a change in the plugin's settings form and then another run.

To exercise several configurations quickly, call the logic directly from a JVM
test instead — which is the argument for keeping it out of `execute` in the
first place:

```kotlin
@Test
fun `dry run copies nothing`() {
    assertEquals(0, plan(files, done = emptySet(), limit = 0).size)
}
```

## 3. Reading what you shipped

```bash
zeta inspect dist/weather-1.0.0.zeta
zeta inspect dist/weather-1.0.0.zeta --classes
```

```text
  weather 1.0.0                                        1.4 MB

  entry point   com.example.weather.WeatherPlugin      ok
  format        4          host api  4 … 4
  dex           classes.dex   1.4 MB   dex 039
  sha256        66c36da01bbaf87fc450401830b61d1ca744…
  settings      city, units
  permissions   INTERNET
  bundled       retrofit, converter-gson, okhttp
```

Worth doing before every release. It answers "did that dependency really get
bundled?" and "is the entry point what I think it is?" in about a second.

`--classes` lists every class in the DEX, which is usually a surprise the first
time and the fastest way to find out that a library brought half of Guava with
it.

## In CI

The JVM half needs nothing but Node and a JDK:

```yaml title=".github/workflows/plugin.yml"
name: plugin
on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 20
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - run: npm install -g zetaforge-cli@4
      - run: zeta test
      - run: zeta build
      - uses: actions/upload-artifact@v4
        with:
          name: plugin
          path: dist/*.zeta
```

Building needs `android.jar`. On a runner without the Android SDK, point the CLI
at one:

```bash
ANDROID_HOME=$HOME/android-sdk zeta build
```

`zeta doctor` prints exactly what it looked for and where, which is the quickest
way to fix a CI machine.

## What the project itself tests

If you are working on ZetaForge rather than on a plugin, see
[Building from source](building-from-source.md). The short version:

| Layer | Command |
|---|---|
| Manifest, package format, permission rules | `./gradlew :runtime:test` |
| The CLI | `npm test --prefix zeta-cli` |
| The whole chain on a device | `./gradlew :host:connectedDebugAndroidTest` |

## Next

[CLI reference →](cli-reference.md)
