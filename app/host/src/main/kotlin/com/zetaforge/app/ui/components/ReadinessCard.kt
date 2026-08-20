package com.zetaforge.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zetaforge.app.R
import com.zetaforge.app.ui.ReadinessItem
import com.zetaforge.app.ui.SystemReadiness
import com.zetaforge.app.ui.items

/**
 * What Android still needs before a scheduled run can be relied on.
 *
 * Shown wherever it is relevant — the wizard, the diagnostics screen, and as a
 * banner on the plugin list once something is scheduled. Each row ends in a
 * button that opens the exact screen with the exact switch, because "check your
 * battery settings" is advice, not help.
 */
@Composable
fun ReadinessPanel(
    readiness: SystemReadiness,
    onFix: (ReadinessItem.Fix) -> Unit,
    modifier: Modifier = Modifier,
    showWhenSatisfied: Boolean = true,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val items = readiness.items(context)
    val outstanding = items.filterNot { it.satisfied }
    if (outstanding.isEmpty() && !showWhenSatisfied) return

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (readiness.allGood) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (readiness.allGood) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = if (readiness.allGood) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column {
                    Text(
                        stringResource(R.string.readiness_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (readiness.allGood) stringResource(R.string.readiness_all_good)
                        else stringResource(R.string.readiness_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val rows = if (readiness.allGood) items else outstanding
            rows.forEach { item ->
                Spacer(Modifier.height(14.dp))
                ReadinessRow(item, onFix)
            }

            // Vendor skins add limits Android does not know about; saying so is
            // the difference between "it is broken" and "here is the last step".
            readiness.manufacturerCaveat?.let { brand ->
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(10.dp))
                    Column {
                        Text(
                            stringResource(R.string.readiness_manufacturer, brand),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            stringResource(R.string.readiness_manufacturer_body, brand),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadinessRow(item: ReadinessItem, onFix: (ReadinessItem.Fix) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            if (item.satisfied) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = if (item.satisfied) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(item.titleRes), style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(item.bodyRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!item.satisfied && item.fix != null) {
            TextButton(onClick = { onFix(item.fix) }) {
                Text(stringResource(R.string.readiness_fix))
            }
        }
    }
}
