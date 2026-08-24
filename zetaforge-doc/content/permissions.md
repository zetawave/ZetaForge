---
title: Permissions
description: Declare what your plugin needs and why, and understand the one hard limit — the host's manifest is the ceiling.
---

# Permissions

## Declaring

```toml
[[permission]]
name   = "android.permission.READ_MEDIA_IMAGES"
reason = "Reads your photos in order to copy them"
minSdk = 33                    # only requested on Android 13+

[[permission]]
name   = "android.permission.READ_EXTERNAL_STORAGE"
reason = "Reads your photos in order to copy them"
maxSdk = 32                    # the pre-13 equivalent

[[permission]]
name     = "android.permission.ACCESS_MEDIA_LOCATION"
reason   = "Keeps the location stored inside photos"
optional = true                # run anyway if denied

[[specialAccess]]
id     = "allFilesAccess"
reason = "Needed to replace files anywhere in shared storage"
```

The `reason` is shown to the user, in a dialog, at the moment they are asked.
Write it for them: *"Reads your photos in order to copy them"*, not *"required
for MediaStore query"*. The CLI refuses to build a plugin whose permission has
no reason.

## What happens at START

Every run, not just the first:

1. the runtime re-checks each declared permission — Android can revoke them at
   any moment, and does so automatically for apps that go unused;
2. anything missing is requested, with your reason on screen;
3. a **mandatory** permission that is denied stops the run with a structured
   failure, before your code is called;
4. an **optional** one that is denied lets the run proceed — your code has to
   cope, so check at run time;
5. special accesses open the right Settings screen and are re-checked on return.

```text
execute()
  → what does this plugin need, on THIS device, right now?
  → not applicable on this API level?      ignore
  → already granted?                       proceed
  → not in the host's manifest?            PERMISSION_NOT_DECLARED_BY_HOST
  → requestable?                           your reason → the system dialog
  → denied for good?                       PERMISSION_PERMANENTLY_DENIED
  → special access?                        explanation → the exact Settings screen
  → re-inspect, then run or fail
```

Nothing is cached. A plugin that worked yesterday can legitimately be blocked
today.

## The host superset — the trap worth knowing

::: danger A permission the host does not declare can never be granted
Android refuses it without showing anything to anyone: no dialog, no error, just
denied.
:::

Permissions are frozen into an app's manifest when the app is built. Your plugin
has no manifest of its own that Android reads — so it can only ever use
permissions the **host's** APK already declares.

The host therefore declares a broad superset covering what plugins realistically
need: media and files, camera, microphone, location, Bluetooth, contacts,
calendar, notifications, alarms, and the special accesses.

If what you need falls outside it, no amount of asking helps — the host has to
be rebuilt with the permission added. The CLI cannot check this for you, because
it does not know which host build the user has, but the host says so plainly at
run time: the failure names the permission and explains that it is not declared.

If you need something outside the superset,
[open an issue](https://github.com/zetawave/ZetaForge/issues) — the list is
meant to grow.

## Special access

These are the ones Android grants through a dedicated Settings screen rather
than a dialog:

| `id` | Settings screen |
|---|---|
| `allFilesAccess` | All files access |
| `displayOverOtherApps` | Display over other apps |
| `exactAlarms` | Alarms & reminders |
| `usageAccess` | Usage access |
| `notificationAccess` | Notification access |
| `ignoreBatteryOptimizations` | Unrestricted battery usage |
| `installPackages` | Install unknown apps |
| `writeSettings` | Modify system settings |

The host opens the exact screen and re-checks when the user comes back — an
instruction like "go to settings and find the switch" is advice, not help.

Use `allFilesAccess` sparingly. It is the most invasive thing a plugin can ask
for, and users are right to be suspicious of it.

## Denied permanently

Android stops showing the dialog after two refusals. The host detects this and
tells the user to grant it from the app's settings page, with a button that
takes them there. You do not have to handle it.

## Checking an optional permission yourself

```kotlin
val fine = ContextCompat.checkSelfPermission(
    context,
    Manifest.permission.ACCESS_MEDIA_LOCATION,
) == PackageManager.PERMISSION_GRANTED

val report = if (fine) detailedReport() else basicReport()
```

## Asking for less

The permission list is the first thing a careful user reads, and it is on the
card before they ever press START. Two habits help:

**Mark what you can as `optional`.** A plugin that still does most of its job
without a permission is far more likely to be run at all.

**Use `minSdk` / `maxSdk`.** Requesting `READ_EXTERNAL_STORAGE` on Android 13,
where it does nothing, looks careless and costs you trust.

## What the user sees

Before running anything: the card lists the permissions the package declares.

`DETAILS` shows each one with your reason and its live state — granted, will be
requested, denied permanently, or not needed on this Android version.

`VIEW CODE` shows your source, shipped inside the `.zeta`, so a permission and
the code that uses it can be read side by side.

## Next

[Dependencies →](dependencies.md)
