package com.zetaforge.plugins.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The calculator's behaviour, tested where it is cheapest to test: on the JVM,
 * with no device, no Host and no Compose.
 *
 * This is half of why a screen plugin should keep its state machine out of its
 * composables. The other half is that these are exactly the cases a person
 * notices immediately and a screenshot never catches.
 */
class CalculatorEngineTest {

    private fun run(vararg keys: Key): CalculatorState =
        keys.fold(CalculatorState()) { state, key -> Calculator.press(state, key) }

    private fun digits(text: String): List<Key> = text.map { char ->
        if (char == '.') Key.Dot else Key.Digit(char - '0')
    }

    private fun type(text: String, state: CalculatorState = CalculatorState()): CalculatorState =
        digits(text).fold(state) { acc, key -> Calculator.press(acc, key) }

    @Test
    fun `adds without binary floating point error`() {
        // The reason the engine is BigDecimal: as Double this is
        // 0.30000000000000004, and a calculator that prints that is broken.
        var state = type("0.1")
        state = Calculator.press(state, Key.Operation(Op.ADD))
        state = type("0.2", state)
        state = Calculator.press(state, Key.Equals)

        assertEquals("0.3", state.entry)
    }

    @Test
    fun `chains operations, applying the pending one at each operator`() {
        var state = type("2")
        state = Calculator.press(state, Key.Operation(Op.ADD))
        state = type("3", state)
        // 2 + 3 is settled here, before the multiplication is even entered.
        state = Calculator.press(state, Key.Operation(Op.MULTIPLY))
        assertEquals("5", state.entry)

        state = type("4", state)
        state = Calculator.press(state, Key.Equals)
        assertEquals("20", state.entry)
    }

    @Test
    fun `a second operator replaces the first instead of applying it`() {
        var state = type("7")
        state = Calculator.press(state, Key.Operation(Op.ADD))
        state = Calculator.press(state, Key.Operation(Op.MULTIPLY))
        state = type("3", state)
        state = Calculator.press(state, Key.Equals)

        // The user corrected themselves: 7 x 3, not 7 + 7 x 3.
        assertEquals("21", state.entry)
    }

    @Test
    fun `equals repeats the last operation and operand`() {
        var state = type("100")
        state = Calculator.press(state, Key.Operation(Op.DIVIDE))
        state = type("2", state)
        state = Calculator.press(state, Key.Equals)
        assertEquals("50", state.entry)

        state = Calculator.press(state, Key.Equals)
        assertEquals("25", state.entry)
        state = Calculator.press(state, Key.Equals)
        assertEquals("12.5", state.entry)
    }

    @Test
    fun `dividing by zero is a state, not an exception`() {
        var state = type("5")
        state = Calculator.press(state, Key.Operation(Op.DIVIDE))
        state = type("0", state)
        state = Calculator.press(state, Key.Equals)

        assertEquals("Cannot divide by zero", state.error)

        // And the next digit starts over rather than needing AC first.
        state = Calculator.press(state, Key.Digit(7))
        assertNull(state.error)
        assertEquals("7", state.entry)
    }

    @Test
    fun `a division that does not terminate is rounded, not refused`() {
        var state = type("1")
        state = Calculator.press(state, Key.Operation(Op.DIVIDE))
        state = type("3", state)
        state = Calculator.press(state, Key.Equals)

        assertNull(state.error)
        assertEquals("0.333333333333", state.entry)
    }

    @Test
    fun `a second decimal point is ignored`() {
        assertEquals("1.5", type("1.5.5").entry)
    }

    @Test
    fun `a digit after a result starts a new number`() {
        var state = type("2")
        state = Calculator.press(state, Key.Operation(Op.ADD))
        state = type("2", state)
        state = Calculator.press(state, Key.Equals)
        state = Calculator.press(state, Key.Digit(9))

        assertEquals("9", state.entry)
    }

    @Test
    fun `entry is capped so the display cannot overflow`() {
        val state = type("1234567890123456789")
        assertEquals(12, state.entry.count { it.isDigit() })
    }

    @Test
    fun `percent after plus is a percentage of the accumulator`() {
        var state = type("200")
        state = Calculator.press(state, Key.Operation(Op.ADD))
        state = type("10", state)
        state = Calculator.press(state, Key.Percent)
        state = Calculator.press(state, Key.Equals)

        // What a desk calculator does: 200 + 10% of 200.
        assertEquals("220", state.entry)
    }

    @Test
    fun `percent on its own is a hundredth`() {
        var state = type("50")
        state = Calculator.press(state, Key.Percent)
        assertEquals("0.5", state.entry)
    }

    @Test
    fun `negate leaves a lone zero alone`() {
        assertEquals("0", run(Key.Negate).entry)
        assertEquals("-4", type("4").let { Calculator.press(it, Key.Negate) }.entry)
    }

    @Test
    fun `backspace removes the last digit and stops at zero`() {
        var state = type("12")
        state = Calculator.press(state, Key.Backspace)
        assertEquals("1", state.entry)
        state = Calculator.press(state, Key.Backspace)
        assertEquals("0", state.entry)
        state = Calculator.press(state, Key.Backspace)
        assertEquals("0", state.entry)
    }

    @Test
    fun `the pending line shows the operation waiting for its operand`() {
        var state = type("12")
        state = Calculator.press(state, Key.Operation(Op.MULTIPLY))
        assertEquals("12 ×", state.expression)

        state = Calculator.press(state, Key.Clear)
        assertEquals("", state.expression)
    }

    // --- formatting ---------------------------------------------------------

    @Test
    fun `thousands are grouped with a separator that cannot wrap`() {
        assertEquals("987 654 321", Formatter.display("987654321", true, null))
        assertEquals("987654321", Formatter.display("987654321", false, null))
    }

    @Test
    fun `the decimal point survives while it is the last thing typed`() {
        // Otherwise "2." flickers back to "2" under the user's finger.
        assertEquals("2.", Formatter.display("2.", false, null))
    }

    @Test
    fun `a fixed number of decimals rounds rather than truncates`() {
        assertEquals("1.24", Formatter.display("1.235", false, 2))
        assertEquals("0.33", Formatter.display("0.333333", false, 2))
    }

    @Test
    fun `grouping and rounding apply to negative numbers too`() {
        assertEquals("-1 234.6", Formatter.display("-1234.55", true, 1))
    }
}
