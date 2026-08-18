package com.zetaforge.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zetaforge.app.ui.theme.MonoStyle
import com.zetaforge.app.ui.theme.zetaAccents
import com.zetaforge.app.R
import com.zetaforge.runtime.log.ZetaLogRecord
import com.zetaforge.sdk.ZetaLogLevel

/**
 * Terminal-like view of the structured runtime + plugin log.
 *
 * Records arrive already formatted by `ZetaLogger`; this composable only decides
 * colours, filtering and scrolling.
 */
@Composable
fun LogConsole(
    records: List<ZetaLogRecord>,
    minLevel: ZetaLogLevel,
    onLevelChange: (ZetaLogLevel) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
) {
    val accents = zetaAccents()
    val listState = rememberLazyListState()

    LaunchedEffect(records.size) {
        if (records.isNotEmpty()) listState.animateScrollToItem(records.lastIndex)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = accents.consoleBackground,
        contentColor = accents.consoleText,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.logs_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = accents.consoleText,
                )
                Spacer(Modifier.width(12.dp))
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZetaLogLevel.entries.forEach { level ->
                        FilterChip(
                            selected = level == minLevel,
                            onClick = { onLevelChange(level) },
                            label = { Text(level.name, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = accents.muted,
                                selectedContainerColor = level.color(accents).copy(alpha = 0.20f),
                                selectedLabelColor = level.color(accents),
                            ),
                            border = null,
                        )
                    }
                }
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Outlined.DeleteSweep,
                        contentDescription = stringResource(R.string.action_clear_logs),
                        tint = accents.muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (onToggleExpand != null) {
                    IconButton(onClick = onToggleExpand) {
                        Icon(
                            if (expanded) Icons.Outlined.CloseFullscreen else Icons.Outlined.OpenInFull,
                            contentDescription = stringResource(
                                if (expanded) R.string.action_collapse_logs else R.string.action_expand_logs
                            ),
                            tint = accents.muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (records.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.logs_empty),
                        style = MonoStyle,
                        color = accents.muted,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(records) { record -> LogRow(record) }
                }
            }
        }
    }
}

@Composable
private fun LogRow(record: ZetaLogRecord) {
    val accents = zetaAccents()
    val color = record.level.color(accents)
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color.copy(alpha = 0.6f))
                .padding(vertical = 2.dp)
                .fillMaxWidth()
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = record.formatted(),
            style = MonoStyle,
            color = if (record.level == ZetaLogLevel.DEBUG) accents.muted else accents.consoleText,
        )
    }
}

private fun ZetaLogLevel.color(accents: com.zetaforge.app.ui.theme.ZetaAccentColors): Color = when (this) {
    ZetaLogLevel.DEBUG -> accents.muted
    ZetaLogLevel.INFO -> accents.info
    ZetaLogLevel.WARN -> accents.warning
    ZetaLogLevel.ERROR -> accents.danger
}
