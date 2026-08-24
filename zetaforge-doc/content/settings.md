---
title: Settings
description: Declare a parameter and the host builds the form. Every field type, run-time options, and action buttons.
---

# Settings

Declare a parameter and the host renders the form. You write no UI, and adding a
setting needs no change to the app: the form is generated on the user's phone
from what your package declares.

## Declaring

```toml
[[setting]]
key         = "quality"
type        = "number"
label       = "Photo quality"
description = "82 is visually transparent; below 75 artefacts show."
group       = "Quality"      # section header in the form
advanced    = false          # true hides it under "Show advanced"
min         = 50
max         = 100
step        = 1
unit        = "%"
default     = 82
```

`key` is how the value reaches your code. `label` is what the user sees.
`description` is the sentence under it — write it for them.

## The types

| `type` | Renders as | Arrives as |
|---|---|---|
| `switch` | a toggle | `input.getBoolean(key)` |
| `number` | a slider or field | `input.getInt(key)` |
| `decimal` | a slider | `input.getDouble(key)` |
| `text` | a text field (`secret = true` masks it) | `input.getString(key)` |
| `choice` | a dropdown | `input.getString(key)` |
| `multiChoice` | chips | `input.getStringArray(key)` |
| `folder` | a system folder picker | `input.getString(key)` — a persisted URI |
| `action` | a button | never — see [Actions](#actions) |

### choice

```toml
[[setting]]
key          = "codec"
type         = "choice"
label        = "Video codec"
options      = ["hevc", "avc"]
optionLabels = ["HEVC (H.265) — smallest", "AVC (H.264) — most compatible"]
default      = "hevc"
```

`options` are the values your code sees; `optionLabels` are what the user reads.
Keeping them apart means you can improve the wording without breaking saved
settings.

### multiChoice

```toml
[[setting]]
key     = "folders"
type    = "multiChoice"
label   = "Folders"
options = ["DCIM", "Pictures", "Movies"]
default = ["DCIM"]
```

### folder

```toml
[[setting]]
key   = "destination"
type  = "folder"
label = "Where to write"
```

Opens the system picker. The host takes the permission *persistably*, so you can
still write there after a reboot without asking again. The value is a
`content://` URI, not a path — use `DocumentFile` or the `ContentResolver`.

### text with `secret`

```toml
[[setting]]
key    = "token"
type   = "text"
label  = "API token"
secret = true
```

::: warning Secret means masked, not encrypted
`secret = true` hides the value in the form. It is **not** encrypted at rest.
Anything that gets the host's private files gets the token.
:::

## Reading them

```kotlin
override suspend fun execute(context: Context, input: Bundle): PluginResult {
    val quality = input.getInt("quality", 82)
    val codec = input.getString("codec") ?: "hevc"
    val folders = input.getStringArray("folders") ?: arrayOf("DCIM")
    val dryRun = input.getBoolean("dryRun", false)
}
```

The bundle always contains every declared key, typed as declared: the host
merges saved values over your defaults before calling you. Pass a fallback
anyway — it makes the function testable without a host.

Values passed explicitly — by a test calling `execute` directly, or by the Host
when it re-runs a plugin — win over saved ones. Unknown keys are preserved
rather than dropped.

## Computed at run time

Some things cannot be known when you write the descriptor: which video encoders
*this* phone has, which accounts are configured, what the server offers today.
Return them from `settings()` and they are merged **over** the declared fields,
matched by key.

```kotlin
override suspend fun settings(context: Context, current: Bundle): ZetaSettingsSpec? =
    ZetaSettingsSpec(
        listOf(
            ZetaSetting.Choice(
                key = "codec",
                label = "Video codec",
                description = "Encoders available on this device.",
                group = "Quality",
                default = "hevc",
                options = encodersOnThisDevice(),
            )
        )
    )
```

`current` holds what is saved so far, so a field can react to another one.

The call is time-limited and its failures are contained: if it throws or hangs,
the dialog falls back to what the package declares — a broken `settings()` still
leaves a usable form. Return `null` to change nothing.

## Actions

An action is a button that runs a short routine and shows the answer, without
starting the plugin proper.

```toml
[[setting]]
key          = "testConnection"
type         = "action"
label        = "Test connection"
description  = "Checks that the PC is reachable right now."
runningLabel = "Looking…"
```

```kotlin
override suspend fun runAction(
    context: Context,
    actionKey: String,
    current: Bundle,
): ZetaActionResult = when (actionKey) {
    "testConnection" -> {
        val host = current.getString("host").orEmpty()
        if (reachable(host)) ZetaActionResult.ok("Connected to $host")
        else ZetaActionResult.failed("No answer from $host")
    }
    else -> ZetaActionResult.failed("Unknown action: $actionKey")
}
```

Keep them short — a dialog is waiting. "Test the connection", "estimate the
result", "sign in": not the work itself.

An action can also write values back into the open form, which is how discovery
buttons work:

```kotlin
ZetaActionResult.ok(
    message = "Found the receiver",
    updatedValues = bundleOf("host" to "192.168.0.154"),
)
```

## Design notes

**Defaults should be the right answer.** Most users never open the form. A
setting exists for the ones who need to disagree with you.

**Put the dangerous ones under `advanced`.** Anything that can destroy data or
produce a worse result belongs behind "Show advanced".

**Group by decision, not by type.** "What to compress", "Quality", "Run" reads
better than "Switches" and "Numbers".

**Prefer an action over a paragraph.** "Test connection" answers a question no
amount of help text can.

## Next

[Permissions →](permissions.md)
