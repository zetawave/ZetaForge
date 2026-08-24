package com.zetaforge.plugins.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The calculator itself: a pure state machine, with no Android and no Compose
 * anywhere in it.
 *
 * Kept separate from the screen on purpose. Everything that is easy to get
 * subtly wrong in a calculator - what a second operator does to a pending one,
 * what `=` repeats, what a decimal point means when there is already one - lives
 * here, where it is ordinary Kotlin that a plugin author can unit test off the
 * device.
 *
 * Arithmetic is [BigDecimal], not [Double]. A calculator that answers
 * `0.30000000000000004` to `0.1 + 0.2` is not a rounding curiosity, it is a
 * broken calculator.
 */
internal data class CalculatorState(
    /** What the display shows, always as a string: `-`, `2.` and `0` all matter. */
    val entry: String = "0",
    /** The left-hand side of a pending operation, if there is one. */
    val accumulator: BigDecimal? = null,
    /** The operation waiting for its right-hand side. */
    val pending: Op? = null,
    /** True while [entry] still shows a result rather than something being typed. */
    val showingResult: Boolean = true,
    /** The last (operation, operand) pair, so `=` can be pressed repeatedly. */
    val repeat: Pair<Op, BigDecimal>? = null,
    /** Set when the last action could not produce a number. */
    val error: String? = null,
) {
    /** The line above the display: what is pending, in the user's own terms. */
    val expression: String
        get() = when {
            error != null -> ""
            accumulator != null && pending != null -> Formatter.plain(accumulator) + " " + pending.symbol
            else -> ""
        }
}

/** The four operations, plus the symbol the display uses for each. */
internal enum class Op(val symbol: String) {
    ADD("+"), SUBTRACT("−"), MULTIPLY("×"), DIVIDE("÷");

    fun apply(left: BigDecimal, right: BigDecimal): BigDecimal = when (this) {
        ADD -> left.add(right, MATH)
        SUBTRACT -> left.subtract(right, MATH)
        MULTIPLY -> left.multiply(right, MATH)
        // Exact division would throw on 1/3; a fixed context is what a
        // calculator does, and stripping the trailing zeros afterwards keeps
        // 6/2 showing as 3 rather than 3.00000000000.
        DIVIDE -> left.divide(right, MATH).stripTrailingZeros()
    }
}

/** Twelve significant digits: what fits on a phone without looking truncated. */
private val MATH = MathContext(12, RoundingMode.HALF_UP)

/** The most digits a single entry accepts, so the display cannot be overflowed. */
private const val MAX_DIGITS = 12

/**
 * Every button on the calculator, as one closed set.
 *
 * The screen maps buttons to these and does nothing else: no arithmetic decision
 * is taken in a composable, which is what keeps the UI free of state that could
 * disagree with [CalculatorState].
 */
internal sealed interface Key {
    data class Digit(val value: Int) : Key
    data object Dot : Key
    data class Operation(val op: Op) : Key
    data object Equals : Key
    data object Clear : Key
    data object Backspace : Key
    data object Negate : Key
    data object Percent : Key
}

internal object Calculator {

    /**
     * Applies one key press.
     *
     * Total: every key is valid in every state, including the error one, where
     * the only thing that clears the error is producing a new number - which is
     * why each branch that starts fresh checks for it rather than the caller.
     */
    fun press(state: CalculatorState, key: Key): CalculatorState = when (key) {
        is Key.Digit -> digit(state, key.value)
        Key.Dot -> dot(state)
        is Key.Operation -> operation(state, key.op)
        Key.Equals -> equals(state)
        Key.Clear -> CalculatorState()
        Key.Backspace -> backspace(state)
        Key.Negate -> negate(state)
        Key.Percent -> percent(state)
    }

    private fun digit(state: CalculatorState, value: Int): CalculatorState {
        // A digit after a result starts a new number: it does not append to the
        // answer that is still on screen.
        val fresh = state.showingResult || state.error != null
        val current = if (fresh) "" else state.entry
        val digits = current.count { it.isDigit() }
        if (digits >= MAX_DIGITS) return state.copy(error = null)
        val next = when {
            current.isEmpty() || current == "0" -> value.toString()
            current == "-0" -> "-" + value
            else -> current + value
        }
        return state.copy(entry = next, showingResult = false, error = null)
    }

    private fun dot(state: CalculatorState): CalculatorState {
        if (state.showingResult || state.error != null) {
            return state.copy(entry = "0.", showingResult = false, error = null)
        }
        // A second dot is a no-op rather than an error: it is a mis-tap, and
        // punishing a mis-tap by clearing the entry is worse than ignoring it.
        if (state.entry.contains('.')) return state
        return state.copy(entry = state.entry + ".")
    }

