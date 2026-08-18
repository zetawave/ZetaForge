package com.zetaforge.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zetaforge.app.R
import com.zetaforge.app.ui.theme.MonoStyle
import com.zetaforge.app.ui.theme.zetaAccents
import com.zetaforge.runtime.PluginEntry
import com.zetaforge.sdk.PluginResult

/**
 * Card rendering one installed plugin.
 *
 * Collapsed it shows only what identifies the plugin - name, version, author,
 * state - plus START, so a long list stays readable. Expanding reveals the
 * description, the permissions it will ask for, package facts and the last
 * result. Purely presentational: every action is a callback, no runtime call
 * happens inside a composable.
 */
@Composable
fun PluginCard(
    entry: PluginEntry,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onStart: () -> Unit,
    onDetails: () -> Unit,
    onViewCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = zetaAccents()
    val manifest = entry.installed.manifest
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // --- always visible: who this plugin is ---------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp).size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.installed.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.plugin_version, entry.installed.version),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = if (manifest.author.isNotBlank()) {
                            stringResource(R.string.plugin_by_author, manifest.author)
                        } else {
                            entry.installed.id
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatePill(entry.state)
                IconButton(onClick = onToggleExpanded) {
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.action_collapse_card else R.string.action_expand_card
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(chevronRotation),
                    )
                }
            }

            // A run in progress, or its outcome, stays visible when collapsed:
            // it is the reason you came back to the screen.
            AnimatedVisibility(visible = entry.isBusy) {
                Column {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = accents.info,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.action_running).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = accents.info,
                    )
                }
            }

            entry.lastResult?.let { result ->
                ResultBanner(result, compact = !expanded)
            }

            // --- expanded only -----------------------------------------------
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        entry.installed.id,
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (manifest.description.isNotBlank()) {
                        Text(
                            manifest.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (manifest.permissions.isNotEmpty() || manifest.specialAccess.isNotEmpty()) {
                        PermissionSummary(entry)
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MetaChip(formatSize(entry.installed.sizeBytes))
                        MetaChip("sha " + entry.installed.sha256.take(10))
                        MetaChip("api " + manifest.minHostApi + ".." + manifest.maxHostApi)
                        entry.loaderStrategy?.let { MetaChip(it.lowercase().replace('_', '-')) }
                    }
                }
            }

            // --- actions ------------------------------------------------------
            Button(
                onClick = onStart,
                enabled = !entry.isBusy,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (entry.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_running).uppercase())
                } else {
                    Text(stringResource(R.string.action_start).uppercase())
                }
            }

            AnimatedVisibility(visible = expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onViewCode,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_view_code).uppercase(), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onDetails,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_details).uppercase(), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** What this plugin will ask the user for, before they ever tap START. */
@Composable
private fun PermissionSummary(entry: PluginEntry) {
    val accents = zetaAccents()
    val manifest = entry.installed.manifest
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    stringResource(R.string.permissions_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    manifest.permissions.forEach { permission ->
                        MetaChip(permission.shortName + if (permission.optional) " ?" else "")
                    }
                    manifest.specialAccess.forEach { access ->
                        MetaChip(access.access.label, tint = accents.warning)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultBanner(result: PluginResult, compact: Boolean) {
    val accents = zetaAccents()
    val color = when (result) {
        is PluginResult.Success -> accents.success
        is PluginResult.Failure -> accents.danger
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.10f),
        contentColor = color,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp)) {
            Text(
                text = when (result) {
                    is PluginResult.Failure -> stringResource(R.string.status_failed) + " - [" + result.errorCode + "]"
                    is PluginResult.Success -> stringResource(R.string.status_success)
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                result.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (compact) 1 else 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                Text(
                    stringResource(R.string.plugin_took, result.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}
