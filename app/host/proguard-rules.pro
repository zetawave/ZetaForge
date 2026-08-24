# ---------------------------------------------------------------------------
# ZetaForge Host - R8 configuration for release builds.
#
# The Host is not a normal app: it loads code it was never compiled against.
# Anything a plugin may touch at runtime is, from R8's point of view, unused -
# so it must be kept explicitly or dynamically loaded plugins break in release
# while working perfectly in debug.
# ---------------------------------------------------------------------------

# --- The plugin contract ---------------------------------------------------
# Also shipped as consumer rules by :plugin-api, repeated here so a release
# build stays correct even if the module layout changes.
-keep interface com.zetaforge.sdk.** { *; }
-keep class com.zetaforge.sdk.** { *; }

# Entry points are resolved by name from the .zeta manifest.
-keepclassmembers class * implements com.zetaforge.sdk.ZetaPlugin {
    public <init>();
    public *;
}

# --- Runtime types that cross the boundary ---------------------------------
-keep class com.zetaforge.runtime.PluginEntry { *; }
-keep class com.zetaforge.runtime.install.InstalledPlugin { *; }
-keep class com.zetaforge.runtime.manifest.ZetaManifest { *; }

# --- The Kotlin runtime is part of the plugin ABI --------------------------
# Plugins declare the Kotlin stdlib and coroutines as `compileOnly` and expect
# the Host to provide them at runtime. R8 only sees the Host's own usage, so
# anything a plugin needs but the Host happens not to call gets removed - and
# the plugin dies with ClassNotFoundException (measured: `kotlin.Unit`).
# The whole shared runtime is therefore kept, which is the real cost of being a
# plugin host: roughly +1 MB of APK, in exchange for a stable plugin ABI.
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep interface kotlinx.coroutines.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# --- Compose is part of the plugin ABI too ---------------------------------
# The same argument as the Kotlin runtime above, for the same reason: a screen
# plugin declares Compose `compileOnly` and draws with the Host's copy. R8 sees
# only what the Host itself calls, so any composable, modifier or Material
# component the Host happens not to use would be removed - and the plugin that
# uses it dies with NoSuchMethodError in release while working in debug.
#
# The icons are kept as well. They are the largest single item here, and the
# alternative is worse: a plugin referencing an icon the Host does not is a
# release-only crash, which is the hardest kind of bug this project can ship.
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.animation.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.material.icons.** { *; }
-dontwarn androidx.compose.**

# --- Class loading ---------------------------------------------------------
-keep class dalvik.system.** { *; }

# Keep line numbers so plugin stack traces stay readable in the log view.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile
