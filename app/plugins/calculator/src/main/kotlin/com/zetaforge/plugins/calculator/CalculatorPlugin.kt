package com.zetaforge.plugins.calculator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zetaforge.sdk.ZetaLog
import com.zetaforge.sdk.ui.ZetaUiHost
import com.zetaforge.sdk.ui.ZetaUiPlugin

/**
 * The reference plugin for screens.
 *
 * A calculator was chosen because it exercises the whole screen path and
 * nothing else: it takes touch input, keeps state across recompositions, reads
 * its settings, uses the Host's Material theme, and needs no permission, no
 * network and no storage. If it works, what works is the mechanism.
 *
 * Note what is *not* here: no `Activity`, no layout XML, no `R` class, no
 * manifest of its own. The plugin supplies a composable, the Host supplies
 * everywhere to put it - which is the only arrangement Android allows for code
 * loaded from a file the system has never seen.
 *
 * Written as a screen only ([ui { only = true }] in the build file), so the Host
 * shows OPEN and hides RUN and SCHEDULE. `execute` still exists, because the
 * contract has one; the default implementation says what it is and does nothing.
 */
class CalculatorPlugin : ZetaUiPlugin {

    override val id: String = "com.zetaforge.plugins.calculator"
    override val name: String = "Calculator"
    override val version: String = "1.0.0"

    @Composable
    override fun Content(host: ZetaUiHost) {
        // Settings come through the same Bundle a scheduled run would receive,
        // so a plugin never has two ways of being configured.
        val groupThousands = remember { host.settings.getBoolean("thousands", true) }
        val decimals = remember {
            when (val precision = host.settings.getString("precision", "auto")) {
                "auto", null, "" -> null
                else -> precision.toIntOrNull()
            }
        }

        // `remember`, deliberately not `rememberSaveable`.
        //
        // Saved instance state is written into a Bundle and read back by the
        // *Host's* class loader, which has never heard of CalculatorState: a
        // screen that saved its own types there would restore into a
        // ClassNotFoundException. Rotation is already handled - the container
        // Activity declares configChanges - so what is actually being given up
        // is state across process death, and a lost half-typed sum is a far
        // better outcome than a crash on the way back.
        //
        // A screen with state worth keeping should write it somewhere it owns,
        // exactly as a job would.
        var state by remember { mutableStateOf(CalculatorState()) }

        fun press(key: Key) {
            state = Calculator.press(state, key)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Display(
                state = state,
                groupThousands = groupThousands,
                decimals = decimals,
                modifier = Modifier.weight(1f),
            )
            Keypad(onKey = ::press, modifier = Modifier.fillMaxWidth())
        }

        // Proof that the second line of the Host's title bar is the plugin's to
        // write, and the first line is not.
        LaunchedEffect(state.error) {
            host.setSubtitle(state.error)
            state.error?.let { ZetaLog.warn(host.pluginId, "Calculator", it) }
        }
    }
}

/** The number, what is pending above it, and the error instead of both. */
@Composable
private fun Display(
    state: CalculatorState,
    groupThousands: Boolean,
    decimals: Int?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = state.expression,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.error ?: Formatter.display(state.entry, groupThousands, decimals),
            // The number is the screen: it gets the largest type on it, and
            // shrinks rather than wrapping when it runs out of room.
            fontSize = if (state.error != null) 26.sp else 52.sp,
            lineHeight = if (state.error != null) 32.sp else 58.sp,
            color = if (state.error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onBackground
            },
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The buttons.
 *
 * A fixed five-row grid rather than a `LazyVerticalGrid`: there are exactly
 * twenty of them, they all fit, and none of them ever scrolls.
 */
@Composable
private fun Keypad(onKey: (Key) -> Unit, modifier: Modifier = Modifier) {
    val rows: List<List<Button>> = listOf(
        listOf(
            Button.Function("AC", Key.Clear),
            Button.Function("±", Key.Negate),
            Button.Function("%", Key.Percent),
            Button.Operator("÷", Key.Operation(Op.DIVIDE)),
        ),
        listOf(
            Button.Number("7", 7), Button.Number("8", 8), Button.Number("9", 9),
            Button.Operator("×", Key.Operation(Op.MULTIPLY)),
        ),
        listOf(
            Button.Number("4", 4), Button.Number("5", 5), Button.Number("6", 6),
            Button.Operator("−", Key.Operation(Op.SUBTRACT)),
        ),
        listOf(
            Button.Number("1", 1), Button.Number("2", 2), Button.Number("3", 3),
            Button.Operator("+", Key.Operation(Op.ADD)),
        ),
        listOf(
            Button.Function("⌫", Key.Backspace),
            Button.Number("0", 0),
            Button.Function(".", Key.Dot),
            Button.Accent("=", Key.Equals),
        ),
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { button ->
                    CalculatorButton(
                        button = button,
                        onClick = { onKey(button.key) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** One button, and which of the three roles it plays. */
private sealed interface Button {
    val label: String
    val key: Key

    data class Number(override val label: String, val digit: Int) : Button {
        override val key: Key get() = Key.Digit(digit)
    }

    data class Operator(override val label: String, override val key: Key) : Button
    data class Function(override val label: String, override val key: Key) : Button
    data class Accent(override val label: String, override val key: Key) : Button
}

@Composable
private fun CalculatorButton(
    button: Button,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Colours come from the Host's theme, so a plugin screen looks like part of
    // the app it is running inside rather than a web page someone embedded.
    val scheme = MaterialTheme.colorScheme
    val container: Color = when (button) {
        is Button.Number -> scheme.surfaceVariant
        is Button.Function -> scheme.secondaryContainer
        is Button.Operator -> scheme.primaryContainer
        is Button.Accent -> scheme.primary
    }
    val content: Color = when (button) {
        is Button.Number -> scheme.onSurface
        is Button.Function -> scheme.onSecondaryContainer
        is Button.Operator -> scheme.onPrimaryContainer
        is Button.Accent -> scheme.onPrimary
    }

    Surface(
        modifier = modifier.height(62.dp),
        shape = RoundedCornerShape(18.dp),
        color = container,
        contentColor = content,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = button.label,
                fontSize = if (button is Button.Number) 24.sp else 22.sp,
            )
        }
    }
}
