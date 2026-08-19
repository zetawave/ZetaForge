package com.zetaforge.runtime

import com.zetaforge.runtime.manifest.ZetaManifest
import com.zetaforge.runtime.permission.PermissionRules
import com.zetaforge.runtime.permission.PermissionState
import com.zetaforge.runtime.permission.SpecialAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission decision logic, tested without a device.
 *
 * These cases are the whole contract the UI depends on: what can be asked, what
 * is already granted, what Android will never grant because the Host APK does
 * not declare it, and what simply does not apply to the running API level.
 */
class PermissionRulesTest {

    @Test
    fun `granted permissions need no action`() {
        val plan = PermissionRules.evaluate(
            manifest = manifest(),
            sdkInt = 34,
            declaredByHost = setOf(MEDIA, INTERNET),
            granted = setOf(MEDIA, INTERNET),
            permanentlyDenied = emptySet(),
            specialAccessGranted = emptySet(),
        )

        assertTrue(plan.isSatisfied)
        assertTrue(plan.requestable.isEmpty())
        // The optional permission is not declared by this Host, which is fine:
        // optional means "run anyway", and the plan reports it as such.
        assertTrue(plan.undeclared.all { it.optional })
    }

    @Test
    fun `a declared but ungranted permission is requestable`() {
        val plan = PermissionRules.evaluate(
            manifest = manifest(),
            sdkInt = 34,
            declaredByHost = setOf(MEDIA, INTERNET),
            granted = setOf(INTERNET),
            permanentlyDenied = emptySet(),
            specialAccessGranted = emptySet(),
        )

        assertFalse(plan.isSatisfied)
        assertTrue(plan.isActionable)
        assertEquals(listOf(MEDIA), plan.requestable.map { it.name })
    }

    @Test
    fun `a permission missing from the Host manifest can never be granted`() {
        val plan = PermissionRules.evaluate(
            manifest = manifest(),
            sdkInt = 34,
            declaredByHost = setOf(INTERNET),
            granted = setOf(INTERNET),
            permanentlyDenied = emptySet(),
            specialAccessGranted = emptySet(),
        )

        assertFalse(plan.isSatisfied)
        assertEquals(listOf(MEDIA), plan.undeclared.filterNot { it.optional }.map { it.name })
        assertEquals(
            PermissionState.NOT_DECLARED_BY_HOST,
            plan.permissions.first { it.requirement.name == MEDIA }.state,
        )
    }

    @Test
    fun `a permanently denied permission is reported separately from a fresh one`() {
        val plan = PermissionRules.evaluate(
            manifest = manifest(),
            sdkInt = 34,
            declaredByHost = setOf(MEDIA, INTERNET),
            granted = setOf(INTERNET),
            permanentlyDenied = setOf(MEDIA),
            specialAccessGranted = emptySet(),
        )

        assertTrue(plan.requestable.isEmpty())
        assertEquals(listOf(MEDIA), plan.permanentlyDenied.map { it.name })
        assertFalse(plan.isSatisfied)
    }

    @Test
    fun `permissions outside the api range do not apply`() {
        // LEGACY is declared with maxSdk 32, so on API 34 it is irrelevant.
        val plan = PermissionRules.evaluate(
            manifest = manifest(),
            sdkInt = 34,
            declaredByHost = setOf(MEDIA, INTERNET),
            granted = setOf(MEDIA, INTERNET),
            permanentlyDenied = emptySet(),
            specialAccessGranted = emptySet(),
        )

        assertEquals(
            PermissionState.NOT_APPLICABLE,
            plan.permissions.first { it.requirement.name == LEGACY }.state,
        )
        assertTrue(plan.isSatisfied)
    }

    @Test
    fun `on an older api the legacy permission is the one that matters`() {
        val plan = PermissionRules.evaluate(
            manifest = manifest(),
            sdkInt = 30,
            declaredByHost = setOf(LEGACY, MEDIA, INTERNET),
            granted = setOf(INTERNET),
            permanentlyDenied = emptySet(),
            specialAccessGranted = emptySet(),
        )

        assertEquals(listOf(LEGACY), plan.requestable.map { it.name })
        assertEquals(
            PermissionState.NOT_APPLICABLE,
            plan.permissions.first { it.requirement.name == MEDIA }.state,
        )
    }

