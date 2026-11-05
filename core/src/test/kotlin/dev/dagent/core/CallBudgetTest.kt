package dev.dagent.core

import dev.dagent.core.tools.CallBudget
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class CallBudgetTest {
    private var store = ""
    private var month = "2026-11"
    private fun budget(cap: Double = 10.0) = CallBudget({ store }, { store = it }, { cap }, { month })

    @Test fun capsMinutesToWhatIsLeft() {
        val b = budget()
        assertEquals(5, b.minutesAllowed(5))
        repeat(20) { b.hold("c$it", 5) } // 20 * 0.45 = 9.00 reserved
        assertEquals(11, budget().minutesAllowed(15)) // 1.00 / 0.09
        b.hold("big", 11)
        assertEquals(0, b.minutesAllowed(5))
    }

    @Test fun settleUsesRealPriceAndResetsMonthly() {
        val b = budget()
        b.hold("a", 15)
        assertEquals(1.35, b.used(), 1e-6)
        b.settle("a", 0.20, 2.0)
        assertEquals(0.20, b.used(), 1e-6)
        b.settle("a", 5.0, 50.0) // already settled: ignored
        assertEquals(0.20, b.used(), 1e-6)
        b.hold("b", 5); b.settle("b", null, 1.0)
        assertEquals(0.29, b.used(), 1e-6)
        month = "2026-12"
        assertEquals(0.0, b.used(), 1e-6)
    }

    @Test fun zeroMeansNoLimit() {
        val b = budget(0.0)
        repeat(500) { b.hold("x$it", 15) }
        assertEquals(15, b.minutesAllowed(15))
        assertTrue(b.left() > 1e9)
    }
}
