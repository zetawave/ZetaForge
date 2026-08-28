package com.zetaforge.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zetaforge.app.R
import com.zetaforge.app.ui.HostUiState
import com.zetaforge.app.ui.components.LogConsole
import com.zetaforge.app.ui.theme.zetaAccents
import com.zetaforge.runtime.task.RunningTask
import com.zetaforge.sdk.ZetaLogLevel
import kotlinx.coroutines.delay

/**
 * What the app is doing, and what it has said about it.
 *
 * ### Why this screen exists at all
 * The log used to sit under the plugin list, permanently, taking a third of
 * every screen from the thing people actually opened the app for. That is the
 * right amount of space for a developer watching a run and far too much for
 * somebody who installed a plugin and wants to press it. But hiding the log
 * behind a menu would be worse in the other direction: when a run misbehaves,
 * the log *is* the app, and hunting for it through a settings tree is exactly
 * the moment somebody gives up.
 *
 * A tab resolves both. It costs nothing when it is not needed - a bottom bar
 * has room for it - it is one tap away when it is, and it can carry a badge for
 * the one case worth interrupting somebody about: something went wrong while
 * they were looking elsewhere.
 *
 * ### The order on the screen
 * The run in flight first, because it answers "is it working?" without reading
 * anything, and the console below it, because it answers "why not?" - and the
 * second question is only ever asked after the first.
 */
@Composable
fun ActivityScreen(
    state: HostUiState,
    onLevelChange: (ZetaLogLevel) -> Unit,
    onClearLogs: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(state.runningTask != null) {
            state.runningTask?.let { RunningCard(it, onStop) }
        }

        if (state.runningTask == null) {
            IdleStrip(state)
        }

        LogConsole(
            records = state.filteredLogs,
            minLevel = state.minLevel,
            onLevelChange = onLevelChange,
            onClear = onClearLogs,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 12.dp),
        )
    }
}

/**
 * The run, while it runs.
 *
 * Deliberately the largest thing on the screen: elapsed time and the plugin's
 * own progress line are what somebody is actually watching, and both change on
 * their own, which is what makes a screen feel alive rather than stuck.
 */
@Composable
private fun RunningCard(task: RunningTask, onStop: () -> Unit) {
    val accents = zetaAccents()

    // The task's own elapsed time is a computed property, so something has to
    // ask for it again; a second is the resolution anybody reads.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(task.pluginId) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = (now - task.startedAtEpochMs).coerceAtLeast(0)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(accents.success)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = task.pluginName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.activity_running_for, formatElapsed(elapsed)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_stop))
                }
            }

            val progress = task.progress
            if (progress.message.isNotBlank()) {
                Text(
                    text = progress.message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val percent = progress.percent
            if (percent != null) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                )
            } else {
                // A plugin that reports no total still gets a bar, because the
                // question it answers is "is anything happening", not "how much".
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                )
            }
        }
    }
}

/** One quiet line when nothing is running, so the tab is never blank. */
@Composable
private fun IdleStrip(state: HostUiState) {
    val issues = state.logs.count { it.level == ZetaLogLevel.ERROR }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.outline)
        )
        Text(
            text = stringResource(R.string.activity_idle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (issues > 0) {
            Text(
                text = stringResource(R.string.activity_errors, issues),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PulsingDot(color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "running")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        Modifier
            .size(10.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = alpha))
    )
}

private fun formatElapsed(millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds < 60 -> seconds.toString() + " s"
        seconds < 3600 -> (seconds / 60).toString() + " min " + (seconds % 60) + " s"
        else -> {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            hours.toString() + " h " + minutes + " min"
        }
    }
}
