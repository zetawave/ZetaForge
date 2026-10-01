package com.zetaforge.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zetaforge.app.R
import com.zetaforge.app.ui.ScheduleFormatter
import com.zetaforge.app.ui.theme.zetaAccents
import com.zetaforge.runtime.PluginEntry
import com.zetaforge.runtime.schedule.Schedule
import com.zetaforge.sdk.PluginResult
import com.zetaforge.sdk.PluginState

/**
 * One row of the plugin list.
 *
 * Collapsed it is a single dense line - who the plugin is, what state it is in,
 * and the one action that matters. A list that carries four buttons per card is
 * a wall of buttons, not a list, so everything else (settings, schedule, source,
 * management) lives one tap away in the details sheet, which the card itself
 * opens.
 *
 * State is a small LED on the corner of the logo rather than a pill of text:
 * colour carries it in the periphery, and the one case worth words - the last
 * result - gets its own line.
 *
 * Purely presentational: every action is a callback, no runtime call happens
 * inside a composable.
 */
@Composable
fun PluginCard(
    entry: PluginEntry,
    schedule: Schedule,
    onStart: () -> Unit,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = zetaAccents()
    val manifest = entry.installed.manifest
    val busy = entry.isBusy

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column {
            // A run in progress announces itself on the card's top edge: visible
            // from across the list, impossible to mistake for decoration.
            AnimatedVisibility(visible = busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = accents.info,
                    trackColor = Color.Transparent,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDetails)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LogoWithLed(entry.state)

                Column(Modifier.weight(1f)) {
                    // The name gets the line to itself. Sharing it with the
                    // version meant a plugin called anything real - "Live
                    // Location" was enough - arrived on screen as "Live Locat…",
                    // which is the one word on the card that has to survive.
                    Text(
                        entry.installed.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        // Author and version only: the row shares its width with
                        // the action button, and a byline that truncates is worse
                        // than a short byline. Size and the rest live in the
                        // details sheet.
                        text = buildString {
                            if (manifest.author.isNotBlank()) {
                                append(stringResource(R.string.plugin_by_author, manifest.author))
                                append("  ·  ")
                            }
                            append(stringResource(R.string.plugin_version, entry.installed.version))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (schedule.isAutomatic) {
                        Spacer(Modifier.height(4.dp))
                        SchedulePill(schedule)
                    }
                    entry.lastResult?.takeIf { !busy }?.let { result ->
                        Spacer(Modifier.height(4.dp))
                        ResultLine(result)
                    }
                }

                CardAction(entry, onStart, onOpen)
            }
        }
    }
}

/**
 * The card's single action: OPEN for a plugin with a screen, RUN otherwise.
 * A run started on a card is confirmed by the spinner inside the same button.
 */
@Composable
private fun CardAction(entry: PluginEntry, onStart: () -> Unit, onOpen: () -> Unit) {
    val manifest = entry.installed.manifest
    val hasScreen = manifest.hasUi

    Button(
        onClick = if (hasScreen) onOpen else onStart,
        enabled = !entry.isBusy,
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        colors = if (hasScreen) {
            ButtonDefaults.filledTonalButtonColors()
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        if (entry.isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Icon(
                imageVector = if (hasScreen) Icons.Outlined.OpenInFull else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = when {
                entry.isBusy -> stringResource(R.string.action_running)
                hasScreen -> manifest.ui?.label?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.action_open)

                else -> stringResource(R.string.action_start)
            },
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

/**
 * The product's own mark with a status LED on its corner. The mark says what
 * kind of thing the row is; the LED says how it is doing right now.
 */
@Composable
private fun LogoWithLed(state: PluginState) {
    val accents = zetaAccents()
    val led = when (state) {
        PluginState.SUCCESS -> accents.success
        PluginState.FAILED -> accents.danger
        PluginState.RUNNING, PluginState.STARTING, PluginState.LOADING -> accents.info
        PluginState.LOADED -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(44.dp)) {
        ZetaLogo(size = 44.dp)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(11.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(2.dp)
                .clip(CircleShape)
                .background(led),
        )
    }
}

/** The last outcome in one line: a coloured dot and the message, trimmed. */
@Composable
private fun ResultLine(result: PluginResult) {
    val accents = zetaAccents()
    val color = when (result) {
        is PluginResult.Success -> accents.success
        is PluginResult.Failure -> accents.danger
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = when (result) {
                is PluginResult.Failure -> stringResource(R.string.status_failed) + " - [" + result.errorCode + "]  " + result.message
                is PluginResult.Success -> stringResource(R.string.status_success) + "  " + result.message
            },
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
    else -> bytes.toString() + " B"
}

/**
 * The badge on a scheduled plugin. Small on purpose: it is a fact about the
 * plugin, not a call to action.
 */
@Composable
private fun SchedulePill(schedule: Schedule) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                ScheduleFormatter.summary(context, schedule),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
