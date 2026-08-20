package com.zetaforge.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zetaforge.app.R
import com.zetaforge.app.ui.ReadinessItem
import com.zetaforge.app.ui.SystemReadiness
import com.zetaforge.app.ui.components.ReadinessPanel

/**
 * The first run.
 *
 * Four steps, and the third is the one that earns its place: it asks for the two
 * system settings a scheduled run depends on, at the only moment the user is
 * receptive to being asked — before anything has silently failed.
 *
 * The second step says plainly that a plugin is not sandboxed. Burying that
 * would make the app feel friendlier and the user worse informed.
 */
@Composable
fun OnboardingScreen(
    readiness: SystemReadiness?,
    onFix: (ReadinessItem.Fix) -> Unit,
    onFinish: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    val last = 3

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    )
                )
            )
            .systemBarsPadding(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 620.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
                    .align(Alignment.Center)
                    .padding(horizontal = 26.dp, vertical = 18.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (step < last) {
                        TextButton(onClick = onFinish) { Text(stringResource(R.string.onboarding_skip)) }
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    AnimatedContent(
                        targetState = step,
                        transitionSpec = {
                            androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.fadeOut()
                        },
                        label = "onboarding",
                    ) { current ->
                        when (current) {
                            0 -> WelcomeStep(compact)
                            1 -> TrustStep()
                            2 -> ScheduleStep(readiness, onFix)
                            else -> ReadyStep()
                        }
                    }
                }

                Dots(step, last + 1)
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (step > 0) {
                        TextButton(onClick = { step-- }) { Text(stringResource(R.string.onboarding_back)) }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { if (step == last) onFinish() else step++ }) {
                        Text(
                            stringResource(
                                if (step == last) R.string.onboarding_done else R.string.onboarding_next
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(compact: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.zeta_wordmark),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
            modifier = Modifier
                .fillMaxWidth(if (compact) 0.42f else 0.55f)
                .padding(bottom = 26.dp),
        )
        Text(
            stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TrustStep() {
    Column {
        StepTitle(stringResource(R.string.onboarding_trust_title))
        Text(
            stringResource(R.string.onboarding_trust_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        Point(Icons.Outlined.Code, stringResource(R.string.onboarding_trust_point_source))
        Point(Icons.Outlined.Lock, stringResource(R.string.onboarding_trust_point_permissions))
        Point(Icons.Outlined.StopCircle, stringResource(R.string.onboarding_trust_point_stop))
    }
}

@Composable
private fun ScheduleStep(readiness: SystemReadiness?, onFix: (ReadinessItem.Fix) -> Unit) {
    Column {
        StepTitle(stringResource(R.string.onboarding_schedule_title))
        Text(
            stringResource(R.string.onboarding_schedule_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        readiness?.let { ReadinessPanel(readiness = it, onFix = onFix) }
    }
}

@Composable
private fun ReadyStep() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Outlined.Bolt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.onboarding_ready_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_ready_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Point(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Dots(current: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(total) { index ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (index == current) 9.dp else 7.dp)
                    .background(
                        if (index == current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
            )
        }
    }
}
