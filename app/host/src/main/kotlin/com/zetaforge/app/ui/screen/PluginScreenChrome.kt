package com.zetaforge.app.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zetaforge.app.R
import com.zetaforge.app.ui.theme.MonoStyle
import com.zetaforge.app.ui.theme.zetaAccents
import com.zetaforge.runtime.permission.PermissionPlan
import com.zetaforge.runtime.permission.SpecialAccess
import kotlinx.coroutines.delay

/**
 * The bar above every plugin screen, and the dialogs that belong to the Host.
 *
 * It is composed in its own view, above the plugin's, so a plugin cannot draw
 * over the only thing on screen that says whose code is running. That is the
 * whole reason it exists: a screen plugin runs with the Host's UID and the
 * Host's permissions, and a convincing fake of the Host's own UI is the cheapest
 * attack such a plugin has.
 */
@Composable
internal fun PluginScreenChrome(
    pluginName: String,
    subtitle: String?,
    phase: PluginScreenActivity.Phase,
    message: String?,
    permissionPrompt: PermissionPlan?,
    specialAccessPrompt: Pair<SpecialAccess, String>?,
    onClose: () -> Unit,
    onMessageShown: () -> Unit,
    onPermissionResult: (Boolean) -> Unit,
    onSpecialAccessResult: (Boolean) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.screen_close),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        pluginName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // The plugin can set the second line, never the first:
                        // it may describe what it is doing, not who it is.
                        text = subtitle ?: stringResource(R.string.screen_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (phase is PluginScreenActivity.Phase.Loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                PluginBadge()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // A message the plugin asked to show. Rendered by the Host, under
            // the Host's bar, so it cannot be mistaken for a system prompt.
            AnimatedVisibility(visible = message != null) {
                if (message != null) {
                    LaunchedEffect(message) {
                        delay(MESSAGE_MS)
                        onMessageShown()
                    }
                    Snackbar(Modifier.padding(8.dp)) { Text(message) }
                }
            }
        }
    }

    if (permissionPrompt != null) {
        PermissionRationaleDialog(
            pluginName = pluginName,
            plan = permissionPrompt,
            onResult = onPermissionResult,
        )
    }
    val special = specialAccessPrompt
    if (special != null) {
        AlertDialog(
            onDismissRequest = { onSpecialAccessResult(false) },
            title = { Text(special.first.label) },
            text = {
                Text(
                    special.second.ifBlank { stringResource(R.string.screen_special_access_body) }
                )
            },
            confirmButton = {
                TextButton(onClick = { onSpecialAccessResult(true) }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { onSpecialAccessResult(false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Why a permission is being asked for, in the plugin author's own words. */
@Composable
private fun PermissionRationaleDialog(
    pluginName: String,
    plan: PermissionPlan,
    onResult: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onResult(false) },
        title = { Text(stringResource(R.string.permissions_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.screen_permission_intro, pluginName))
                (plan.requestable + plan.permanentlyDenied).forEach { requirement ->
                    Text(
                        text = "• " + requirement.shortName +
                            if (requirement.reason.isNotBlank()) " — " + requirement.reason else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                plan.missingSpecialAccess.forEach { requirement ->
                    Text(
                        text = "• " + requirement.access.label +
                            if (requirement.reason.isNotBlank()) " — " + requirement.reason else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResult(true) }) {
                Text(stringResource(R.string.action_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = { onResult(false) }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun PluginBadge() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = stringResource(R.string.screen_badge_short),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * The ground the Host's own screens paint on.
 *
 * Without it these compositions are transparent and show the Activity's window
 * background, which is themed by Android from the manifest and has no idea
 * which of ZetaForge's two colour schemes is in force.
 */
@Composable
private fun PluginScreenSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        content = content,
    )
}

/** What fills the plugin's area before its content exists, or instead of it. */
@Composable
internal fun PluginScreenPlaceholder(phase: PluginScreenActivity.Phase) = PluginScreenSurface {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            is PluginScreenActivity.Phase.Refused -> RefusalCard(phase)
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun RefusalCard(phase: PluginScreenActivity.Phase.Refused) {
    val accents = zetaAccents()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = accents.warning,
            modifier = Modifier.size(40.dp),
        )
        Text(
            stringResource(R.string.screen_unavailable_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            phase.reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            phase.errorCode,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What a user sees instead of a crash.
 *
 * It shows the exception and the top of its stack on purpose: the person
 * looking at a plugin screen that just died is, in this product, usually the
 * person who wrote it.
 */
@Composable
internal fun PluginScreenError(
    title: String,
    body: String,
    detail: String,
    stackTrace: String,
    onClose: () -> Unit,
) = PluginScreenSurface {
    val accents = zetaAccents()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = accents.danger,
            modifier = Modifier.size(40.dp),
        )
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = accents.danger.copy(alpha = 0.10f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(detail, style = MonoStyle, color = accents.danger)
                Spacer(Modifier.height(8.dp))
                Text(
                    stackTrace,
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = onClose) { Text(stringResource(R.string.screen_close)) }
    }
}

private const val MESSAGE_MS = 2_600L
