package com.zetaforge.app.ui

import android.content.Context
import android.content.SharedPreferences
import com.zetaforge.sdk.ZetaLogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The handful of choices that belong to the app rather than to a plugin.
 *
 * Deliberately small and deliberately not a database: these are read on the
 * first frame, so they have to be available synchronously, and there will never
 * be enough of them to justify anything more.
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    data class Settings(
        val theme: Theme = Theme.SYSTEM,
        val minLogLevel: ZetaLogLevel = ZetaLogLevel.DEBUG,
        /** Whether a manually started run also notifies when it ends. */
        val notifyManualResults: Boolean = true,
        val onboardingDone: Boolean = false,
    )

    enum class Theme { SYSTEM, LIGHT, DARK }

    private fun read() = Settings(
        theme = enumOf(prefs.getString(KEY_THEME, null), Theme.SYSTEM),
        minLogLevel = enumOf(prefs.getString(KEY_LOG_LEVEL, null), ZetaLogLevel.DEBUG),
        notifyManualResults = prefs.getBoolean(KEY_NOTIFY_MANUAL, true),
        onboardingDone = prefs.getBoolean(KEY_ONBOARDING, false),
    )

    fun setTheme(theme: Theme) = update { putString(KEY_THEME, theme.name) }

    fun setMinLogLevel(level: ZetaLogLevel) = update { putString(KEY_LOG_LEVEL, level.name) }

    fun setNotifyManualResults(enabled: Boolean) = update { putBoolean(KEY_NOTIFY_MANUAL, enabled) }

    fun setOnboardingDone(done: Boolean) = update { putBoolean(KEY_ONBOARDING, done) }

    private inline fun update(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private companion object {
        const val FILE = "zetaforge.app"
        const val KEY_THEME = "theme"
        const val KEY_LOG_LEVEL = "logLevel"
        const val KEY_NOTIFY_MANUAL = "notifyManualResults"
        const val KEY_ONBOARDING = "onboardingDone"
    }
}
