package com.zetaforge.app.schedule

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.zetaforge.app.R
import com.zetaforge.runtime.schedule.Schedule

/**
 * The device conditions a schedule can insist on, and the state of the system
 * settings that decide whether scheduling works at all.
 *
 * Two different jobs, kept together because they answer the same question from
 * two sides: *can this run happen?*
 */
object DeviceConditions {

    /**
     * The first condition a schedule asked for that the device does not meet.
     * @return a sentence for the user, or null when the run may proceed.
     */
    fun unmet(context: Context, schedule: Schedule): String? {
        if (schedule.requiresCharging && !isCharging(context)) {
            return context.getString(R.string.condition_needs_charging)
        }
        if (schedule.requiresBatteryNotLow && isBatteryLow(context)) {
            return context.getString(R.string.condition_battery_low)
        }
        if (schedule.requiresUnmeteredNetwork && !isUnmetered(context)) {
            return context.getString(R.string.condition_needs_wifi)
        }
        return null
    }

    fun isCharging(context: Context): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        return when (status.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }
    }

    fun batteryPercent(context: Context): Int {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    fun isBatteryLow(context: Context): Boolean {
        val percent = batteryPercent(context)
        return percent in 0 until LOW_BATTERY_PERCENT && !isCharging(context)
    }

    fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // -- system readiness ------------------------------------------------------

    /**
     * Battery optimisation is the single biggest reason a scheduled run does not
     * happen on a real phone: an optimised app is put in a bucket where alarms
     * are deferred for hours and network is cut. Asking to leave that bucket is
     * the one system setting worth insisting on.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Takes the user exactly where the switch is, not to a settings home screen.
     * The direct request dialog is used where allowed; the app's own battery page
     * is the fallback, because it is one tap from the switch.
     */
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))

    @Suppress("BatteryLife") // Deliberate: a scheduler that Doze defers is broken.
    fun requestIgnoreBatteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))

    /** Some manufacturers add a second, stricter layer on top of Android's. */
    fun manufacturerNeedsExtraStep(): Boolean {
        val brand = Build.MANUFACTURER.lowercase()
        return AGGRESSIVE_BRANDS.any { brand.contains(it) }
    }

    private const val LOW_BATTERY_PERCENT = 20

    /**
     * Vendors known to kill background work beyond what Android does. The list is
     * only used to show an extra hint, never to change behaviour.
     */
    private val AGGRESSIVE_BRANDS = listOf(
        "xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme",
        "oneplus", "vivo", "iqoo", "samsung", "meizu", "asus", "tecno", "infinix",
    )
}
