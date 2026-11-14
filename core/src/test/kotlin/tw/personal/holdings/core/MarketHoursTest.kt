package tw.personal.holdings.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MarketHoursTest {
    private val txDec = TwFuture(IndexFuture.TX, ContractMonth.of(2026, 12))
    private val tsmc = TwEquity(TwCode.parse("2330")!!, TwBoard.TWSE)
    private val aapl = UsEquity(UsTicker.parse("AAPL")!!)
    private val btc = Crypto(CoinSymbol.parse("BTC")!!)

    @Test
    fun `期貨 15 點以後的成交算下一個交易日，週五夜盤算到下週一`() {
        assertEquals(LocalDate.of(2026, 11, 16), MarketHours.tradingDay(txDec, taipei("2026-11-16T14:59")))
        assertEquals(LocalDate.of(2026, 11, 17), MarketHours.tradingDay(txDec, taipei("2026-11-16T15:00")))
        assertEquals(LocalDate.of(2026, 11, 17), MarketHours.tradingDay(txDec, taipei("2026-11-17T04:30")))
        assertEquals(LocalDate.of(2026, 11, 23), MarketHours.tradingDay(txDec, taipei("2026-11-20T21:00")))
        assertEquals(LocalDate.of(2026, 11, 23), MarketHours.tradingDay(txDec, taipei("2026-11-21T03:00")))
        assertEquals(LocalDate.of(2026, 11, 16), MarketHours.tradingDay(tsmc, taipei("2026-11-16T20:00")))
    }

    @Test
    fun `台股盤中近的報價是即時，太舊的是過期，收盤後當天的是收盤價`() {
        fun at(asOf: String, now: String) = staleness(tsmc, quote("600", asOf = taipei(asOf)), taipei(now))

        assertEquals(Staleness.LIVE, at("2026-11-16T10:50", "2026-11-16T11:00"))
        assertEquals(Staleness.STALE, at("2026-11-16T09:30", "2026-11-16T11:00"))
        assertEquals(Staleness.STALE, at("2026-11-13T13:30", "2026-11-16T11:00"))
        assertEquals(Staleness.FINAL, at("2026-11-16T13:30", "2026-11-16T20:00"))
        assertEquals(Staleness.FINAL, at("2026-11-13T13:30", "2026-11-15T20:00"))
        assertEquals(Staleness.STALE, at("2026-11-12T13:30", "2026-11-15T20:00"))
    }

    @Test
    fun `美股時段跟著紐約夏令時間走`() {
        val sameUtcClock = "T13:40:00Z"
        val summer = Instant.parse("2026-07-15$sameUtcClock")
        val winter = Instant.parse("2026-12-16$sameUtcClock")
        val yesterdaysClose = { now: Instant -> quote("300", asOf = now.minusSeconds(22 * 3600)) }

        assertEquals(Staleness.STALE, staleness(aapl, yesterdaysClose(summer), summer))
        assertEquals(Staleness.FINAL, staleness(aapl, yesterdaysClose(winter), winter))
        assertEquals(Staleness.LIVE, staleness(aapl, quote("300", asOf = summer.minusSeconds(300)), summer))
    }

    @Test
    fun `僅收盤價的報價盤中算過期，收盤後才是收盤價；夜盤不影響它`() {
        val eod = quote("23000", asOf = taipei("2026-11-16T13:45"), freshness = Freshness.END_OF_DAY)
        val yesterday = quote("23000", asOf = taipei("2026-11-13T13:45"), freshness = Freshness.END_OF_DAY)

        assertEquals(Staleness.FINAL, staleness(txDec, eod, taipei("2026-11-16T22:00")))
        assertEquals(Staleness.STALE, staleness(txDec, yesterday, taipei("2026-11-16T10:00")))
    }

    @Test
    fun `指數期貨夜盤的報價是即時，夜盤收盤後是收盤價`() {
        assertEquals(Staleness.LIVE, staleness(txDec, quote("23000", asOf = taipei("2026-11-16T21:50")), taipei("2026-11-16T22:00")))
        assertEquals(Staleness.FINAL, staleness(txDec, quote("23000", asOf = taipei("2026-11-17T04:59")), taipei("2026-11-17T06:00")))
    }

    @Test
    fun `加密貨幣 24 小時，只看報價多舊`() {
        val now = taipei("2026-11-15T03:00")
        assertEquals(Staleness.LIVE, staleness(btc, quote("68000", asOf = now.minusSeconds(600)), now))
        assertEquals(Staleness.STALE, staleness(btc, quote("68000", asOf = now.minusSeconds(7200)), now))
    }
}
