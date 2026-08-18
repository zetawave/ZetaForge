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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zetaforge.app.R
import com.zetaforge.app.ui.theme.CodeStyle
import com.zetaforge.app.ui.theme.MonoStyle
import com.zetaforge.app.ui.theme.zetaAccents
import com.zetaforge.runtime.pkg.PluginSourceFile

/**
 * Full-screen reader for the sources shipped inside a `.zeta`.
 *
 * This is the answer to "what is this plugin actually going to do?": the code
 * comes out of the very archive that was installed, not from a description.
 * Line numbers are rendered next to the text so the user can follow along.
 */
@Composable
fun CodeViewerDialog(
    pluginName: String,
    files: List<PluginSourceFile>,
    onDismiss: () -> Unit,
) {
    val accents = zetaAccents()
    var selected by remember(files) { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pluginName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.code_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }

                if (files.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.code_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    return@Column
                }

                if (files.size > 1) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        files.forEachIndexed { index, file ->
                            FilterChip(
                                selected = index == selected,
                                onClick = { selected = index },
                                label = {
                                    Text(
                                        file.entry.displayName.substringAfterLast('/'),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(),
                            )
                        }
                    }
                }

                val file = files[selected.coerceIn(files.indices)]
                Text(
                    file.entry.displayName,
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Surface(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = accents.consoleBackground,
                    contentColor = accents.consoleText,
                ) {
                    val lines = remember(file) { file.content.lines() }
                    val horizontal = rememberScrollState()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
                    ) {
                        items(lines.size) { index ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                Text(
                                    text = (index + 1).toString().padStart(3, ' '),
                                    style = CodeStyle,
                                    color = accents.muted,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = lines[index].ifEmpty { " " },
                                    style = CodeStyle,
                                    modifier = Modifier.horizontalScroll(horizontal),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                        if (file.truncated) {
                            item {
                                Text(
                                    stringResource(R.string.code_truncated),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accents.warning,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