    private fun operation(state: CalculatorState, op: Op): CalculatorState {
        if (state.error != null) return CalculatorState(pending = op, accumulator = BigDecimal.ZERO)
        val typed = state.entry.toBigDecimalOrNull() ?: BigDecimal.ZERO

        // Pressing + then × means × : the user corrected themselves, they did
        // not ask for two operations.
        if (state.showingResult && state.accumulator != null && state.pending != null) {
            return state.copy(pending = op)
        }

        val left = if (state.accumulator != null && state.pending != null) {
            val result = runCatching { state.pending.apply(state.accumulator, typed) }
                .getOrElse { return errorState(it) }
            result
        } else {
            typed
        }
        return CalculatorState(
            entry = Formatter.plain(left),
            accumulator = left,
            pending = op,
            showingResult = true,
        )
    }

    private fun equals(state: CalculatorState): CalculatorState {
        if (state.error != null) return state

        // Pressing = again repeats the last operation against the last operand,
        // which is what every physical calculator does and what people expect
        // when they want to halve a number three times.
        if (state.pending == null || state.accumulator == null) {
            val repeat = state.repeat ?: return state.copy(showingResult = true)
            val current = state.entry.toBigDecimalOrNull() ?: return state
            val result = runCatching { repeat.first.apply(current, repeat.second) }
                .getOrElse { return errorState(it) }
            return state.copy(
                entry = Formatter.plain(result),
                showingResult = true,
            )
        }

        val right = state.entry.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val result = runCatching { state.pending.apply(state.accumulator, right) }
            .getOrElse { return errorState(it) }
        return CalculatorState(
            entry = Formatter.plain(result),
            showingResult = true,
            repeat = state.pending to right,
        )
    }

    private fun backspace(state: CalculatorState): CalculatorState {
        if (state.error != null) return CalculatorState()
        // Backspace on a result clears it: there is nothing meaningful about
        // deleting the last digit of an answer.
        if (state.showingResult) return state.copy(entry = "0")
        val trimmed = state.entry.dropLast(1)
        val next = if (trimmed.isEmpty() || trimmed == "-") "0" else trimmed
        return state.copy(entry = next, showingResult = false)
    }

    private fun negate(state: CalculatorState): CalculatorState {
        if (state.error != null) return state
        if (state.entry == "0" || state.entry == "0.") return state
        val next = if (state.entry.startsWith("-")) state.entry.drop(1) else "-" + state.entry
        return state.copy(entry = next)
    }

    /**
     * Percent, in the way a calculator means it.
     *
     * With a pending `+` or `−` it is a percentage *of the accumulator*, so
     * `200 + 10 %` is 220. With `×`, `÷` or nothing pending it is simply a
     * hundredth. Both are what the buttons on a desk calculator do, and the
     * difference is the part people notice when it is wrong.
     */
    private fun percent(state: CalculatorState): CalculatorState {
        if (state.error != null) return state
        val typed = state.entry.toBigDecimalOrNull() ?: return state
        val hundredth = typed.divide(BigDecimal(100), MATH)
        val value = when (state.pending) {
            Op.ADD, Op.SUBTRACT -> state.accumulator?.multiply(hundredth, MATH) ?: hundredth
            else -> hundredth
        }
        return state.copy(entry = Formatter.plain(value.stripTrailingZeros()), showingResult = false)
    }

    /**
     * Division by zero is the only failure a calculator can actually produce,
     * and it is a state, not an exception: the display says so and the next
     * digit starts over.
     */
    private fun errorState(cause: Throwable): CalculatorState = CalculatorState(
        entry = "0",
        error = if (cause is ArithmeticException) "Cannot divide by zero" else "Out of range",
    )
}

/** Turns the internal [BigDecimal] into what the display and the pending line show. */
internal object Formatter {

    /** Unformatted, for round-tripping a value back into [CalculatorState.entry]. */
    fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    /**
     * What the user reads.
     *
     * Both settings the plugin declares end up here, which is the point of the
     * example: a screen reads its configuration through the same `Bundle` a
     * scheduled run would get, and does not invent a second way to be configured.
     */
    fun display(raw: String, groupThousands: Boolean, decimals: Int?): String {
        if (raw.isEmpty()) return "0"
        val negative = raw.startsWith("-")
        val body = if (negative) raw.drop(1) else raw
        val hasTrailingDot = body.endsWith(".")
        var integer = body.substringBefore('.')
        var fraction = body.substringAfter('.', "")

        if (decimals != null && fraction.isNotEmpty()) {
            val rounded = runCatching {
                BigDecimal(body).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
            }.getOrDefault(body)
            integer = rounded.substringBefore('.')
            fraction = rounded.substringAfter('.', "")
        }

        val groupedInteger = if (groupThousands) group(integer) else integer
        return buildString {
            if (negative) append('-')
            append(groupedInteger)
            // The dot survives while it is the last thing typed, otherwise "2."
            // would flicker back to "2" under the user's finger.
            if (fraction.isNotEmpty()) append('.').append(fraction)
            else if (hasTrailingDot) append('.')
        }
    }

    private fun group(digits: String): String {
        if (digits.length <= 3) return digits
        return digits.reversed()
            .chunked(3)
            .joinToString(" ")   // narrow no-break space: a separator that never wraps
            .reversed()
    }
}
