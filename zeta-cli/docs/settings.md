# Settings

Declare a parameter, and the Host builds the form. You write no UI, and adding a
setting needs no change to the app — the form is generated from what the package
declares, at run time, on the user's phone.

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

| type | renders as | arrives as |
|---|---|---|
| `switch` | a toggle | `input.getBoolean(key)` |
| `number` | a slider or field | `input.getInt(key)` |
| `decimal` | a slider | `input.getDouble(key)` |
| `text` | a text field (`secret = true` masks it) | `input.getString(key)` |
| `choice` | a dropdown | `input.getString(key)` |
| `multiChoice` | chips | `input.getStringArray(key)` |
| `folder` | a system folder picker | `input.getString(key)` — a persisted URI |
| `action` | a button | never: see [Actions](#actions) |

```toml
[[setting]]
key     = "codec"
type    = "choice"
label   = "Video codec"
options = ["hevc", "avc"]
optionLabels = ["HEVC (H.265) — smallest", "AVC (H.264) — most compatible"]
default = "hevc"
```

```toml
[[setting]]
key     = "folders"
type    = "multiChoice"
label   = "Folders"
options = ["DCIM", "Pictures", "Movies"]
default = ["DCIM"]
```

```toml
[[setting]]
key   = "destination"
type  = "folder"
label = "Where to write"
```

A `folder` setting opens the system picker and the Host keeps the permission
across reboots, so you can write there on later runs without asking again.

## Reading them

```kotlin
override suspend fun execute(context: Context, input: Bundle): PluginResult {
    val quality = input.getInt("quality", 82)
    val codec = input.getString("codec") ?: "hevc"
    val folders = input.getStringArray("folders") ?: arrayOf("DCIM")
    val dryRun = input.getBoolean("dryRun", false)
}
```

The Bundle always contains every declared key, typed as declared: the Host
merges the saved values over your defaults before calling you. Always pass a
fallback anyway — it makes the function testable without a Host.

Values passed explicitly (by a test calling `execute` directly) win over the
saved ones. Unknown keys are preserved rather than dropped.

## The run-time hook

Some things cannot be known when you write the descriptor — which video encoders
this particular phone has, which accounts are configured, what the server
offers. Return them from `settings()` and they are merged **over** the declared
fields, matched by key:

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

The call is time-limited and its failures are contained: if it throws or hangs,
the dialog falls back to what the package declares. Return `null` to change
nothing.

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
override suspend fun runAction(context: Context, actionKey: String, current: Bundle) =
    when (actionKey) {
        "testConnection" -> ZetaActionResult.ok("Connected to 192.168.0.154 → D:\\Backup")
        else -> ZetaActionResult.failed("Unknown action: $actionKey")
    }
```

Keep them short — a dialog is waiting. "Test the connection", "estimate the
result", "sign in": not the work itself.

An action can also write values back into the open form:

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

**Prefer an action over a paragraph.** "Test connection" answers a question that
no amount of help text can.
