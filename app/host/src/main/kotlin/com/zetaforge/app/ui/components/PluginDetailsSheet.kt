package com.zetaforge.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zetaforge.app.R
import com.zetaforge.app.ui.HostUiState
import com.zetaforge.app.ui.theme.MonoStyle
import com.zetaforge.app.ui.theme.zetaAccents
import com.zetaforge.runtime.permission.PermissionState
import com.zetaforge.runtime.permission.PermissionStatus
import com.zetaforge.sdk.PluginResult

/**
 * The bottom sheet is the one place that knows everything about a plugin, so it
 * is ordered by why you opened it:
 *
 *  1. who it is (header) and what you can do to it right now (the action row,
 *     directly under the header - the reason the sheet was opened);
 *  2. how the last run went (the result card, when there is one);
 *  3. what it will ask for and what it is made of (the technical sections);
 *  4. getting the package back out and taking it off the device, last and
 *     visually separated, because uninstall must never be a mis-tap away from
 *     export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailsSheet(
    details: HostUiState.DetailsState,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onOpenScreen: () -> Unit,
    onSettings: () -> Unit,
    onSchedule: () -> Unit,
    onRunFailing: () -> Unit,
    onRunThrowing: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onUnload: () -> Unit,
    onUninstall: () -> Unit,
    onViewCode: () -> Unit,
) {
    val accents = zetaAccents()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entry = details.entry
    val manifest = entry.installed.manifest

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // -- 1. identity ---------------------------------------------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ZetaLogo(size = 52.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.installed.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            if (manifest.author.isNotBlank()) {
                                append(stringResource(R.string.plugin_by_author, manifest.author))
                                append("  ·  ")
                            }
                            append(stringResource(R.string.plugin_version, entry.installed.version))
                            append("  ·  ")
                            append(formatSize(entry.installed.sizeBytes))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatePill(entry.state)
            }

            if (manifest.description.isNotBlank()) {
                Text(
                    manifest.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // -- 2. what you can do right now ----------------------------------
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A plugin with a screen leads with OPEN: for a screen-only one it
                // is the only thing that means anything, and for a plugin that is
                // both it is the action a person came here for.
                if (manifest.hasUi) {
                    FilledTonalButton(
                        onClick = onOpenScreen,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Text(manifest.ui?.label?.takeIf { it.isNotBlank() } ?: stringResource(R.string.action_open))
                    }
                }

                // RUN is hidden for a screen-only plugin: its `execute` exists
                // because the contract requires one, and pressing it would do
                // nothing a user could want.
                if (!manifest.isUiOnly) {
                    Button(
                        onClick = onStart,
                        enabled = !entry.isBusy,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Text(stringResource(R.string.action_start))
                    }
                }

                OutlinedIconButton(
                    onClick = onSettings,
                    modifier = Modifier.size(44.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = stringResource(R.string.action_settings),
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Scheduling something that only exists while someone is looking
                // at it is meaningless, so it is not offered.
                if (!manifest.isUiOnly) {
                    OutlinedIconButton(
                        onClick = onSchedule,
                        modifier = Modifier.size(44.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Icon(
                            Icons.Outlined.Schedule,
                            contentDescription = stringResource(R.string.schedule_title),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // -- 3. how the last run went --------------------------------------
            entry.lastResult?.let { result -> ResultCard(result) }

            // -- 4. what it asks for, and what it is made of --------------------
            DetailSection(stringResource(R.string.permissions_title)) {
                if (manifest.permissions.isEmpty() && manifest.specialAccess.isEmpty()) {
                    Text(
                        stringResource(R.string.permissions_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val plan = entry.permissionPlan
                    val statuses = plan?.permissions ?: manifest.permissions.map {
                        PermissionStatus(it, PermissionState.REQUESTABLE)
                    }
                    statuses.forEach { status ->
                        PermissionRow(status)
                        Spacer(Modifier.height(6.dp))
                    }
                    manifest.specialAccess.forEach { requirement ->
                        KeyValue(requirement.access.label, requirement.reason.ifBlank { "-" })
                    }
                }
            }

            DetailSection(stringResource(R.string.details_identity)) {
                KeyValue(stringResource(R.string.details_field_plugin_id), manifest.pluginId)
                KeyValue(stringResource(R.string.details_field_entry_point), manifest.entryPoint)
                if (manifest.homepage.isNotBlank()) {
                    KeyValue(stringResource(R.string.details_field_homepage), manifest.homepage)
                }
                if (manifest.license.isNotBlank()) {
                    KeyValue(stringResource(R.string.details_field_license), manifest.license)
                }
                KeyValue(
                    stringResource(R.string.details_field_host_api),
                    manifest.minHostApi.toString() + ".." + manifest.maxHostApi,
                )
            }

            DetailSection(stringResource(R.string.details_package)) {
                KeyValue(stringResource(R.string.details_field_size), formatSize(entry.installed.sizeBytes))
                KeyValue(stringResource(R.string.details_field_checksum), entry.installed.sha256)
                KeyValue(
                    stringResource(R.string.details_field_dex),
                    manifest.dex.joinToString { it.path + " (" + formatSize(it.size) + ")" },
                )
                KeyValue(
                    stringResource(R.string.details_field_signature),
                    if (manifest.signature == null) {
                        stringResource(R.string.details_unsigned)
                    } else {
                        manifest.signature!!.algorithm
                    },
                )
                KeyValue(
                    stringResource(R.string.details_field_class_loader),
                    entry.loaderStrategy ?: stringResource(R.string.details_not_loaded),
                )
                if (manifest.capabilities.isNotEmpty()) {
                    KeyValue(stringResource(R.string.details_field_capabilities), manifest.capabilities.joinToString())
                }
            }

            if (manifest.bundledDependencies.isNotEmpty()) {
                DetailSection(stringResource(R.string.details_bundled_deps)) {
                    manifest.bundledDependencies.forEach { Text(it, style = MonoStyle) }
                }
            }
            if (manifest.hostProvidedDependencies.isNotEmpty()) {
                DetailSection(stringResource(R.string.details_host_deps)) {
                    manifest.hostProvidedDependencies.forEach { Text(it, style = MonoStyle) }
                }
            }

            if (details.verification.isNotEmpty()) {
                DetailSection(stringResource(R.string.details_verification)) {
                    details.verification.forEach { line ->
                        Text(
                            line,
                            style = MonoStyle,
                            color = when {
                                line.startsWith("[FAIL]") -> accents.danger
                                line.startsWith("[warn]") -> accents.warning
                                else -> accents.success
                            },
                        )
                    }
                }
            }

            // Both buttons feed inputs (`baseUrl`, `throwOnPurpose`) that only a
            // plugin written for the demo reads. Any other plugin ignores them
            // and simply runs for real - a backup, a compression pass over the
            // gallery - which is the opposite of what "failure scenario"
            // promises. So they are offered only where they mean something.
            if (manifest.capabilities.contains(CAPABILITY_FAILURE_SCENARIOS)) {
                DetailSection(stringResource(R.string.details_scenarios)) {
                    Text(
                        stringResource(R.string.details_scenarios_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onRunFailing, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.details_scenario_unreachable), maxLines = 1)
                        }
                        OutlinedButton(onClick = onRunThrowing, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.details_scenario_throw), maxLines = 1)
                        }
                    }
                }
            }

            // -- 5. getting it back out, and off the device ---------------------
            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.details_manage).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onViewCode, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_view_code).uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_share).uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_export).uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = onUnload, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.Memory, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_unload).uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedButton(
                    onClick = onUninstall,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, accents.danger.copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accents.danger),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_uninstall).uppercase())
                }
            }
        }
    }
}

/** How the last run went, as a coloured card rather than a row of fields. */
@Composable
private fun ResultCard(result: PluginResult) {
    val accents = zetaAccents()
    val color = when (result) {
        is PluginResult.Success -> accents.success
        is PluginResult.Failure -> accents.danger
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.10f),
        contentColor = color,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color),
                )
                Text(
                    text = when (result) {
                        is PluginResult.Failure -> stringResource(R.string.status_failed) + " - [" + result.errorCode + "]"
                        is PluginResult.Success -> stringResource(R.string.status_success)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.plugin_took, result.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                result.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.data.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                result.data.forEach { (k, v) -> KeyValue(k, v.toString()) }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(118.dp),
        )
        Text(
            value,
            style = MonoStyle,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Capability tag a plugin declares to say it understands the demo's failure
 * inputs, and so can be asked to fail on purpose.
 */
private const val CAPABILITY_FAILURE_SCENARIOS = "poc.failureScenarios"
