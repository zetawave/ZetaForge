package com.zetaforge.app

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.zetaforge.runtime.ImportResult
import com.zetaforge.runtime.ZetaPluginRuntime
import com.zetaforge.runtime.permission.PermissionCoordinator
import com.zetaforge.runtime.permission.PermissionState
import com.zetaforge.sdk.PluginResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The permission story, end to end, with a real plugin that needs a run-time
 * permission (`files-demo` reads the media library).
 *
 * What is proven here:
 *  - a plugin declaring a permission is described correctly by the runtime;
 *  - with the permission granted, the plugin runs and really touches MediaStore;
 *  - with a permission that is *not* granted, execution is blocked before the
 *    plugin code runs, with a structured failure rather than a crash or a
 *    silent misbehaviour;
 *  - a permission the Host APK does not declare is reported as such, because
 *    Android can never grant it at run time.
 *
 * The tests run with the headless (denying) gateway: they check the decisions,
 * not the dialogs, which need a foreground Activity.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PermissionGateTest {

    /**
     * Granted for the whole class: `pm grant` at run time would restart the
     * process and break the run, so the framework does it before we start.
     */
    @get:Rule
    val mediaPermission: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) {
            GrantPermissionRule.grant("android.permission.READ_MEDIA_IMAGES")
        } else {
            GrantPermissionRule.grant("android.permission.READ_EXTERNAL_STORAGE")
        }

    private lateinit var runtime: ZetaPluginRuntime

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun setUp() {
        File(context.filesDir, "zetaforge").deleteRecursively()
        runtime = ZetaPluginRuntime(context)
        runBlocking { runtime.refresh() }
    }

    @Test
    fun test01_manifestCarriesPermissionsWithReasons() = runBlocking {
        val entry = importFilesDemo()

        val permissions = entry.installed.manifest.permissions
        assertTrue("no permissions declared", permissions.isNotEmpty())
        val media = permissions.first { it.name.endsWith("READ_MEDIA_IMAGES") }
        assertTrue("reason is what the user reads", media.reason.isNotBlank())
        assertFalse(media.optional)
        assertTrue(
            "optional permission should be marked optional",
            permissions.first { it.name.endsWith("ACCESS_MEDIA_LOCATION") }.optional,
        )
    }

    @Test
    fun test02_hostDeclaresEverythingThePluginAsks() = runBlocking {
        val entry = importFilesDemo()

        val plan = runtime.inspectPermissions(entry.id)!!
        val undeclared = plan.permissions
            .filter { it.state == PermissionState.NOT_DECLARED_BY_HOST }
            .map { it.requirement.name }

        assertTrue(
            "the Host superset is missing: $undeclared - add them to zetaforge.permissions",
            undeclared.isEmpty(),
        )
    }

    @Test
    fun test03_pluginRunsWhenThePermissionIsGranted() = runBlocking {
        val entry = importFilesDemo()

        val result = runtime.execute(entry.id)

        assertTrue("expected success, got $result", result is PluginResult.Success)
        val success = result as PluginResult.Success
        // MediaStore answered: the count is present (it may legitimately be 0).
        assertTrue(success.data.containsKey("imageCount"))
        assertEquals(context.packageName, success.data["hostPackage"])
        assertTrue(File(success.data["report"]!!).isFile)
    }

    @Test
    fun test04_executionIsBlockedWhenAPermissionIsMissing() = runBlocking {
        // Same DEX, but the manifest asks for a permission the device has not
        // granted: the runtime must stop before the plugin code runs.
        val repacked = repackageWithPermission("android.permission.CAMERA")
        assumeTrue(
            "CAMERA is unexpectedly granted on this device",
            !runtime.permissionInspector.isGranted("android.permission.CAMERA"),
        )

        val imported = runtime.importPlugin(repacked.inputStream(), repacked.name)
        assertTrue(imported is ImportResult.Success)
        val entry = (imported as ImportResult.Success).entry

        val result = runtime.execute(entry.id)

        assertTrue("expected failure, got $result", result is PluginResult.Failure)
        val failure = result as PluginResult.Failure
        assertEquals(PermissionCoordinator.ERROR_DENIED, failure.errorCode)
        assertTrue(failure.data["missing"].orEmpty().contains("CAMERA"))

        // Nothing ran: no report file was produced by this execution.
        assertTrue(runtime.logger.records.value.none { it.message.contains("MediaStore:") })
    }

    @Test
    fun test05_permissionNotDeclaredByTheHostIsReportedAsSuch() = runBlocking {
        // SEND_SMS is deliberately absent from zetaforge.permissions: Android
        // would refuse it without any dialog, so the runtime must say why.
        val repacked = repackageWithPermission("android.permission.SEND_SMS")

        val imported = runtime.importPlugin(repacked.inputStream(), repacked.name)
        assertTrue(imported is ImportResult.Success)
        val entry = (imported as ImportResult.Success).entry

        val result = runtime.execute(entry.id)

        assertTrue(result is PluginResult.Failure)
        val failure = result as PluginResult.Failure
        assertEquals(PermissionCoordinator.ERROR_NOT_DECLARED, failure.errorCode)
        assertTrue(failure.message.contains("SEND_SMS"))
    }

    // -- helpers ------------------------------------------------------------

    private suspend fun importFilesDemo(): com.zetaforge.runtime.PluginEntry {
        val result = runtime.importPlugin(testContext.assets.open(ASSET), ASSET)
        assertTrue("import failed: $result", result is ImportResult.Success)
        return (result as ImportResult.Success).entry
    }

    /**
     * Rebuilds the demo package with a different permission and plugin id,
     * keeping the very same DEX. This exercises the gate without needing a
     * second plugin module for every scenario.
     */
    private fun repackageWithPermission(permission: String): File {
        val source = File(context.cacheDir, "source-$ASSET").apply {
            outputStream().use { out -> testContext.assets.open(ASSET).use { it.copyTo(out) } }
        }
        val target = File(context.cacheDir, "gate-" + permission.substringAfterLast('.') + ".zeta")

        ZipFile(source).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val patched = manifest
                .replace("\"com.zetaforge.plugins.filesdemo\"", "\"com.zetaforge.test." + permission.substringAfterLast('.').lowercase() + "\"")
                .replace(
                    Regex("\"permissions\"\\s*:\\s*\\[[^\\]]*]"),
                    "\"permissions\": [ { \"name\": \"" + permission + "\", \"reason\": \"gate test\" } ]",
                )

            ZipOutputStream(target.outputStream().buffered()).use { out ->
                out.putNextEntry(ZipEntry("manifest.json"))
                out.write(patched.toByteArray())
                out.closeEntry()
                zip.entries().asSequence().filter { it.name != "manifest.json" }.forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { input -> input.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        return target
    }

    private companion object {
        const val ASSET = "files-demo.zeta"
    }
}
