package com.zetaforge.app

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The Host ships English (default) and Italian.
 *
 * The device locale cannot be changed from an instrumented test, so the strings
 * are resolved through a locale-overridden `Context` - which is exactly what the
 * framework does at run time when the user switches language.
 */
@RunWith(AndroidJUnit4::class)
class LocalizationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun localized(locale: Locale) = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(locale) }
    )

    @Test
    fun englishIsTheDefault() {
        val english = localized(Locale.ENGLISH)

        assertEquals("Import plugin", english.getString(R.string.action_import_plugin))
        assertEquals("Installed plugins", english.getString(R.string.plugins_title))
        assertEquals("View code", english.getString(R.string.action_view_code))
    }

    @Test
    fun italianIsTranslated() {
        val italian = localized(Locale.ITALIAN)

        assertEquals("Importa plugin", italian.getString(R.string.action_import_plugin))
        assertEquals("Plugin installati", italian.getString(R.string.plugins_title))
        assertEquals("Vedi codice", italian.getString(R.string.action_view_code))
        assertEquals("Permessi", italian.getString(R.string.permissions_title))
    }

    @Test
    fun formattedStringsKeepTheirPlaceholders() {
        val italian = localized(Locale.ITALIAN)
        val english = localized(Locale.ENGLISH)

        val itBanner = italian.getString(R.string.banner_installed, "Files Demo", "1.0.0")
        val enBanner = english.getString(R.string.banner_installed, "Files Demo", "1.0.0")

        assertTrue(itBanner.contains("Files Demo") && itBanner.contains("1.0.0"))
        assertTrue(enBanner.contains("Files Demo") && enBanner.contains("1.0.0"))
        assertNotEquals(itBanner, enBanner)
    }

    @Test
    fun everyUserFacingStringHasAnItalianTranslation() {
        // The app name is intentionally identical in both languages; everything
        // else must actually differ or the translation is missing.
        val english = localized(Locale.ENGLISH)
        val italian = localized(Locale.ITALIAN)

        val untranslated = USER_FACING.filter { id ->
            english.getString(id) == italian.getString(id)
        }.map { context.resources.getResourceEntryName(it) }

        assertTrue("not translated: $untranslated", untranslated.isEmpty())
    }

    private companion object {
        val USER_FACING = listOf(
            R.string.app_tagline,
            R.string.action_import_plugin,
            R.string.action_start,
            R.string.action_details,
            R.string.action_view_code,
            R.string.plugins_title,
            R.string.plugins_empty_title,
            R.string.plugins_empty_body,
            R.string.permissions_title,
            R.string.permissions_explain_body,
            R.string.permissions_denied_body,
            R.string.special_access_title,
            R.string.code_subtitle,
            R.string.details_verification,
            R.string.details_last_result,
        )
    }
}
