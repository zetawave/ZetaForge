---
title: Screens
description: Give a plugin a real interface. Compose supplied by the host, drawn in a container Activity, with failures contained.
---

# Screens

A plugin can be something the user *opens* rather than something they start: a
screen, written in Compose, drawn inside the host.

```kotlin
class CalculatorPlugin : ZetaUiPlugin {
    override val id = "com.example.calculator"
    override val name = "Calculator"
    override val version = "1.0.0"

    @Composable
    override fun Content(host: ZetaUiHost) {
        // your screen
    }
}
```

```toml
[ui]
only = true        # this plugin is a screen and nothing else
```

That is the whole opt-in. The card shows **OPEN**, and with `only = true` it
hides START and SCHEDULE, because scheduling something that exists only while a
person is looking at it means nothing.

## Why a composable and not an Activity

Android resolves components from the manifest of an *installed APK*, frozen at
install time. An `Activity` class living in a plugin's DEX is unstartable:
`startActivity` asks the system to resolve a component it has never heard of,
and the system refuses. It is the same wall as
[a permission the host does not declare](permissions.md#the-host-superset-the-trap-worth-knowing),
in a different place.

So the host declares one container Activity, and the plugin supplies *content*:

```text
Plugin card [OPEN]  ──▶  PluginScreenActivity(pluginId)     ← the host's, in its manifest
                              │
                              ├─ runtime.openUi(pluginId)   ← the same class loader as always
                              └─ plugin.Content(host)       ← a @Composable, not a component
```

Compose is what makes this pleasant rather than merely possible: it needs no
resources, so a plugin still needs no `resources.arsc`, no `R` class, and the
`.zeta` format does not change at all. The package that draws a full screen is
the same package that ran a batch job yesterday.

## What the plugin is given

Not the `Activity` — that belongs to the host, and handing it over would let a
plugin finish it, replace its content view, or start a permission request the
runtime has no record of. Instead:

```kotlin
interface ZetaUiHost {
    val pluginId: String
    val pluginName: String
    val context: Context           // the Activity context, for dialogs and measuring
    val settings: Bundle           // the same shape execute() receives
    val scope: CoroutineScope      // tied to the screen, failures contained

    fun message(text: String)      // a line the host renders
    fun setSubtitle(text: String?) // the second line of the title bar
    suspend fun ensurePermissions(): Boolean
    fun close()
}
```

`settings` is a snapshot, in the same `Bundle` shape `execute` receives — so a
plugin that is both a screen and a job reads its configuration exactly one way.

`scope` is where every suspend call belongs. It is cancelled when the screen
closes and carries a handler that turns an uncaught failure into the host's
error screen rather than a dead process.

## Compose belongs to the host

This is the one rule that matters, and it is the same rule as the SDK's: the
host builds the composition and the plugin adds to it, so both halves must
resolve to the **same class objects**.

A bundled second copy of Compose produces
`ClassCastException: androidx.compose.runtime.Composer cannot be cast to
androidx.compose.runtime.Composer`, from inside a frame, with no useful stack.

Three separate things make sure that never ships:

1. **The build.** `zeta build` reads the class-definition table of the DEX it
   produced and fails if the plugin *defines* anything under `androidx.compose.`
   (or the SDK, Kotlin, or coroutines).
2. **The runtime.** The shared-contract check re-runs at load time and refuses
   with a sentence rather than a cast failure.
3. **The manifest.** Every screen records the contract version it was built
   against, and a host implementing an older one refuses to open it and says so.

::: note You get this for free
The generated build declares Compose `compileOnly`. You only hit this by adding
Compose to `[dependencies]` by hand.
:::

## When a plugin throws

`execute` is easy to contain: one suspend call, on a background dispatcher,
inside a `try`. A screen has no single call — its code is re-entered by the
framework from several directions, and an exception on any of them unwinds
through `ViewRootImpl` and kills the process.

Two mechanisms cover it:

| Where it throws | What catches it |
|---|---|
| touch dispatch, key dispatch, measure, layout, draw | the container view the plugin's content sits in |
| composition, recomposition, `LaunchedEffect`, `host.scope` | the plugin's **own** recomposer, with its own exception handler |

Either way the outcome is the same: the screen is replaced by an error report
carrying the exception and the top of its stack, the plugin is unloaded, and the
host keeps running. Nothing is repaired in place — after an exception a
composition describes a tree that was never finished — so it is torn down, and
the next OPEN starts from a fresh instance.

## Identity

The bar above every plugin screen — the plugin's name, and a **PLUGIN** badge —
is composed in a *different view, above* the plugin's own, and is not reachable
by the plugin's drawing.

That is not decoration. A screen plugin runs with the host's UID and the host's
permissions, so a convincing fake of the host's own UI asking for a password is
the cheapest attack such a plugin has. The plugin owns the second line of that
bar and nothing else.

::: warning What it does not prevent
A plugin can still open a `Dialog` over the whole window; Android gives no way
to stop that. What it cannot do is quietly repaint the host's identity.
:::

## A screen pins its plugin

While a screen is open:

* **unload is refused** — dropping the class loader under a live composition
  leaves objects whose classes no longer resolve;
* **uninstall and re-import wait** — they ask the screen to close and wait for
  it, bounded, so a stuck screen cannot hang an uninstall for ever.

```text
Installed in com.example.calculator
Waiting for the open screen to close
Screen closed
Unloaded
```

## What a screen cannot keep

::: danger Not `rememberSaveable`, for your own types
Saved instance state goes into a `Bundle` that Android restores with the
**host's** class loader, which has never heard of your classes. A screen that
saves its own types there restores into a `ClassNotFoundException`.
:::

The container Activity declares `configChanges`, so rotation is already handled.
What is given up is state across process death — and a lost half-typed form is a
far better outcome than a crash on the way back. A screen with state worth
keeping should write it somewhere it owns, exactly as a job would.

## Structuring one

Keep the state machine out of the composables. It is ordinary Kotlin, testable
on the JVM without a device, and it is where the bugs live:

```kotlin
// engine.kt — no Android, no Compose
internal data class State(val entry: String = "0", val error: String? = null)
internal fun press(state: State, key: Key): State = /* … */

// plugin.kt — only drawing
@Composable
override fun Content(host: ZetaUiHost) {
    var state by remember { mutableStateOf(State()) }
    Keypad(onKey = { state = press(state, it) })
    Display(state)

    LaunchedEffect(state.error) { host.setSubtitle(state.error) }
}
```

## The reference plugin

`app/plugins/calculator/` in the repository is a calculator, chosen because it
exercises the whole screen path and nothing else: touch input, state across
recompositions, the host's Material theme, its own declared settings — and no
permission, no network, no storage. If it works, what works is the mechanism.

```bash
./gradlew :plugins:calculator:buildZetaPlugin
```

## Next

[Logging and progress →](logging-and-progress.md)
