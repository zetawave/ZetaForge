package com.zetaforge.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zetaforge.app.R
import com.zetaforge.app.ui.theme.MonoStyle
import com.zetaforge.app.ui.theme.zetaAccents
import com.zetaforge.runtime.permission.PermissionPlan
import com.zetaforge.runtime.permission.PermissionState
import com.zetaforge.runtime.permission.PermissionStatus
import com.zetaforge.runtime.permission.SpecialAccess

/**
 * Explains, before the system dialog appears, which plugin is asking for what
 * and why - the piece Android itself cannot provide, because to the OS every
 * request comes from ZetaForge, not from the plugin.
 */
@Composable
fun PermissionRequestDialog(
    pluginName: String,
    plan: PermissionPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accents = zetaAccents()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.permissions_requested_by, pluginName)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.permissions_explain_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                plan.permissions
                    .filter { it.state != PermissionState.NOT_APPLICABLE && it.state != PermissionState.GRANTED }
                    .forEach { status -> PermissionRow(status) }

                plan.missingSpecialAccess.forEach { requirement ->
                    PermissionLine(
                        icon = Icons.Outlined.Warning,
                        tint = accents.warning,
                        title = requirement.access.label,
                        subtitle = requirement.reason,
                        badge = stringResource(R.string.special_access_title),
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.action_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Sends the user to the right Settings screen for a special access. */
@Composable
fun SpecialAccessDialog(
    access: SpecialAccess,
    reason: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Warning, contentDescription = null, tint = zetaAccents().warning) },
        title = { Text(stringResource(R.string.special_access_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.special_access_body, access.label))
                if (reason.isNotBlank()) {
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.action_open_settings)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Shown when Android will not ask again, or when the Host cannot ask at all. */
@Composable
fun PermissionBlockedDialog(
    title: String,
    body: String,
    canOpenSettings: Boolean,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Info, contentDescription = null, tint = zetaAccents().danger) },
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            if (canOpenSettings) {
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
            } else {
                Button(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
        dismissButton = if (canOpenSettings) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
        } else {
            null
        },
    )
}

/** One permission row: name, why it is needed, and its current state. */
@Composable
fun PermissionRow(status: PermissionStatus, modifier: Modifier = Modifier) {
    val accents = zetaAccents()
    val (icon, tint, badge) = when (status.state) {
        PermissionState.GRANTED ->
            Triple(Icons.Outlined.CheckCircle, accents.success, stringResource(R.string.permissions_state_granted))

        PermissionState.REQUESTABLE ->
            Triple(Icons.Outlined.Lock, MaterialTheme.colorScheme.primary, stringResource(R.string.permissions_state_requestable))

        PermissionState.PERMANENTLY_DENIED ->
            Triple(Icons.Outlined.Warning, accents.danger, stringResource(R.string.permissions_state_denied))

        PermissionState.NOT_DECLARED_BY_HOST ->
            Triple(Icons.Outlined.Warning, accents.danger, stringResource(R.string.permissions_state_undeclared))

        PermissionState.NOT_APPLICABLE ->
            Triple(Icons.Outlined.Info, accents.muted, stringResource(R.string.permissions_state_not_applicable))
    }

    PermissionLine(
        icon = icon,
        tint = tint,
        title = status.requirement.shortName,
        subtitle = status.requirement.reason,
        badge = badge,
        optional = status.requirement.optional,
        modifier = modifier,
    )
}

@Composable
private fun PermissionLine(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    badge: String,
    optional: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (optional) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.permissions_optional),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(badge, style = MaterialTheme.typography.labelSmall, color = tint)
            }
        }
    }
}
