package com.zetaforge.app.ui.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zetaforge.app.R
import com.zetaforge.app.ui.HostUiState
import com.zetaforge.app.ui.theme.MonoStyle
import com.zetaforge.app.ui.theme.zetaAccents
import com.zetaforge.sdk.ZetaSetting

/**
 * The settings form of a plugin, generated entirely from the schema the plugin
 * ships.
 *
 * Nothing here knows what any parameter means: the Host draws a switch, a
 * slider or a dropdown because the plugin said so, and hands the values back in
 * the Bundle every plugin already receives. Adding a parameter to a plugin
 * therefore needs no change in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginSettingsDialog(
    state: HostUiState.SettingsState,
    onValueChange: (String, Any) -> Unit,
    onPickFolder: (String) -> Unit,
    onAction: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accents = zetaAccents()
    var showAdvanced by remember { mutableStateOf(false) }

    val visible = state.spec.settings.filter { showAdvanced || !it.advanced }
    val groups = visible.groupBy { it.group }
    val hasAdvanced = state.spec.settings.any { it.advanced }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(state.pluginName) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.spec.isEmpty) {
                    Text(
                        stringResource(R.string.settings_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                groups.forEach { (group, settings) ->
                    if (group.isNotBlank()) {
                        Text(
                            group.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    settings.forEach { setting ->
                        SettingField(
                            setting = setting,
                            value = state.values[setting.key],
                            busyAction = state.runningAction == setting.key,
                            onValueChange = { onValueChange(setting.key, it) },
                            onPickFolder = { onPickFolder(setting.key) },
                            onAction = { onAction(setting.key) },
                        )
                    }
                }

                state.actionResult?.let { result ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = (if (result.successful) accents.success else accents.danger).copy(alpha = 0.12f),
                        contentColor = if (result.successful) accents.success else accents.danger,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            result.message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                if (hasAdvanced) {
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(
                            stringResource(
                                if (showAdvanced) R.string.settings_hide_advanced else R.string.settings_show_advanced
                            )
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onSave) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text(stringResource(R.string.settings_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingField(
    setting: ZetaSetting,
    value: Any?,
    busyAction: Boolean,
    onValueChange: (Any) -> Unit,
    onPickFolder: () -> Unit,
    onAction: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (setting) {
            is ZetaSetting.Switch -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(setting.label, style = MaterialTheme.typography.bodyMedium)
                    Description(setting.description)
                }
                Switch(
                    checked = value as? Boolean ?: setting.default,
                    onCheckedChange = onValueChange,
                )
            }

            is ZetaSetting.Number -> {
                val current = (value as? Number)?.toLong() ?: setting.default
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(setting.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        current.toString() + (if (setting.unit.isNotBlank()) " " + setting.unit else ""),
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // A bounded range gets a slider; an open one a plain field,
                // because a slider over 0..2 billion is useless.
                if (setting.max < Long.MAX_VALUE && setting.max > setting.min) {
                    Slider(
                        value = current.toFloat(),
                        onValueChange = { onValueChange(it.toLong()) },
                        valueRange = setting.min.toFloat()..setting.max.toFloat(),
                        steps = stepsFor(setting.min, setting.max, setting.step),
                    )
                } else {
                    OutlinedTextField(
                        value = current.toString(),
                        onValueChange = { text -> text.toLongOrNull()?.let(onValueChange) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Description(setting.description)
            }

            is ZetaSetting.Decimal -> {
                val current = (value as? Number)?.toDouble() ?: setting.default
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(setting.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        String.format("%.2f", current) + (if (setting.unit.isNotBlank()) " " + setting.unit else ""),
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = current.toFloat(),
                    onValueChange = { onValueChange(it.toDouble()) },
                    valueRange = setting.min.toFloat()..setting.max.toFloat(),
                )
                Description(setting.description)
            }

            is ZetaSetting.Text -> {
                OutlinedTextField(
                    value = value?.toString() ?: setting.default,
                    onValueChange = onValueChange,
                    label = { Text(setting.label) },
                    placeholder = { if (setting.hint.isNotBlank()) Text(setting.hint) },
                    singleLine = true,
                    visualTransformation = if (setting.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Description(setting.description)
            }

            is ZetaSetting.Choice -> {
                var expanded by remember { mutableStateOf(false) }
                val current = value?.toString() ?: setting.default
                val currentLabel = setting.options.firstOrNull { it.value == current }?.label ?: current
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = currentLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(setting.label) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        setting.options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    onValueChange(option.value)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                Description(setting.description)
            }

            is ZetaSetting.MultiChoice -> {
                Text(setting.label, style = MaterialTheme.typography.bodyMedium)
                val selected = (value as? List<*>)?.mapNotNull { it?.toString() } ?: setting.default
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    setting.options.forEach { option ->
                        FilterChip(
                            selected = selected.contains(option.value),
                            onClick = {
                                onValueChange(
                                    if (selected.contains(option.value)) selected - option.value
                                    else selected + option.value
                                )
                            },
                            label = { Text(option.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Description(setting.description)
            }

            is ZetaSetting.Folder -> {
                Text(setting.label, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        value?.toString()?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.settings_pick_folder),
                        style = MonoStyle,
                        maxLines = 1,
                    )
                }
                Description(setting.description)
            }

            is ZetaSetting.Action -> {
                OutlinedButton(
                    onClick = onAction,
                    enabled = !busyAction,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busyAction) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(setting.runningLabel.ifBlank { setting.label })
                    } else {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(setting.label)
                    }
                }
                Description(setting.description)
            }
        }
    }
}

@Composable
private fun Description(text: String) {
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Slider notches, capped so a wide range does not become unusable. */
private fun stepsFor(min: Long, max: Long, step: Long): Int {
    if (step <= 0) return 0
    val count = ((max - min) / step).toInt() - 1
    return count.coerceIn(0, 100)
}

@Composable
private fun ExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        content = content,
    )
}

/** Small helper kept for symmetry with the other components in this package. */
@Composable
fun SettingsButtonPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.size(0.dp))
}
