---
title: Scheduling
description: Turn a plugin into a service — nightly, on Wi-Fi, while charging — and understand what Android does to background work.
---

# Scheduling

A plugin that only runs when someone presses a button is a tool. One that runs
on its own is a service, and that is what scheduling turns ZetaForge into.

Nothing is required of the plugin. Scheduling is attached by the *user*, from
the card, to any plugin that has a useful `execute`. Your code is identical
either way.

## What the user sets

**SCHEDULE** on a plugin card opens a panel:

| | |
|---|---|
| **How often** | once at a date and time; every N (15 minutes … 1 day); every day; or chosen weekdays |
| **Time** | a system time picker, honouring the device's 12/24-hour setting |
| **Conditions** | only while charging · only on Wi-Fi · only above 20% battery |
| **Exact time** | wake the device to the minute — opt-in, because it costs battery and, from Android 12, a permission |

At the bottom sits a preview: next run, last run, how it went — computed by the
same function the alarm uses, so what the user is promised and what the system
will do cannot diverge.

## How a run happens

```text
AlarmManager  ──▶  ScheduleReceiver  ──▶  PluginExecutionService  ──▶  runtime.execute
                        │                          │
                        │                          └── notification: running, then the result
                        └── conditions unmet? notify and re-arm instead
```

One alarm per plugin, always for the *next* run only. A repeating alarm cannot
express "every Tuesday and Friday, unless it already ran", and it survives an
edit badly; after each run the next alarm is set from the same function that
draws the preview.

Schedules live beside the plugin, in its own directory, so uninstalling a plugin
takes its schedule with it and nothing can point at a plugin that is gone.

The receiver re-arms everything on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` —
Android drops alarms on both — and tells the user about runs that were due while
the device was off.

## Writing a plugin worth scheduling

The code is the same. The *assumptions* are not.

**Assume nobody is watching.** No dialog can be shown, no question can be asked.
Anything that needs a decision has to be a setting, decided in advance.

**Assume it will be interrupted.** Keep a ledger of what is already done, under
`context.filesDir`, and skip it next time. Write to a temporary file and rename;
a rename is atomic, a half-written file that replaced a good one is not
recoverable.

```kotlin
override suspend fun execute(context: Context, input: Bundle): PluginResult {
    val ledger = Ledger(context.filesDir.resolve("done.txt"))
    val pending = findWork().filterNot { ledger.contains(it.id) }

    var completed = 0
    for (item in pending) {
        coroutineContext.ensureActive()          // the user can still press Stop
        val temporary = File(target, "${item.name}.part")
        transfer(item, temporary)
        temporary.renameTo(File(target, item.name))
        ledger.add(item.id)
        completed++
        ZetaProgress.report(id, completed, pending.size, item.name)
    }

    return PluginResult.Success("Transferred $completed of ${pending.size}")
}
```

**Keep each run short.** "Every 15 minutes" and "two hours of work" do not
combine. Cap the work per run with a setting — *files per run* — and let the
schedule do the rest.

**Report progress.** It reaches the notification, which is the only thing a user
has to tell a working plugin from a stuck one.

## Notifications

The rule the host enforces: **a run is never silent.** Started by hand or by an
alarm, reporting progress or not, app on screen or closed for hours — something
happened on the user's device with their permissions, and they are told.

Four channels, so one can be silenced without losing the others:

| Channel | Answers |
|---|---|
| `zetaforge.runs` | Is something running right now? (ongoing, with progress) |
| `zetaforge.results` | How did it end? |
| `zetaforge.schedule` | What was postponed or missed? |
| `zetaforge.attention` | Something needs me before it can work |

## The two settings that decide whether any of this works

Scheduled work fails silently on a real phone for two reasons. The host checks
both in one place and shows them in three — the first-run wizard, a banner on
the plugin list, and Diagnostics — and every failing row has a button that opens
the exact screen with the exact switch.

::: warning Battery optimisation
While the app is "optimised", Android can defer an alarm for hours and cut its
network access. This is the one worth insisting on, and it is the single most
common cause of "my backup stopped working".
:::

**Notifications.** Without them a background run is invisible, and Android is
more willing to stop a process nobody can see.

**Exact alarms**, only when a schedule asked for them. From Android 12 this is a
separate permission.

Where the phone is from a vendor known to add its own limits on top of
Android's, the panel says so, rather than letting the user conclude the app is
broken.

## What scheduling is not

::: note Not a real-time trigger
The granularity is 15 minutes and delivery goes through `AlarmManager`, so with
battery optimisation on, a run can slip by hours. Anything that must react the
moment something happens — a message arriving, entering a place — is outside
what this can do.
:::

## Good candidates

Things that are periodic, tolerant of being late, and useful without supervision:

* a nightly backup to a NAS, while charging, on Wi-Fi
* weekly media compression, capped at N files per run
* a health check over HTTP that stays quiet until something is down
* a watcher that notifies only when a page or a price changes
* a weekly export of contacts and calendar to a folder you control

## Next

[Screens →](screens.md)
