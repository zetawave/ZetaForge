package com.zetaforge.runtime

import com.zetaforge.runtime.manifest.ZetaManifest
import com.zetaforge.runtime.manifest.ZetaManifestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZetaManifestTest {

    @Test
    fun `parses a valid manifest`() {
        val manifest = ZetaManifest.parse(validManifest())

        assertEquals(1, manifest.formatVersion)
        assertEquals("com.zetaforge.plugins.retrofitdemo", manifest.pluginId)
        assertEquals("com.zetaforge.plugins.retrofitdemo.RetrofitDemoPlugin", manifest.entryPoint)
        assertEquals("0.1.0", manifest.version)
        assertEquals(listOf("android.permission.INTERNET"), manifest.permissions)
        assertEquals(1, manifest.dex.size)
        assertEquals("dex/classes.dex", manifest.dex.first().path)
        assertNull(manifest.signature)
        assertTrue(manifest.isCompatibleWith(1))
    }

    @Test
    fun `rejects malformed json`() {
        val error = assertThrows { ZetaManifest.parse("{ not json") }
        assertTrue(error.message!!.contains("not valid JSON"))
    }

    @Test
    fun `rejects a missing entry point`() {
        val json = validManifest().replace("\"entryPoint\": \"com.zetaforge.plugins.retrofitdemo.RetrofitDemoPlugin\",", "")
        val error = assertThrows { ZetaManifest.parse(json) }
        assertTrue(error.message!!.contains("entryPoint"))
    }

    @Test
    fun `rejects an entry point that is not a class name`() {
        val json = validManifest().replace("com.zetaforge.plugins.retrofitdemo.RetrofitDemoPlugin", "NotQualified")
        val error = assertThrows { ZetaManifest.parse(json) }
        assertTrue(error.message!!.contains("entryPoint"))
    }

    @Test
    fun `rejects an invalid version`() {
        val json = validManifest().replace("\"version\": \"0.1.0\"", "\"version\": \"one\"")
        val error = assertThrows { ZetaManifest.parse(json) }
        assertTrue(error.message!!.contains("version"))
    }

    @Test
    fun `rejects a future package format`() {
        val json = validManifest().replace("\"formatVersion\": 1", "\"formatVersion\": 99")
        val error = assertThrows { ZetaManifest.parse(json) }
        assertTrue(error.message!!.contains("newer than"))
    }

    @Test
    fun `rejects a manifest without dex`() {
        val json = validManifest().replace("\"path\": \"dex/classes.dex\"", "\"path\": \"\"")
        val error = assertThrows { ZetaManifest.parse(json) }
        assertTrue(error.message!!.contains("path"))
    }

    @Test
    fun `rejects an inverted api range`() {
        val json = validManifest().replace("\"minHostApi\": 1", "\"minHostApi\": 5")
        val error = assertThrows { ZetaManifest.parse(json) }
        assertTrue(error.message!!.contains("greater than"))
    }

    @Test
    fun `reports incompatibility with an out of range host`() {
        val manifest = ZetaManifest.parse(validManifest())
        assertFalse(manifest.isCompatibleWith(2))
        assertFalse(manifest.isCompatibleWith(0))
    }

    private fun assertThrows(block: () -> Unit): ZetaManifestException = try {
        block()
        throw AssertionError("Expected ZetaManifestException")
    } catch (e: ZetaManifestException) {
        e
    }

    private fun validManifest(): String = """
        {
          "formatVersion": 1,
          "pluginId": "com.zetaforge.plugins.retrofitdemo",
          "name": "Retrofit Demo",
          "version": "0.1.0",
          "description": "demo",
          "author": "ZetaForge",
          "entryPoint": "com.zetaforge.plugins.retrofitdemo.RetrofitDemoPlugin",
          "minHostApi": 1,
          "maxHostApi": 1,
          "minSdk": 26,
          "permissions": ["android.permission.INTERNET"],
          "capabilities": ["network.http"],
          "dependencies": { "bundled": ["com.squareup.retrofit2:retrofit:2.11.0"], "hostProvided": [] },
          "code": { "dex": [ { "path": "dex/classes.dex", "size": 10, "sha256": "", "dexVersion": "035" } ] },
          "signature": null
        }
    """.trimIndent()
}
