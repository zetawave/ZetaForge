---
title: Logging and progress
description: Report what your plugin is doing — to the log console, the notification, and the terminal.
---

# Logging and progress

## Logging

```kotlin
ZetaLog.debug(id, "Weather", "raw = $body")
ZetaLog.info(id, "Weather", "HTTP 200 in 412 ms")
ZetaLog.warn(id, "Weather", "Retrying (2/5)")
ZetaLog.error(id, "Weather", "Gave up after 5 attempts", exception)
```

The three arguments are the plugin id, a short source label of your choosing,
and the message. The label groups related lines — the class or subsystem is a
good choice.

Every line appears in three places at once: the app's log console, `zeta logs`,
and the output of `zeta run`. Nothing extra is needed to see them.

```text
16:33:03.155 INFO   Weather   HTTP 200 in 412 ms
16:33:03.160 WARN   Weather   Retrying (2/5)
```

### Levels, and what to use them for

| Level | For |
|---|---|
| `debug` | Detail useful while developing. Filtered out by default in the app. |
| `info` | The narrative of a run: what it decided, what it did. |
| `warn` | Something recoverable that the user might care about later. |
| `error` | The run is failing, or a part of it has. |

::: warning Logs are not private
The log console is visible in the app and over adb. Never log tokens, passwords,
file contents, or anything from a `secret` setting. The temptation is strongest
exactly when you are debugging authentication.
:::

### What to log

The useful test is whether a line would help you understand a run you were not
watching, a week later, from a screenshot someone sent you.

Good: *"Found 214 photos, 12 already compressed, skipping"*. That explains a
result.

Less good: *"entering loop"*. That explains nothing and buries the line above.

One or two lines per phase is usually right. A line per file is a line per file
times four thousand.

## Progress

```kotlin
ZetaProgress.report(
    pluginId = id,
    current = copied,
    total = files.size.toLong(),
    message = "12.4 GB copied",
)
```

Or, when the total is not known yet:

```kotlin
ZetaProgress.status(id, "Scanning the library…")
```

`total = null` gives an indeterminate indicator rather than a bar, which is
honest and better than a bar that jumps.

### Why it is more than cosmetic

The host keeps a foreground service alive for the whole of a run, and that
service needs something to show. A plugin that reports progress gets a real
progress bar in the notification — and, more importantly, keeps running with the
screen off, because Android never freezes a process with a foreground component.

Reporting is optional: a plugin that never calls it still runs, and simply shows
an indeterminate notification.

### How often

Often enough to look alive, rarely enough not to be the work. Once per item is
fine for hundreds of items; for tens of thousands, throttle:

```kotlin
if (index % 25 == 0 || index == files.lastIndex) {
    ZetaProgress.report(id, index.toLong(), files.size.toLong(), files[index].name)
}
```

### Write the message for a person

The notification line is read at a glance, from a lock screen. Something like
`4 812/21 076 files · 12.4 GB` says how far along, how much is left and how big
the job is, in one line and no jargon.

## The log console

In the app, the console sits under the plugin list and can be expanded to fill
the screen. It filters by level, searches, and can be cleared.

It is the first place to look when something behaves differently on the device
than it did in a test — particularly for the runtime's own lines, which explain
what was loaded, which class loader was used, which permissions were evaluated
and why a run was blocked:

```text
Class loader: DELEGATE_LAST over code.jar (23918 bytes)
Entry point instantiated: com.example.weather.WeatherPlugin
Plugin loaded
START
Running without optional permission ACCESS_MEDIA_LOCATION
SUCCESS - 18° and clear in Rome (412 ms)
```

## From the terminal

```bash
zeta logs                 # follow everything
zeta run                  # one run, with its log and result
zeta run --quiet          # only the final result, for scripts
```

`zeta run` exits 0 on success and 1 on failure:

```bash
zeta build && zeta install && zeta run --quiet || echo "the plugin failed"
```

## Next

[Testing →](testing.md)
