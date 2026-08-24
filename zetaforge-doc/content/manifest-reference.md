---
title: zetaplugin.toml
description: Every field of the plugin descriptor, with its defaults and its constraints.
---

# zetaplugin.toml

The whole configuration of a plugin. There is no build file: `zeta build`
generates one from this and throws it away.

```toml
[plugin]
id          = "com.example.weather"
name        = "Weather"
version     = "1.0.0"
author      = "Jane Doe"
homepage    = "https://example.com/weather"
license     = "Apache-2.0"
description = "One paragraph, written for a human."
entryPoint  = "com.example.weather.WeatherPlugin"
minSdk      = 26
minHostApi  = 4
maxHostApi  = 4
category    = "utility"

[ui]
only = true

[dependencies]
retrofit = "com.squareup.retrofit2:retrofit:2.11.0"

[[setting]]
key   = "city"
type  = "text"
label = "City"

[[permission]]
name   = "android.permission.INTERNET"
reason = "Downloads the forecast"

[[specialAccess]]
id     = "allFilesAccess"
reason = "Writes the archive anywhere you choose"
```

## `[plugin]`

| Field | Required | |
|---|---|---|
| `id` | **yes** | Reverse-DNS, lowercase, at least two parts. Also the installation directory and the key of every stored setting. |
| `name` | no | Shown on the card. Defaults to the last part of `id`. |
| `version` | **yes** | `major.minor.patch`. The host sorts updates by it. |
| `entryPoint` | **yes** | Fully qualified class implementing `ZetaPlugin`. Checked against the produced DEX. |
| `author` | no | Shown on the card and in details. |
| `homepage` | no | A project or support URL. |
| `license` | no | Your plugin's licence, e.g. `Apache-2.0`. Not ZetaForge's. |
| `description` | no | One paragraph, shown in details. Write it for a user. |
| `minSdk` | no | Oldest Android you support. Default `26`. |
| `minHostApi` | no | Hard requirement; an older host refuses to install. Defaults to the CLI's own. |
| `maxHostApi` | no | What you tested; a newer host warns but runs. |
| `category` | no | Free-form label for grouping. |

::: danger `id` is forever
It is the installation folder and the key of every saved setting and state file.
**Changing it creates a second, unrelated plugin** — the old one keeps its data
and stays installed. Renaming a plugin means changing `name`, never `id`.
:::

## `[ui]`

Present only for a plugin that has a [screen](screens.md).

| Field | Default | |
|---|---|---|
| `enabled` | `true` | Set `false` to declare "built by a toolchain that knows about screens, but this one has none". |
| `only` | `false` | The screen is the whole plugin: the host hides START and SCHEDULE. |
| `uiApi` | the CLI's own | The screen contract this was built against. Do not set it by hand. |
| `label` | — | Overrides the text on the button that opens it. |

Declaring `[ui]` also adds `ui` to the package's capability list automatically,
so the two can never disagree.

## `[dependencies]`

```toml
[dependencies]
retrofit = "com.squareup.retrofit2:retrofit:2.11.0"
thing    = { module = "com.example:thing:1.0", excludeKotlin = false }
```

The key is a label of your choosing. The value is a Maven coordinate, or a table
when you need `excludeKotlin`. See [Dependencies](dependencies.md).

## `[[setting]]`

Repeatable. Order in the file is order in the form.

| Field | Applies to | |
|---|---|---|
| `key` | all | How the value reaches your code. Required. |
| `type` | all | `switch`, `number`, `decimal`, `text`, `choice`, `multiChoice`, `folder`, `action`. Required. |
| `label` | all | What the user reads. Defaults to `key`. |
| `description` | all | The sentence underneath. |
| `group` | all | Section header. Fields with the same group are shown together. |
| `advanced` | all | `true` hides it under "Show advanced". |
| `default` | all but `action` | Typed as the field is. |
| `min` `max` `step` | `number`, `decimal` | Bounds for the slider. |
| `unit` | `number`, `decimal` | Suffix, e.g. `%`, `MB`. |
| `hint` | `text` | Placeholder. |
| `secret` | `text` | Masks the value. **Not encrypted at rest.** |
| `options` | `choice`, `multiChoice` | The values your code sees. |
| `optionLabels` | `choice`, `multiChoice` | What the user reads, positionally matched. |
| `runningLabel` | `action` | Shown while the action runs. |

Full detail and examples: [Settings](settings.md).

## `[[permission]]`

Repeatable.

| Field | | |
|---|---|---|
| `name` | **required** | The Android permission, e.g. `android.permission.INTERNET`. |
| `reason` | **required** | Shown to the user when asked. The build fails without it. |
| `optional` | default `false` | `true` lets the run proceed when denied. |
| `minSdk` | default `1` | Only requested at or above this API level. |
| `maxSdk` | default unbounded | Only requested at or below it. |

## `[[specialAccess]]`

Repeatable. For the capabilities Android grants through a Settings screen rather
than a dialog.

| Field | | |
|---|---|---|
| `id` | **required** | One of `allFilesAccess`, `displayOverOtherApps`, `exactAlarms`, `usageAccess`, `notificationAccess`, `ignoreBatteryOptimizations`, `installPackages`, `writeSettings`. |
| `reason` | **required** | Shown before the Settings screen is opened. |
| `optional` | default `false` | |

See [Permissions](permissions.md#special-access).

## Validation

The CLI validates the descriptor before compiling anything, and every failure is
written as a sentence with a hint rather than a stack trace:

* `id` must be reverse-DNS and lowercase
* `version` must be `major.minor.patch`
* `entryPoint` must be a fully qualified class name — and must exist in the
  produced DEX, which is checked after compiling, with the closest match
  suggested when it does not
* `minHostApi` must not exceed what the CLI builds for
* `minHostApi` must not exceed `maxHostApi`
* every permission must have a reason
* `[ui].uiApi` must not exceed the screen contract the CLI knows

## Next

[The .zeta format →](package-format.md)
