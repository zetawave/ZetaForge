package com.zetaforge.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zetaforge.app.R
import com.zetaforge.app.ui.theme.zetaAccents
import com.zetaforge.sdk.PluginState

/** Small rounded label used for metadata (size, checksum, loader, ...). */
@Composable
fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = tint,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Coloured pill showing a plugin lifecycle state. */
@Composable
fun StatePill(state: PluginState, modifier: Modifier = Modifier) {
    val accents = zetaAccents()
    val color = when (state) {
        PluginState.SUCCESS -> accents.success
        PluginState.FAILED -> accents.danger
        PluginState.RUNNING, PluginState.STARTING, PluginState.LOADING -> accents.info
        PluginState.LOADED -> MaterialTheme.colorScheme.primary
        PluginState.STOPPED -> accents.muted
        else -> accents.muted
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(state.label(), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * The lifecycle state, in words rather than as an enum constant.
 *
 * `PluginState.name` was going straight onto the card, which meant an Italian
 * phone showing "INSTALLED" next to nine translated lines - the one piece of
 * English left, and on the most-read part of the screen.
 */
@Composable
fun PluginState.label(): String = stringResource(
    when (this) {
        PluginState.DISCOVERED -> R.string.plugin_state_discovered
        PluginState.VALIDATING -> R.string.plugin_state_validating
        PluginState.INSTALLING -> R.string.plugin_state_installing
        PluginState.INSTALLED -> R.string.plugin_state_installed
        PluginState.LOADING -> R.string.plugin_state_loading
        PluginState.LOADED -> R.string.plugin_state_loaded
        PluginState.STARTING -> R.string.plugin_state_starting
        PluginState.RUNNING -> R.string.plugin_state_running
        PluginState.SUCCESS -> R.string.plugin_state_success
        PluginState.FAILED -> R.string.plugin_state_failed
        PluginState.STOPPED -> R.string.plugin_state_stopped
    }
)

/** Section header with an optional trailing action slot. */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            androidx.compose.foundation.layout.Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        trailing?.invoke()
    }
}
