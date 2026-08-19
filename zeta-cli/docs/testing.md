# Testing

Three levels, cheapest first. Use the cheap ones for the logic and the expensive
one only for what genuinely needs a device.

## 1. JVM tests — milliseconds, no device

`test/` is an ordinary Kotlin test source set, run by `zeta test`.

```kotlin
class BackupPlannerTest {
    @Test
    fun `skips files already copied at the same size`() {
        val state = BackupState(mapOf("a.jpg" to 1024L))
        assertFalse(state.needsCopy("a.jpg", 1024L))
        assertTrue(state.needsCopy("a.jpg", 2048L))
    }
}
```

The trick is to keep the interesting logic **out** of `execute` — which needs a
real `Context` — and in plain functions and classes. What to copy, what to skip,
how to name the output, when to give up: none of it needs Android, and all of it
is where the bugs are.

`kotlin-test` and `kotlinx-coroutines-test` are already on the test classpath.

## 2. On a device — the real thing

```bash
zeta dev            # build, install, run, on every save
zeta run            # one execution, with the log
zeta logs           # follow the stream
```

`zeta run` exits 0 on success and 1 on failure, so it works in a script:

```bash
zeta build && zeta install && zeta run --quiet || echo "the plugin failed"
```

## 3. Reading what you shipped

```bash
zeta inspect dist/weather-1.0.0.zeta
zeta inspect dist/weather-1.0.0.zeta --classes
```

Worth doing before every release. It answers "did that dependency really get
bundled?" and "is the entry point what I think it is?" in one second.

## Logging

```kotlin
ZetaLog.info(id, "Weather", "HTTP 200 in 412 ms")
ZetaLog.warn(id, "Weather", "Retrying (2/5)")
ZetaLog.debug(id, "Weather", "raw = $body")
```

The lines appear in the app's log console and in `zeta logs` and `zeta run`. Use
the plugin's own tag as the second argument — that is what the CLI prints in the
left column.

## Progress

```kotlin
ZetaProgress.report(id, current = copied, total = totalBytes, message = "12/340 files")
```

Beyond the progress bar, this matters for a practical reason: while a plugin
reports progress the Host keeps a **foreground service** alive, which is what
stops Android from freezing the process when the screen goes off. A long
transfer without it stalls until the phone is woken.

## Work that must survive interruption

Anything running for minutes should be resumable, and the patterns are the same
every time:

* keep progress in `context.filesDir/<your-plugin>/state.json`, written to a
  temporary file and renamed over the old one, so a crash mid-write cannot
  destroy it;
* save every N items, not at the end;
* key entries by something that changes when the item changes — path plus size —
  so a modified file is processed again and an unmodified one never is;
* when the work is destructive, write the result beside the original and
  **rename over it**: on one filesystem the rename is atomic, so the path always
  holds either the intact original or the finished result.

## Debugging a plugin that will not load

Almost always one of three things, and `zeta build` catches the first two:

1. `entryPoint` does not name a class in the DEX;
2. a shared class (contract, stdlib, coroutines) was bundled;
3. the Host is older than `minHostApi`.

If it loads but fails at run time, `zeta logs` shows the runtime's own trace —
class loader, instantiation, permission gate, START — which usually names the
step that broke.