    @Test
    fun `an optional permission does not block execution`() {
        val plan = PermissionRules.evaluate(
            manifest = manifest(),
            sdkInt = 34,
            declaredByHost = setOf(MEDIA, INTERNET, OPTIONAL),
            granted = setOf(MEDIA, INTERNET),
            permanentlyDenied = emptySet(),
            specialAccessGranted = emptySet(),
        )

        assertTrue(plan.isSatisfied)
        assertEquals(listOf(OPTIONAL), plan.missingOptional.map { it.name })
    }

    @Test
    fun `missing special access blocks and is reported`() {
        val plan = PermissionRules.evaluate(
            manifest = manifest(withSpecialAccess = true),
            sdkInt = 34,
            declaredByHost = setOf(MEDIA, INTERNET),
            granted = setOf(MEDIA, INTERNET),
            permanentlyDenied = emptySet(),
            specialAccessGranted = emptySet(),
        )

        assertFalse(plan.isSatisfied)
        assertEquals(listOf(SpecialAccess.ALL_FILES_ACCESS), plan.missingSpecialAccess.map { it.access })
    }

    @Test
    fun `granted special access satisfies the plan`() {
        val plan = PermissionRules.evaluate(
            manifest = manifest(withSpecialAccess = true),
            sdkInt = 34,
            declaredByHost = setOf(MEDIA, INTERNET),
            granted = setOf(MEDIA, INTERNET),
            permanentlyDenied = emptySet(),
            specialAccessGranted = setOf(SpecialAccess.ALL_FILES_ACCESS),
        )

        assertTrue(plan.isSatisfied)
    }

    @Test
    fun `v1 manifests with plain string permissions still parse`() {
        val manifest = ZetaManifest.parse(
            """
            {
              "formatVersion": 1,
              "pluginId": "com.example.legacy",
              "name": "Legacy",
              "version": "1.0.0",
              "entryPoint": "com.example.legacy.Plugin",
              "minHostApi": 1, "maxHostApi": 1,
              "permissions": ["android.permission.INTERNET"],
              "code": { "dex": [ { "path": "dex/classes.dex", "size": 1, "sha256": "", "dexVersion": "035" } ] }
            }
            """.trimIndent()
        )

        assertEquals(listOf(INTERNET), manifest.permissionNames)
        assertFalse(manifest.permissions.first().optional)
    }

    private fun manifest(withSpecialAccess: Boolean = false): ZetaManifest = ZetaManifest.parse(
        """
        {
          "formatVersion": 2,
          "pluginId": "com.zetaforge.plugins.filesdemo",
          "name": "Files Demo",
          "version": "1.0.0",
          "entryPoint": "com.zetaforge.plugins.filesdemo.FilesDemoPlugin",
          "minHostApi": 1,
          "maxHostApi": 1,
          "minSdk": 26,
          "permissions": [
            { "name": "$INTERNET", "reason": "talk to the server" },
            { "name": "$MEDIA", "reason": "count images", "minSdk": 33 },
            { "name": "$LEGACY", "reason": "count images", "maxSdk": 32 },
            { "name": "$OPTIONAL", "reason": "photo location", "optional": true, "minSdk": 29 }
          ],
          ${if (withSpecialAccess) "\"specialAccess\": [ { \"id\": \"allFilesAccess\", \"reason\": \"write anywhere\" } ]," else ""}
          "code": { "dex": [ { "path": "dex/classes.dex", "size": 1, "sha256": "", "dexVersion": "038" } ] }
        }
        """.trimIndent()
    )

    private companion object {
        const val INTERNET = "android.permission.INTERNET"
        const val MEDIA = "android.permission.READ_MEDIA_IMAGES"
        const val LEGACY = "android.permission.READ_EXTERNAL_STORAGE"
        const val OPTIONAL = "android.permission.ACCESS_MEDIA_LOCATION"
    }
}
