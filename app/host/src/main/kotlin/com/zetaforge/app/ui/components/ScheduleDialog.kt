package com.zetaforge.app.ui.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zetaforge.app.R
import com.zetaforge.app.ui.HostUiState
import com.zetaforge.app.ui.ScheduleFormatter
import com.zetaforge.runtime.schedule.Schedule
import java.util.Calendar

/**
 * Where the user decides when a plugin runs without them.
 *
 * The whole panel is built around one promise: **the preview at the bottom is
 * the truth**. It is computed by the same function the alarm uses, so what the
 * user reads before saving is exactly what the system will do — the alternative,
 * a form that describes its own intentions, is how schedulers lose trust.
 */
@Composable
fun ScheduleDialog(
    state: HostUiState.ScheduleState,
    onEdit: ((Schedule) -> Schedule) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val draft = state.draft
    val next = draft.nextRunAfter(System.currentTimeMillis())

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
        title = { Text(stringResource(R.string.schedule_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.schedule_subtitle, state.pluginName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // -- the master switch --------------------------------------
                ToggleRow(
                    title = stringResource(R.string.schedule_enabled),
                    subtitle = if (!draft.enabled) stringResource(R.string.schedule_disabled_hint) else null,
                    checked = draft.enabled,
                    onCheckedChange = { on ->
                        onEdit { s ->
                            // Turning it on with nothing chosen would save a
                            // schedule that never fires; daily is the sane default.
                            if (on && s.mode == Schedule.Mode.MANUAL) s.copy(enabled = true, mode = Schedule.Mode.DAILY)
                            else s.copy(enabled = on)
                        }
                    },
                )

                AnimatedVisibility(visible = draft.enabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Spacer(Modifier.height(8.dp))
                        FieldLabel(stringResource(R.string.schedule_mode))
                        ModePicker(draft.mode) { mode -> onEdit { it.copy(mode = mode) } }

                        when (draft.mode) {
                            Schedule.Mode.ONCE -> OnceFields(draft, onEdit)
                            Schedule.Mode.INTERVAL -> IntervalFields(draft, onEdit)
                            Schedule.Mode.DAILY -> TimeField(draft, onEdit)
                            Schedule.Mode.WEEKLY -> {
                                TimeField(draft, onEdit)
                                Spacer(Modifier.height(10.dp))
                                FieldLabel(stringResource(R.string.schedule_days))
                                DayPicker(draft.daysOfWeek) { days -> onEdit { it.copy(daysOfWeek = days) } }
                                if (draft.daysOfWeek.isEmpty()) {
                                    Hint(stringResource(R.string.schedule_days_none), warning = true)
                                }
                            }

                            Schedule.Mode.MANUAL -> Unit
                        }

                        Spacer(Modifier.height(14.dp))
                        FieldLabel(stringResource(R.string.schedule_conditions))
                        ConditionRow(
                            icon = { Icon(Icons.Outlined.BatteryChargingFull, null, Modifier.size(18.dp)) },
                            title = stringResource(R.string.schedule_requires_charging),
                            help = stringResource(R.string.schedule_requires_charging_help),
                            checked = draft.requiresCharging,
                            onCheckedChange = { v -> onEdit { it.copy(requiresCharging = v) } },
                        )
                        ConditionRow(
                            icon = { Icon(Icons.Outlined.Wifi, null, Modifier.size(18.dp)) },
                            title = stringResource(R.string.schedule_requires_wifi),
                            help = stringResource(R.string.schedule_requires_wifi_help),
                            checked = draft.requiresUnmeteredNetwork,
                            onCheckedChange = { v -> onEdit { it.copy(requiresUnmeteredNetwork = v) } },
                        )
                        ConditionRow(
                            icon = null,
                            title = stringResource(R.string.schedule_requires_battery),
                            help = null,
                            checked = draft.requiresBatteryNotLow,
                            onCheckedChange = { v -> onEdit { it.copy(requiresBatteryNotLow = v) } },
                        )

                        Spacer(Modifier.height(6.dp))
                        ConditionRow(
                            icon = null,
                            title = stringResource(R.string.schedule_exact),
                            help = stringResource(R.string.schedule_exact_help),
                            checked = draft.exact,
                            onCheckedChange = { v -> onEdit { it.copy(exact = v) } },
                        )
                        if (draft.exact && !state.exactAllowed) {
                            Hint(stringResource(R.string.schedule_exact_missing), warning = true)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Preview(
                    next = next,
                    lastRun = ScheduleFormatter.lastRun(context, draft),
                    runCount = draft.runCount,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = !draft.enabled || draft.isComplete,
            ) {
                Text(
                    stringResource(
                        if (draft.enabled) R.string.schedule_save else R.string.schedule_turn_off
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// -- pieces -----------------------------------------------------------------

@Composable
private fun ModePicker(current: Schedule.Mode, onPick: (Schedule.Mode) -> Unit) {
    val options = listOf(
        Schedule.Mode.ONCE to R.string.schedule_mode_once,
        Schedule.Mode.INTERVAL to R.string.schedule_mode_interval,
        Schedule.Mode.DAILY to R.string.schedule_mode_daily,
        Schedule.Mode.WEEKLY to R.string.schedule_mode_weekly,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (mode, label) ->
            FilterChip(
                selected = current == mode,
                onClick = { onPick(mode) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun TimeField(draft: Schedule, onEdit: ((Schedule) -> Schedule) -> Unit) {
    val context = LocalContext.current
    Spacer(Modifier.height(10.dp))
    FieldLabel(stringResource(R.string.schedule_time))
    AssistChip(
        onClick = {
            TimePickerDialog(
                context,
                { _, hour, minute -> onEdit { it.copy(minuteOfDay = hour * 60 + minute) } },
                draft.hour,
                draft.minute,
                android.text.format.DateFormat.is24HourFormat(context),
            ).show()
        },
        label = { Text(ScheduleFormatter.time(context, draft.minuteOfDay)) },
        leadingIcon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(),
    )
}

@Composable
private fun OnceFields(draft: Schedule, onEdit: ((Schedule) -> Schedule) -> Unit) {
    val context = LocalContext.current
    val calendar = Calendar.getInstance().apply {
        timeInMillis = if (draft.onceAtMillis > 0) draft.onceAtMillis else System.currentTimeMillis()
    }

    Spacer(Modifier.height(10.dp))
    FieldLabel(stringResource(R.string.schedule_date))
    AssistChip(
        onClick = {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    onEdit { s ->
                        val c = Calendar.getInstance().apply {
                            timeInMillis = if (s.onceAtMillis > 0) s.onceAtMillis else System.currentTimeMillis()
                            set(year, month, day)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        s.copy(onceAtMillis = c.timeInMillis)
                    }
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
            ).apply {
                // A one-shot in the past would simply never fire.
                datePicker.minDate = System.currentTimeMillis() - 1000
            }.show()
        },
        label = {
            Text(
                if (draft.onceAtMillis > 0) ScheduleFormatter.date(context, draft.onceAtMillis)
                else stringResource(R.string.schedule_pick_date)
            )
        },
        leadingIcon = { Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp)) },
    )

    Spacer(Modifier.height(10.dp))
    FieldLabel(stringResource(R.string.schedule_time))
    AssistChip(
        onClick = {
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    onEdit { s ->
                        val c = Calendar.getInstance().apply {
                            timeInMillis = if (s.onceAtMillis > 0) s.onceAtMillis else System.currentTimeMillis()
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        s.copy(onceAtMillis = c.timeInMillis, minuteOfDay = hour * 60 + minute)
                    }
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(context),
            ).show()
        },
        label = {
            Text(
                if (draft.onceAtMillis > 0) ScheduleFormatter.time(context, draft.minuteOfDay)
                else stringResource(R.string.schedule_pick_time)
            )
        },
        leadingIcon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp)) },
    )
}

/**
 * Intervals are chosen from a ladder rather than a free number: the useful
 * values are few, and a slider that can land on "every 7 minutes" invites a
 * setting that ruins the battery.
 */
@Composable
private fun IntervalFields(draft: Schedule, onEdit: ((Schedule) -> Schedule) -> Unit) {
    val context = LocalContext.current
    val steps = listOf(15, 30, 60, 120, 180, 360, 720, 1440)
    val index = steps.indexOfFirst { it >= draft.intervalMinutes }.coerceAtLeast(0)

    Spacer(Modifier.height(10.dp))
    FieldLabel(stringResource(R.string.schedule_every, ScheduleFormatter.interval(context, steps[index])))
    Slider(
        value = index.toFloat(),
        onValueChange = { v -> onEdit { it.copy(intervalMinutes = steps[v.toInt().coerceIn(steps.indices)]) } },
        valueRange = 0f..(steps.size - 1).toFloat(),
        steps = steps.size - 2,
    )
    Hint(stringResource(R.string.schedule_min_interval, Schedule.MIN_INTERVAL_MINUTES))
}

@Composable
private fun DayPicker(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ScheduleFormatter.weekOrder().forEach { day ->
            val on = selected.contains(day)
            Surface(
                shape = CircleShape,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(40.dp)
                    .clickable { onChange(if (on) selected - day else selected + day) },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        ScheduleFormatter.dayInitial(day),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Preview(next: Long?, lastRun: String, runCount: Int) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.schedule_next_run),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    next?.let { ScheduleFormatter.dateTime(context, it) }
                        ?: stringResource(R.string.schedule_next_none),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.schedule_last_run),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(lastRun, style = MaterialTheme.typography.labelMedium)
            }
            if (runCount > 0) {
                Text(
                    stringResource(R.string.schedule_run_count, runCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let {
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

@Composable
private fun ConditionRow(
    icon: (@Composable () -> Unit)?,
    title: String,
    help: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.size(10.dp))
        }
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

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Hint(text: String, warning: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}
