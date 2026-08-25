package com.zetaforge.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zetaforge.app.BuildConfig
import com.zetaforge.app.R
import com.zetaforge.app.schedule.DeviceConditions
import com.zetaforge.app.ui.AppPreferences
import com.zetaforge.app.ui.HostUiState
import com.zetaforge.app.ui.ReadinessItem
import com.zetaforge.app.ui.ScheduleFormatter
import com.zetaforge.app.ui.components.ReadinessPanel
import com.zetaforge.app.ui.components.SectionHeader
import com.zetaforge.sdk.ZetaLogLevel
import com.zetaforge.sdk.ZetaSdk

/**
 * The three screens behind the menu: settings, help and diagnostics.
 *
 * They share a shape — a scrolling column of cards, centred and width-capped —
 * so they behave identically on a phone, in landscape and on a tablet without
 * any of them needing its own layout code.
 */

@Composable
fun AppSettingsScreen(
    state: HostUiState,
    onTheme: (AppPreferences.Theme) -> Unit,
    onLogLevel: (ZetaLogLevel) -> Unit,
    onNotifyResults: (Boolean) -> Unit,
    onCheckUpdatesOnLaunch: (Boolean) -> Unit,
    onReplayOnboarding: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(modifier) {
        item {
            Card(stringResource(R.string.app_settings_appearance)) {
                AppPreferences.Theme.entries.forEach { theme ->
                    ChoiceRow(
                        label = stringResource(
                            when (theme) {
                                AppPreferences.Theme.SYSTEM -> R.string.app_settings_theme_system
                                AppPreferences.Theme.LIGHT -> R.string.app_settings_theme_light
                                AppPreferences.Theme.DARK -> R.string.app_settings_theme_dark
                            }
                        ),
                        selected = state.preferences.theme == theme,
                        onClick = { onTheme(theme) },
                    )
                }
            }
        }

        item {
            Card(stringResource(R.string.app_settings_notifications)) {
                SwitchRow(
                    title = stringResource(R.string.app_settings_notify_results),
                    help = stringResource(R.string.app_settings_notify_results_help),
                    checked = state.preferences.notifyManualResults,
                    onCheckedChange = onNotifyResults,
                )
            }
        }

        item {
            Card(stringResource(R.string.app_settings_updates)) {
                SwitchRow(
                    title = stringResource(R.string.app_settings_check_updates),
                    help = stringResource(R.string.app_settings_check_updates_help),
                    checked = state.preferences.checkUpdatesOnLaunch,
                    onCheckedChange = onCheckUpdatesOnLaunch,
                )
            }
        }

        item {
            Card(stringResource(R.string.app_settings_logs)) {
                Text(
                    stringResource(R.string.app_settings_log_level),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                ZetaLogLevel.entries.forEach { level ->
                    ChoiceRow(
                        label = level.name,
                        selected = state.preferences.minLogLevel == level,
                        onClick = { onLogLevel(level) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onClearLogs) {
                    Text(stringResource(R.string.app_settings_clear_logs))
                }
            }
        }

        item {
            Card(null) {
                TextButton(onClick = onReplayOnboarding) {
                    Text(stringResource(R.string.app_settings_onboarding_again))
                }
            }
        }
    }
}

@Composable
fun HelpScreen(modifier: Modifier = Modifier) {
    val topics = listOf(
        R.string.help_what_title to R.string.help_what_body,
        R.string.help_install_title to R.string.help_install_body,
        R.string.help_settings_title to R.string.help_settings_body,
        R.string.help_schedule_title to R.string.help_schedule_body,
        R.string.help_permissions_title to R.string.help_permissions_body,
        R.string.help_trouble_title to R.string.help_trouble_body,
        R.string.help_write_title to R.string.help_write_body,
    )
    ScreenScaffold(modifier) {
        items(topics.size) { index ->
            val (title, body) = topics[index]
            Card(stringResource(title)) {
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(
    state: HostUiState,
    onFix: (ReadinessItem.Fix) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    ScreenScaffold(modifier) {
        item {
            SectionHeader(
                title = stringResource(R.string.diagnostics_title),
                subtitle = stringResource(R.string.diagnostics_subtitle),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        state.readiness?.let { readiness ->
            item { ReadinessPanel(readiness = readiness, onFix = onFix) }
        }

        item {
            Card(stringResource(R.string.diagnostics_system)) {
                InfoRow(stringResource(R.string.diagnostics_device), "${Build.MANUFACTURER} ${Build.MODEL}")
                InfoRow(stringResource(R.string.diagnostics_android), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                InfoRow(
                    stringResource(R.string.diagnostics_battery),
                    batteryLine(context),
                )
                InfoRow(
                    stringResource(R.string.diagnostics_network),
                    stringResource(
                        if (DeviceConditions.isUnmetered(context)) R.string.diagnostics_network_unmetered
                        else R.string.diagnostics_network_metered
                    ),
                )
                InfoRow(stringResource(R.string.diagnostics_storage), freeSpace())
            }
        }

        item {
            Card(stringResource(R.string.diagnostics_app)) {
                InfoRow(stringResource(R.string.diagnostics_app_version), BuildConfig.VERSION_NAME)
                InfoRow(stringResource(R.string.diagnostics_host_api), ZetaSdk.HOST_API_VERSION.toString())
                InfoRow(stringResource(R.string.diagnostics_plugins), state.plugins.size.toString())
            }
        }

        if (state.schedules.values.any { it.isAutomatic }) {
            item {
                Card(stringResource(R.string.diagnostics_schedules)) {
                    state.schedules.values.filter { it.isAutomatic }.forEach { schedule ->
                        val name = state.plugins.firstOrNull { it.id == schedule.pluginId }
                            ?.installed?.displayName ?: schedule.pluginId
                        InfoRow(name, ScheduleFormatter.summary(context, schedule))
                        InfoRow(
                            stringResource(R.string.schedule_next_run),
                            ScheduleFormatter.nextRun(context, schedule),
                        )
                        InfoRow(
                            stringResource(R.string.schedule_last_run),
                            ScheduleFormatter.lastRun(context, schedule),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        item {
            Button(
                onClick = { copyReport(context, state) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diagnostics_copy))
            }
        }
    }
}

@Composable
fun AboutScreen(
    update: HostUiState.UpdateState,
    onCheckUpdates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(modifier) {
        item {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.zeta_wordmark),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                    modifier = Modifier.fillMaxWidth(0.45f),
                )
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
                Text(
                    stringResource(
                        R.string.about_host_api,
                        ZetaSdk.HOST_API_VERSION,
                        ZetaSdk.MANIFEST_FORMAT_VERSION,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Card(null) {
                Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Card(stringResource(R.string.app_settings_updates)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onCheckUpdates, enabled = !update.checking) {
                        Text(
                            stringResource(
                                if (update.checking) R.string.update_checking else R.string.update_action_check,
                            ),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    // The card on the plugin list is where an available update
                    // is offered; here there is only ever a sentence to report.
                    val summary = update.message
                        ?: update.available?.let {
                            stringResource(R.string.update_subtitle, it.version, "")
                        }
                    if (summary != null) {
                        Text(
                            summary.trim().trimEnd('·', ' '),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        item {
            Card(stringResource(R.string.about_trust_title)) {
                Text(
                    stringResource(R.string.about_trust_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// -- shared pieces ----------------------------------------------------------

@Composable
private fun ScreenScaffold(
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun Card(title: String?, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SwitchRow(title: String, help: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            help?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun batteryLine(context: Context): String {
    val percent = DeviceConditions.batteryPercent(context)
    val charging = context.getString(
        if (DeviceConditions.isCharging(context)) R.string.diagnostics_charging
        else R.string.diagnostics_not_charging
    )
    return if (percent >= 0) "$percent% · $charging" else charging
}

private fun freeSpace(): String {
    val stat = StatFs(Environment.getDataDirectory().path)
    val gb = stat.availableBytes / 1_000_000_000.0
    return String.format("%.1f GB", gb)
}

/**
 * A plain-text report the user can paste into a bug report. Deliberately the
 * same facts the screen shows, so nobody has to transcribe them by hand.
 */
private fun copyReport(context: Context, state: HostUiState) {
    val report = buildString {
        appendLine("ZetaForge ${BuildConfig.VERSION_NAME} (Host API ${ZetaSdk.HOST_API_VERSION})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("battery: ${batteryLine(context)}")
        appendLine("network: ${if (DeviceConditions.isUnmetered(context)) "unmetered" else "metered/offline"}")
        appendLine("free space: ${freeSpace()}")
        state.readiness?.let {
            appendLine("notifications: ${it.notificationsEnabled}")
            appendLine("battery unrestricted: ${it.batteryUnrestricted}")
            appendLine("exact alarms: ${it.exactAlarmsAllowed}")
        }
        appendLine()
        appendLine("plugins (${state.plugins.size}):")
        state.plugins.forEach { entry ->
            appendLine("  ${entry.installed.displayName} ${entry.installed.version} · ${entry.id}")
            val schedule = state.schedules[entry.id]
            if (schedule != null && schedule.isAutomatic) {
                appendLine("    schedule: ${ScheduleFormatter.summary(context, schedule)}")
                appendLine("    next: ${ScheduleFormatter.nextRun(context, schedule)}")
                appendLine("    last: ${ScheduleFormatter.lastRun(context, schedule)}")
            }
        }
    }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("ZetaForge diagnostics", report))
}
