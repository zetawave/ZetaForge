---
title: Quick start
description: From an empty folder to a Kotlin plugin running on a real device, in about five minutes.
---

# Quick start

This builds a plugin, puts it on a device, and runs it. It assumes
[installation](installation.md) is done and `zeta doctor` is green.

## 1. Create a project

```bash
zeta new hello-world
cd hello-world
```

```text
hello-world/
├── zetaplugin.toml          identity, settings, permissions, dependencies
├── src/
│   └── com/example/helloworld/HelloWorldPlugin.kt
├── test/
│   └── HelloWorldPluginTest.kt
└── .gitignore
```

That is the whole project. There is **no build file**, no `AndroidManifest.xml`,
no resources and no Gradle wrapper to maintain — `zeta build` generates
everything the compiler needs from the descriptor and throws it away again.

Other starting points:

```bash
zeta new my-plugin --template network       # Retrofit + a real HTTP call
zeta new my-plugin --template background    # long work, progress, cancellation
zeta new --list-templates
```

## 2. Look at what you got

```toml title="zetaplugin.toml"
[plugin]
id          = "com.example.helloworld"
name        = "Hello World"
version     = "1.0.0"
author      = "You"
description = "Says hello, and shows how much the Host lends a plugin."
entryPoint  = "com.example.helloworld.HelloWorldPlugin"

minHostApi = 4
maxHostApi = 4

[[setting]]
key         = "greeting"
type        = "text"
label       = "Greeting"
description = "What the plugin says when it runs."
default     = "Hello"

[[setting]]
key     = "shout"
type    = "switch"
label   = "Shout it"
default = false
```

```kotlin title="src/com/example/helloworld/HelloWorldPlugin.kt"
class HelloWorldPlugin : ZetaPlugin {

    override val id = "com.example.helloworld"
    override val name = "Hello World"
    override val version = "1.0.0"

    override suspend fun execute(context: Context, input: Bundle): PluginResult {
        val greeting = input.getString("greeting") ?: "Hello"
        val shout = input.getBoolean("shout", false)

        val message = "$greeting from a plugin the app has never seen"
        return PluginResult.Success(
            message = if (shout) message.uppercase() else message,
            data = mapOf(
                "package" to context.packageName,
                "filesDir" to context.filesDir.absolutePath,
            ),
        )
    }
}
```

Two things are worth noticing.

`input` already contains every setting you declared, typed as declared. You did
not write a form and the app did not need an update to show one.

`context` is the **host's real Android `Context`**. Not a wrapper, not a
restricted subset: `filesDir`, `contentResolver`, `getSystemService`, all of it.

## 3. Build it

```bash
zeta build
```

```text
  ✓ Hello World 1.0.0                                          4.1s

    package       dist/hello-world-1.0.0.zeta
    size          18.4 KB
    classes       12
    entry point   com.example.helloworld.HelloWorldPlugin   ok
    settings      greeting, shout
    permissions   none
    screen        no
```

The entry point is not taken on trust — the CLI opens the DEX it just produced
and checks the class is really defined in it, suggesting the closest match when
it is not.

## 4. Put it on the device

```bash
zeta install
```

Open ZetaForge on the phone and the plugin is in the list. Tap **START**.

Or stay in the terminal:

```bash
zeta run
```

```text
  ✓ SUCCESS  312 ms

    Hello from a plugin the app has never seen

    package    com.zetaforge.app
    filesDir   /data/user/0/com.zetaforge.app/files
```

`package` is the host's — proof the plugin is running inside it rather than
somewhere of its own.

## 5. The loop you will actually use

```bash
zeta dev
```

Watches your sources and, on every save, rebuilds, reinstalls and runs, printing
the result and the plugin's log lines. It is the whole edit–run cycle in one
terminal, with no IDE involved.

```bash
zeta logs        # follow the log stream on its own
zeta test        # run the JVM tests, no device needed
```

## 6. Add a dependency

Say the plugin should fetch something. Add the library to the descriptor:

```toml
[dependencies]
retrofit = "com.squareup.retrofit2:retrofit:2.11.0"
gson     = "com.squareup.retrofit2:converter-gson:2.11.0"
```

Then use it as you would in any Kotlin project. That library is compiled into
**your** DEX; the app does not contain Retrofit and never will. Two plugins can
use two different versions of it without either noticing — see
[class loading](class-loading.md) for why that works.

## Where to go next

::: cards
- [Plugin anatomy](plugin-anatomy.md) — The contract in full: what you implement and what you get.
- [Settings](settings.md) — Every field type, run-time options, and action buttons.
- [Scheduling](scheduling.md) — Make it run on its own: nightly, on Wi-Fi, while charging.
- [Screens](screens.md) — Give the plugin a real interface with Compose.
:::
