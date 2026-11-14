package tw.personal.holdings.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AlertsTest {
    private val tsmc = TwEquity(TwCode.parse("2330")!!, TwBoard.TWSE)

    private fun longWithStop(stop: String) = holding(draft(twStock(), "1", "600", stop = stop))

    private fun step(
        holdings: List<Holding>,
        last: String,
        at: String,
        states: Map<AlertKey, AlertState> = emptyMap(),
        low: String? = null,
        high: String? = null,
    ): AlertStep {
        val asOf = taipei(at)
        val q = quote(last, asOf = asOf, low = low, high = high)
        return stepAlerts(holdings, mapOf<Quotable, Quote>(tsmc to q), states, asOf.plusSeconds(60))
    }

    private fun AlertStep.firedLevels(): List<String> =
        fire.map { (it as AlertFiring.PriceCrossed).level.value.toPlainString() }

    @Test
    fun `同一筆報價跑兩次只發一次`() {
        val h = listOf(longWithStop("580"))

        val first = step(h, "575", "2026-11-16T10:00")
        val second = step(h, "575", "2026-11-16T10:00", states = first.next)

        assertEquals(listOf("580"), first.firedLevels())
        assertEquals(emptyList<AlertFiring>(), second.fire)
        assertEquals(first.next, second.next)
    }

    @Test
    fun `已觸發的規則在下一筆報價仍在線外時不再發`() {
        val h = listOf(longWithStop("580"))

        val first = step(h, "575", "2026-11-16T10:00")
        val later = step(h, "570", "2026-11-16T10:15", states = first.next)

        assertEquals(emptyList<AlertFiring>(), later.fire)
    }

    @Test
    fun `改停損價是新規則，舊規則從狀態表消失`() {
        val before = step(listOf(longWithStop("580")), "575", "2026-11-16T10:00")

        val after = step(listOf(longWithStop("560")), "555", "2026-11-16T10:15", states = before.next)

        assertEquals(listOf("560"), after.firedLevels())
        assertEquals(listOf("h1|STOP_LOSS|560"), after.next.keys.map { it.stableTag() })
    }

    @Test
    fun `580 與 580點0 是同一條規則`() {
        val before = step(listOf(longWithStop("580")), "575", "2026-11-16T10:00")

        val after = step(listOf(longWithStop("580.0")), "574", "2026-11-16T10:15", states = before.next)

        assertEquals(emptyList<AlertFiring>(), after.fire)
        assertEquals(before.next.keys, after.next.keys)
        assertEquals("h1|STOP_LOSS|580", after.next.keys.single().stableTag())
    }

    @Test
    fun `回升不到百分之一不重新武裝，超過才重新武裝，之後再跌破會再響`() {
        val h = listOf(longWithStop("580"))

        val fired = step(h, "575", "2026-11-16T10:00")
        val notEnough = step(h, "585.8", "2026-11-16T10:15", states = fired.next)
        val stillFired = step(h, "579", "2026-11-16T10:30", states = notEnough.next)
        val rearmed = step(h, "586", "2026-11-16T10:45", states = notEnough.next)
        val again = step(h, "579", "2026-11-16T11:00", states = rearmed.next)

        assertEquals(emptyList<AlertFiring>(), stillFired.fire)
        assertTrue(rearmed.next.values.single() is AlertState.Armed)
        assertEquals(listOf("580"), again.firedLevels())
    }

    @Test
    fun `過期報價不觸發，也不重新武裝`() {
        val h = listOf(longWithStop("580"))
        val staleAt = taipei("2026-11-13T13:30")
        val now = taipei("2026-11-16T11:00")

        val armed = stepAlerts(h, mapOf<Quotable, Quote>(tsmc to quote("570", asOf = staleAt)), emptyMap(), now)
        val fired = step(h, "575", "2026-11-16T10:00")
        val stillFired = stepAlerts(h, mapOf<Quotable, Quote>(tsmc to quote("600", asOf = staleAt)), fired.next, now)

        assertEquals(emptyList<AlertFiring>(), armed.fire)
        assertEquals(fired.next, stillFired.next)
    }

    @Test
    fun `兩次抓取之間最低價碰到停損，就算現價已彈回也觸發`() {
        val result = step(listOf(longWithStop("580")), last = "590", at = "2026-11-16T10:00", low = "579")

        assertEquals(listOf("580"), result.firedLevels())
    }

    @Test
    fun `區間低點觸發後現價已彈回超過百分之一，同一筆報價重跑多次也只響一次`() {
        val h = listOf(longWithStop("580"))

        val first = step(h, last = "590", at = "2026-11-16T10:00", low = "579")
        val second = step(h, last = "590", at = "2026-11-16T10:00", low = "579", states = first.next)
        val third = step(h, last = "590", at = "2026-11-16T10:00", low = "579", states = second.next)

        assertEquals(1, (first.fire + second.fire + third.fire).size)
    }

    @Test
    fun `沒有區間高低時退回用現價判斷`() {
        val result = step(listOf(longWithStop("580")), last = "590", at = "2026-11-16T10:00")

        assertEquals(emptyList<AlertFiring>(), result.fire)
    }

    @Test
    fun `空單的停損在上方、目標在下方`() {
        val short = holding(draft(twStock(), "1", "600", side = Side.SHORT, stop = "620", target = "550"))

        val stopHit = step(listOf(short), last = "610", at = "2026-11-16T10:00", high = "621")
        val targetHit = step(listOf(short), last = "549", at = "2026-11-16T10:00")

        assertEquals(listOf(AlertKind.STOP_LOSS), stopHit.fire.map { it.key.kind })
        assertEquals(listOf(AlertKind.TARGET), targetHit.fire.map { it.key.kind })
    }

    @Test
    fun `轉倉提醒在最後交易日前 3 個日曆日發一次`() {
        val h = listOf(holding(draft(tx("2026-12"), "1", "23000")))
        fun run(at: String, states: Map<AlertKey, AlertState>) = stepAlerts(h, emptyMap(), states, taipei(at))

        val early = run("2026-12-12T09:00", emptyMap())
        val due = run("2026-12-13T09:00", early.next)
        val again = run("2026-12-14T09:00", due.next)

        assertEquals(emptyList<AlertFiring>(), early.fire)
        val rollover = due.fire.single() as AlertFiring.Rollover
        assertEquals(LocalDate.of(2026, 12, 16), rollover.lastTradingDay)
        assertEquals("h1|ROLLOVER|2026-12", rollover.key.stableTag())
        assertEquals(emptyList<AlertFiring>(), again.fire)
    }

    @Test
    fun `刪掉的持股與到期的合約不再有規則`() {
        val fired = step(listOf(longWithStop("580")), "575", "2026-11-16T10:00")
        val expired = holding(
            draft(tx("2026-10"), "1", "23000", stop = "22000", entryDate = LocalDate.of(2026, 9, 1)),
            today = LocalDate.of(2026, 10, 1),
        )

        val result = stepAlerts(listOf(expired), emptyMap(), fired.next, Instant.parse("2026-11-16T02:00:00Z"))

        assertEquals(emptyMap<AlertKey, AlertState>(), result.next)
        assertEquals(emptyList<AlertFiring>(), result.fire)
    }
}
