package com.zetaforge.app

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zetaforge.runtime.ImportResult
import com.zetaforge.runtime.ZetaPluginRuntime
import com.zetaforge.sdk.PluginResult
import com.zetaforge.sdk.PluginState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * End-to-end acceptance test for the whole ZetaForge chain:
 *
 * ```
 * externally built Kotlin plugin -> retrofit-demo.zeta -> import -> verify
 * -> install in app-private storage -> DEX class loading -> entry point
 * -> Host Context -> Retrofit/OkHttp (bundled in the plugin) -> real HTTPS GET
 * ```
 *
 * The `.zeta` under test is the real artifact produced by
 * `:plugins:retrofit-demo:buildZetaPlugin`, copied into the test assets by the
 * Host build script. Nothing here is mocked.
 *
 * The network tests need the device to be online; they are the point of the PoC.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ZetaForgeAcceptanceTest {

    private lateinit var runtime: ZetaPluginRuntime

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // Start from a clean private storage so every run installs from scratch.
        File(context.filesDir, "zetaforge").deleteRecursively()
        runtime = ZetaPluginRuntime(context)
        runBlocking { runtime.refresh() }
    }

    @Test
    fun test01_importInstallsThePackageInPrivateStorage() = runBlocking {
        val result = importDemoPlugin()

        assertTrue("import failed: $result", result is ImportResult.Success)
        val entry = (result as ImportResult.Success).entry
        assertEquals(PLUGIN_ID, entry.id)
        assertEquals("Retrofit Demo", entry.installed.displayName)

        val installDir = File(File(context.filesDir, "zetaforge/plugins"), PLUGIN_ID)
        assertTrue("package not stored privately", File(installDir, "current.zeta").isFile)
        assertTrue("code.jar not prepared", File(installDir, "extracted/code.jar").isFile)
        assertEquals(64, entry.installed.sha256.length)
        assertTrue(entry.installed.manifest.permissions.contains("android.permission.INTERNET"))
    }

    @Test
    fun test02_executeLoadsDexAndPerformsRealHttpRequest() = runBlocking {
        importDemoPlugin()

        val result = runtime.execute(PLUGIN_ID)

        assertTrue("expected success, got $result", result is PluginResult.Success)
        val success = result as PluginResult.Success
        assertEquals("200", success.data["httpStatus"])
        assertTrue("no response body", (success.data["responseBytes"]?.toInt() ?: 0) > 0)

        // The plugin really got the Host Context.
        assertEquals(context.packageName, success.data["hostPackage"])
        assertNotNull(success.data["hostFilesDir"])
        assertNotNull(success.data["contentResolver"])

        // ... and it ran from its own class loader, not the Host's.
        val loaderName = success.data["pluginClassLoader"].orEmpty()
        assertTrue("unexpected loader: $loaderName", loaderName.contains("ClassLoader"))
        assertTrue(loaderName != context.classLoader.javaClass.name)

        val entry = runtime.plugins.value.first { it.id == PLUGIN_ID }
        assertEquals(PluginState.SUCCESS, entry.state)
    }

    @Test
    fun test03_retrofitAndOkHttpComeFromThePluginNotFromTheHost() = runBlocking {
        importDemoPlugin()
        val result = runtime.execute(PLUGIN_ID) as PluginResult.Success
        assertTrue(result.data["okhttpVersion"].orEmpty().isNotBlank())

        // The Host itself cannot see Retrofit: it is not one of its dependencies.
        val hostSees = runCatching { context.classLoader.loadClass("retrofit2.Retrofit") }.isSuccess
        assertTrue("Host APK must not contain Retrofit", !hostSees)
        val hostSeesOkHttp = runCatching { context.classLoader.loadClass("okhttp3.OkHttpClient") }.isSuccess
        assertTrue("Host APK must not contain OkHttp", !hostSeesOkHttp)
    }

    @Test
    fun test04_pluginCanBeExecutedMoreThanOnce() = runBlocking {
        importDemoPlugin()

        val first = runtime.execute(PLUGIN_ID)
        val second = runtime.execute(PLUGIN_ID)

        assertTrue(first is PluginResult.Success)
        assertTrue(second is PluginResult.Success)
    }

    @Test
    fun test05_hostSurvivesAPluginThatThrows() = runBlocking {
        importDemoPlugin()

        val input = Bundle().apply { putBoolean("throwOnPurpose", true) }
        val result = runtime.execute(PLUGIN_ID, input)

        assertTrue(result is PluginResult.Failure)
        val failure = result as PluginResult.Failure
        assertEquals("RuntimeException", failure.errorCode)
        assertTrue(failure.data.containsKey("stackTrace"))

        // The Host is still fully functional afterwards.
        val recovered = runtime.execute(PLUGIN_ID)
        assertTrue("runtime broken after plugin crash", recovered is PluginResult.Success)
    }

    @Test
    fun test06_networkFailureIsReportedAsFailureNotAsCrash() = runBlocking {
        importDemoPlugin()

        val input = Bundle().apply { putString("baseUrl", "https://zetaforge-unreachable.invalid/") }
        val result = runtime.execute(PLUGIN_ID, input)

        assertTrue(result is PluginResult.Failure)
        assertEquals("NETWORK_ERROR", (result as PluginResult.Failure).errorCode)

        val entry = runtime.plugins.value.first { it.id == PLUGIN_ID }
        assertEquals(PluginState.FAILED, entry.state)
    }

    @Test
    fun test07_logsContainTheFullLifecycle() = runBlocking {
        importDemoPlugin()
        runtime.execute(PLUGIN_ID)

        val lines = runtime.logger.records.value.map { it.formatted() }
        listOf("Plugin loaded", "START", "Retrofit initialized", "HTTP 200", "SUCCESS").forEach { marker ->
            assertTrue("missing log line: $marker\n" + lines.joinToString("\n"), lines.any { it.contains(marker) })
        }
    }

    @Test
    fun test08_invalidPackageIsRejected() = runBlocking {
        val garbage = "definitely not a zeta package".byteInputStream()

        val result = runtime.importPlugin(garbage, "garbage.zeta")

        assertTrue(result is ImportResult.Failure)
        assertEquals("validate", (result as ImportResult.Failure).stage)
    }

    private suspend fun importDemoPlugin(): ImportResult {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val stream = assets.open(ASSET_NAME)
        return runtime.importPlugin(stream, ASSET_NAME)
    }

    private companion object {
        const val PLUGIN_ID = "com.zetaforge.plugins.retrofitdemo"
        const val ASSET_NAME = "retrofit-demo.zeta"
    }
}
