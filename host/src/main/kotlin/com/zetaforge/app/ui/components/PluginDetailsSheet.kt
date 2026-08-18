package com.zetaforge.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zetaforge.app.ui.HostUiState
import com.zetaforge.app.ui.theme.MonoStyle
import com.zetaforge.app.ui.theme.zetaAccents
import com.zetaforge.sdk.PluginResult

/**
 * Bottom sheet with everything the Host knows about a plugin: manifest,
 * verification checks, last result payload and the failure-path actions used to
 * demonstrate error containment.
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
            Text(
                manifest.description.ifBlank { "No description provided." },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaChip("v" + manifest.version)
                MetaChip("format " + manifest.formatVersion)
                MetaChip("minSdk " + manifest.minSdk)
                StatePill(entry.state)
            }

            DetailSection("Identity") {
                KeyValue("pluginId", manifest.pluginId)
                KeyValue("entryPoint", manifest.entryPoint)
                KeyValue("author", manifest.author.ifBlank { "-" })
                KeyValue("hostApi", manifest.minHostApi.toString() + ".." + manifest.maxHostApi)
            }

            DetailSection("Package") {
                KeyValue("size", formatSize(entry.installed.sizeBytes))
                KeyValue("sha256", entry.installed.sha256)
                KeyValue("dex", manifest.dex.joinToString { it.path + " (" + formatSize(it.size) + ")" })
                KeyValue("signature", if (manifest.signature == null) "unsigned" else manifest.signature!!.algorithm)
                KeyValue("classLoader", entry.loaderStrategy ?: "not loaded yet")
            }

            if (manifest.permissions.isNotEmpty() || manifest.capabilities.isNotEmpty()) {
                DetailSection("Requested") {
                    KeyValue("permissions", manifest.permissions.joinToString().ifBlank { "-" })
                    KeyValue("capabilities", manifest.capabilities.joinToString().ifBlank { "-" })
                }
            }

            if (manifest.bundledDependencies.isNotEmpty()) {
                DetailSection("Dependencies inside the plugin DEX") {
                    manifest.bundledDependencies.forEach { Text(it, style = MonoStyle) }
                }
            }
            if (manifest.hostProvidedDependencies.isNotEmpty()) {
                DetailSection("Provided by the Host") {
                    manifest.hostProvidedDependencies.forEach { Text(it, style = MonoStyle) }
                }
            }

            if (details.verification.isNotEmpty()) {
                DetailSection("Verification") {
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
                DetailSection("Last result") {
                    KeyValue("status", result.status.name)
                    KeyValue("message", result.message)
                    KeyValue("duration", result.durationMs.toString() + " ms")
                    (result as? PluginResult.Failure)?.let { KeyValue("errorCode", it.errorCode) }
                    result.data.forEach { (k, v) -> KeyValue(k, v) }
                }
            }

            DetailSection("Failure scenarios (PoC)") {
                Text(
                    "Both run the same plugin; the Host must survive and report the failure.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onRunFailing, modifier = Modifier.weight(1f)) {
                        Text("UNREACHABLE HOST")
                    }
                    OutlinedButton(onClick = onRunThrowing, modifier = Modifier.weight(1f)) {
                        Text("THROW")
                    }
                }
            }

            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onUnload, modifier = Modifier.weight(1f)) { Text("UNLOAD") }
                TextButton(onClick = onUninstall, modifier = Modifier.weight(1f)) { Text("UNINSTALL") }
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
