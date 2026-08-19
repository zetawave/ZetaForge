# Permissions

## Declaring

```toml
[[permission]]
name   = "android.permission.READ_MEDIA_IMAGES"
reason = "Reads your photos in order to copy them"
minSdk = 33                    # only requested on Android 13+

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

Every time, not just the first:

1. the runtime re-checks each declared permission — they can be revoked at any
   moment, including while your plugin is running;
2. anything missing is requested, with your reason on screen;
3. a **mandatory** permission that is denied stops the run with a structured
   failure, before your code is called;
4. an **optional** one that is denied lets the run proceed — your code has to
   cope, so check at run time;
5. special accesses open the right Settings screen and are re-checked on return.

## The Host superset — the trap worth knowing

**A permission the Host APK does not declare can never be granted.** Android
refuses it without showing anything to anyone: no dialog, no error, just denied.

Permissions are frozen into an app's manifest when the app is built. The Host
therefore declares a broad superset covering what plugins realistically need. If
yours falls outside it, no amount of asking will help — the Host has to be
rebuilt with the permission added.

`zeta build` cannot check this for you (it does not know which Host the user has),
but the Host says so plainly at run time: the failure names the permission and
explains that it is not declared.

If you need something outside the superset, open an issue: the list is meant to
grow.

## Special access

These are the ones Android does not grant through a dialog but through a
dedicated Settings screen:

| id | screen |
|---|---|
| `allFilesAccess` | All files access |
| `displayOverOtherApps` | Display over other apps |
| `exactAlarms` | Alarms & reminders |
| `usageAccess` | Usage access |
| `notificationAccess` | Notification access |
| `ignoreBatteryOptimizations` | Unrestricted battery usage |
| `installPackages` | Install unknown apps |
| `writeSettings` | Modify system settings |

The Host opens the right screen and re-checks when the user comes back. Use
`allFilesAccess` sparingly — it is the most invasive thing a plugin can ask for,
and users are right to be suspicious of it.

## Denied permanently

Android stops showing the dialog after two refusals. The Host detects this and
tells the user to grant it from the app's settings page, with a button that
takes them there. You do not have to handle it.

## Asking for less

The permission list is the first thing a careful user reads. Two habits that
help:

* **Mark what you can as `optional`.** A plugin that still does most of its job
  without a permission is far more likely to be run at all.
* **Use `minSdk` / `maxSdk`.** Requesting `READ_EXTERNAL_STORAGE` on Android 13,
  where it does nothing, looks careless and costs you trust.
