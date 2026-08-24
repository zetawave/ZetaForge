package com.zetaforge.runtime.loader

import android.os.Build
import com.zetaforge.runtime.install.InstalledPlugin
import dalvik.system.DelegateLastClassLoader
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Creates the class loader a plugin runs in.
 *
 * ## Why a dedicated loader per plugin
 * Each plugin gets its own loader so its classes can be dropped as a unit
 * (unload = release the loader) and so two plugins never see each other's
 * classes.
 *
 * ## Which loader
 * * [DexClassLoader] is parent-first: any class the Host already has wins.
 *   Simple, but a plugin can never use a different version of a library the
 *   Host also ships.
 * * [DelegateLastClassLoader] (API 27+) looks up bootclasspath -> own DEX ->
 *   parent. That is exactly what a plugin system wants: the plugin's own bundled
 *   dependencies (Retrofit, OkHttp, ...) win over anything the Host happens to
 *   have, so `Host -> library X` and `Plugin -> library Y` can coexist.
 *
 * ZetaForge therefore uses [DelegateLastClassLoader] when available and falls
 * back to [DexClassLoader] on API 26.
 *
 * ## The shared-contract rule
 * Types that cross the boundary (`com.zetaforge.sdk.*`, the Kotlin stdlib and
 * coroutines) must resolve to *one* class object on both sides, otherwise the
 * Host cannot cast the instance it just created to [com.zetaforge.sdk.ZetaPlugin].
 * This is guaranteed at build time, not at load time: the plugin module declares
 * those artifacts as `compileOnly`, so they are never compiled into the plugin
 * DEX and delegate-last has nothing local to find. [SharedContract] documents and
 * enforces that invariant at runtime.
 */
object PluginClassLoaderFactory {

    /**
     * @param plugin the installed plugin whose `code.jar` should be loaded.
     * @param parent the Host class loader; supplies the shared contract classes.
     * @param nativeLibraryDir optional directory for future `.so` support.
     */
    fun create(
        plugin: InstalledPlugin,
        parent: ClassLoader,
        nativeLibraryDir: File? = null,
        optimizedDir: File? = null,
    ): PluginClassLoader {
        val dexPath = plugin.codeJar.absolutePath
        val libPath = nativeLibraryDir?.absolutePath

        val loader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            DelegateLastClassLoader(dexPath, libPath, parent)
        } else {
            DexClassLoader(dexPath, optimizedDir?.absolutePath, libPath, parent)
        }

        return PluginClassLoader(
            delegate = loader,
            strategy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                Strategy.DELEGATE_LAST
            } else {
                Strategy.PARENT_FIRST
            },
            dexPath = dexPath,
        )
    }

    enum class Strategy { DELEGATE_LAST, PARENT_FIRST }
}

/** Wrapper carrying the loader plus the decision that produced it, for logging. */
class PluginClassLoader(
    val delegate: ClassLoader,
    val strategy: PluginClassLoaderFactory.Strategy,
    val dexPath: String,
) {
    fun loadClass(name: String): Class<*> = delegate.loadClass(name)
}

/**
 * Types that must be identical on both sides of the boundary.
 *
 * Used by the runtime to fail with an explicit message instead of an opaque
 * `ClassCastException` when a plugin accidentally bundles the contract.
 */
object SharedContract {

    val packages: List<String> = listOf(
        "com.zetaforge.sdk",
        "kotlin",
        "kotlinx.coroutines",
        // Screens are drawn with the Host's Compose. It is a boundary type for
        // exactly the same reason the SDK is: the Host builds the composition
        // and the plugin adds to it, so both halves must be the same objects.
        "androidx.compose",
    )

    /**
     * Returns the contract classes the plugin loaded from its *own* DEX instead
     * of from the Host. Any non-empty result means the plugin was built wrong.
     */
    fun conflicts(pluginLoader: ClassLoader, hostLoader: ClassLoader): List<String> =
        CONTRACT_CLASSES.filter { name ->
            runCatching {
                val fromPlugin = pluginLoader.loadClass(name)
                val fromHost = hostLoader.loadClass(name)
                fromPlugin !== fromHost
            }.getOrDefault(false)
        }

    private val CONTRACT_CLASSES = listOf(
        "com.zetaforge.sdk.ZetaPlugin",
        "com.zetaforge.sdk.PluginResult",
        "kotlin.coroutines.Continuation",
        // The two Compose types every compiled composable mentions: if a plugin
        // bundled Compose, these are what would differ, and the screen would
        // die with a ClassCastException nobody could read.
        "androidx.compose.runtime.Composer",
        "androidx.compose.runtime.internal.ComposableLambda",
    )
}
