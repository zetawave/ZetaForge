package com.zetaforge.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * Bottom sheet with everything the Host knows about a plugin: identity,
 * permissions and their live state, package facts, verification checks, the last
 * result, and the failure-path actions used to demonstrate error containment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailsSheet(
    details: HostUiState.DetailsState,
    onDismiss: () -> Unit,
    onRunFailing: () -> Unit,
    onRunThrowing: () -> Unit,
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
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(entry.installed.displayName, style = MaterialTheme.typography.headlineSmall)
            if (manifest.author.isNotBlank()) {
                Text(
                    stringResource(R.string.plugin_by_author, manifest.author),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                manifest.description.ifBlank { stringResource(R.string.details_no_description) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaChip(stringResource(R.string.plugin_version, manifest.version))
                MetaChip(stringResource(R.string.details_field_format) + " " + manifest.formatVersion)
                MetaChip(stringResource(R.string.details_field_min_sdk) + " " + manifest.minSdk)
                StatePill(entry.state)
            }

            Button(onClick = onViewCode, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_view_code).uppercase())
            }

            // What the plugin will ask for, and where each request stands today.
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

            entry.lastResult?.let { result ->
                DetailSection(stringResource(R.string.details_last_result)) {
                    KeyValue(stringResource(R.string.details_field_status), result.status.name)
                    KeyValue(stringResource(R.string.details_field_message), result.message)
                    KeyValue(stringResource(R.string.details_field_duration), result.durationMs.toString() + " ms")
                    (result as? PluginResult.Failure)?.let {
                        KeyValue(stringResource(R.string.details_field_error_code), it.errorCode)
                    }
                    result.data.forEach { (k, v) -> KeyValue(k, v) }
                }
            }

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

            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onUnload, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_unload).uppercase())
                }
                TextButton(onClick = onUninstall, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_uninstall).uppercase())
                }
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
            modifier = Modifier.width(110.dp),
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
