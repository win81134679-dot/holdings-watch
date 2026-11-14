package tw.personal.holdings.quotes

import kotlinx.coroutines.runBlocking
import tw.personal.holdings.core.ContractMonth
import tw.personal.holdings.core.FetchFailure
import tw.personal.holdings.core.Freshness
import tw.personal.holdings.core.IndexFuture
import tw.personal.holdings.core.Quotable
import tw.personal.holdings.core.SourceCapability
import tw.personal.holdings.core.StockFuture
import tw.personal.holdings.core.StockFutureSize
import tw.personal.holdings.core.TwCode
import java.time.Instant
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/**
 * 期貨頁與期交所的路由。這些測試沒有登記走勢圖服務（libra）的網址，所以 libra 那一步都是連不上，
 * 路由往下走到期貨頁；斷言只看 libra 之後的請求。libra 本身的行為在 YahooLibraTest。
 */
class FuturesRoutingTest {
    private fun FakeHttp.pageCalls() = calls.filterNot { it.startsWith(LIBRA_PREFIX) }

    @Test fun `libra answers the held contracts in one request and the page is not fetched`() {
        val http = FakeHttp(mapOf(libraUrlOf("WTX&", "WMT@") to ok(fixture("yahoo_tw_libra_tick.json"))))
        val tx = future(IndexFuture.TX, 2026, 11)
        val mtx = future(IndexFuture.MTX, 2026, 12)
        val got = fetch(saturdayNight, http, tx, mtx)
        assertEquals(listOf(libraUrlOf("WTX&", "WMT@")), http.calls)
        assertEquals(price("43624"), got.getValue(tx).quote().last)
        assertEquals(price("43680"), got.getValue(mtx).quote().last)
    }

    @Test fun `libra down falls back to the page`() {
        val http = FakeHttp(mapOf(futurePageUrl("WTX&") to ok(wtxPage)))
        val tx = future(IndexFuture.TX, 2026, 11)
        fetch(saturdayNight, http, tx)
        assertEquals(listOf(libraUrlOf("WTX&"), futurePageUrl("WTX&")), http.calls)
    }

    private val saturdayNight = taipei("2026-11-14T01:45:00")

    private val wtxPage = fixtureText("yahoo_tw_future_WTX&.html")
    private val wmtPage = fixtureText("yahoo_tw_future_WMT@.html")
    private val taifex = ok(fixture("taifex_DailyMarketReportFut.json"))

    private fun fetch(
        now: Instant,
        http: HttpGet,
        vararg items: Quotable,
        since: Instant? = null,
    ) = runBlocking { QuoteFetchers.create(http, { now }, {}).fetch(items.toSet()) { since } }

    private fun capability(now: Instant, x: Quotable) =
        QuoteFetchers.create(FakeHttp(), { now }, {}).capabilityOf(x)

    // 2026-11 的最後交易日是 11/18（週三）13:30。

    @Test fun `front months follow the last trading day rule`() {
        assertEquals(ContractMonth.of(2026, 11) to ContractMonth.of(2026, 12), frontMonths(taipei("2026-11-18T13:29:59")))
        assertEquals(ContractMonth.of(2026, 12) to ContractMonth.of(2027, 1), frontMonths(taipei("2026-11-19T15:00:00")))
    }

    @Test fun `between the last trading day close and the next day 15 00 the near month is unknown`() {
        assertNull(frontMonths(taipei("2026-11-18T13:30:00")))
        assertNull(frontMonths(taipei("2026-11-18T20:00:00")))
        assertNull(frontMonths(taipei("2026-11-19T14:59:59")))
    }

    @Test fun `near month TX page gives last previousClose asOf and LIVE`() {
        val http = FakeHttp(mapOf(futurePageUrl("WTX&") to ok(wtxPage)))
        val x = future(IndexFuture.TX, 2026, 11)
        val q = fetch(saturdayNight, http, x).getValue(x).quote()
        assertEquals(listOf(futurePageUrl("WTX&")), http.pageCalls())
        assertEquals(price("43423"), q.last)
        assertEquals(price("43839"), q.previousClose)
        assertEquals(Instant.parse("2026-11-13T17:41:42Z"), q.asOf)
        assertEquals(Freshness.LIVE, q.freshness)
    }

    @Test fun `page low and high are the day range only when since is null`() {
        val http = FakeHttp(mapOf(futurePageUrl("WTX&") to ok(wtxPage)))
        val x = future(IndexFuture.TX, 2026, 11)
        val first = fetch(saturdayNight, http, x).getValue(x).quote()
        assertEquals(price("42956"), first.low)
        assertEquals(price("44423"), first.high)
        val later = fetch(saturdayNight, http, x, since = saturdayNight).getValue(x).quote()
        assertNull(later.low)
        assertNull(later.high)
    }

    @Test fun `second month MTX uses the at-sign page`() {
        val http = FakeHttp(mapOf(futurePageUrl("WMT@") to ok(wmtPage)))
        val x = future(IndexFuture.MTX, 2026, 12)
        val q = fetch(saturdayNight, http, x).getValue(x).quote()
        assertEquals(listOf(futurePageUrl("WMT@")), http.pageCalls())
        assertEquals(price("43472"), q.last)
        assertEquals(price("43917"), q.previousClose)
    }

    @Test fun `one page answers near and far months of TX and MTX`() {
        val http = FakeHttp(mapOf(futurePageUrl("WTX&") to ok(wtxPage)))
        val txNear = future(IndexFuture.TX, 2026, 11)
        val txFar = future(IndexFuture.TX, 2026, 12)
        val mtxNear = future(IndexFuture.MTX, 2026, 11)
        val mtxFar = future(IndexFuture.MTX, 2026, 12)
        val r = fetch(saturdayNight, http, txNear, txFar, mtxNear, mtxFar)
        assertEquals(listOf(futurePageUrl("WTX&")), http.pageCalls())
        assertEquals(price("43423"), r.getValue(txNear).quote().last)
        assertEquals(price("43560"), r.getValue(txFar).quote().last)
        assertEquals(price("43430"), r.getValue(mtxNear).quote().last)
        val far = r.getValue(mtxFar).quote()
        assertEquals(price("43492"), far.last)
        assertEquals(price("43917"), far.previousClose)
        assertEquals(Instant.parse("2026-11-13T17:40:00Z"), far.asOf)
        assertEquals(price("43049"), far.low)
        assertEquals(price("44480"), far.high)
    }

    @Test fun `the first held contract picks the page`() {
        val http = FakeHttp(mapOf(futurePageUrl("WTX@") to ok(wtxPage)))
        val a = future(IndexFuture.TX, 2026, 12)
        val b = future(IndexFuture.MTX, 2026, 11)
        val r = fetch(saturdayNight, http, a, b)
        assertEquals(listOf(futurePageUrl("WTX@")), http.pageCalls())
        r.getValue(a).quote()
        r.getValue(b).quote()
    }

    @Test fun `a contract missing from the fetched page gets its own page`() {
        val http = FakeHttp(
            mapOf(futurePageUrl("WTX&") to ok("<html>no data</html>"), futurePageUrl("WMT@") to ok(wmtPage), TAIFEX_URL to taifex),
        )
        val near = future(IndexFuture.TX, 2026, 11)
        val far = future(IndexFuture.MTX, 2026, 12)
        val r = fetch(saturdayNight, http, near, far)
        assertEquals(listOf(futurePageUrl("WTX&"), futurePageUrl("WMT@"), TAIFEX_URL), http.pageCalls())
        assertEquals(price("43472"), r.getValue(far).quote().last)
        assertEquals(Freshness.END_OF_DAY, r.getValue(near).quote().freshness)
    }

    @Test fun `a page without the quote object falls back to Taifex`() {
        val http = FakeHttp(mapOf(futurePageUrl("WTX&") to ok("<html>no data</html>"), TAIFEX_URL to taifex))
        val x = future(IndexFuture.TX, 2026, 11)
        val q = fetch(saturdayNight, http, x).getValue(x).quote()
        assertEquals(listOf(futurePageUrl("WTX&"), TAIFEX_URL), http.pageCalls())
        assertEquals(price("43178"), q.last)
        assertEquals(Freshness.END_OF_DAY, q.freshness)
    }

    @Test fun `page failure falls back to Taifex and both failing reports a failure`() {
        val down = FakeHttp(mapOf(futurePageUrl("WTX&") to HttpResponse(500, ByteArray(0), null)))
        val x = future(IndexFuture.TX, 2026, 11)
        assertEquals(FetchFailure.Network, fetch(saturdayNight, down, x).getValue(x).failure())
        assertEquals(listOf(futurePageUrl("WTX&"), TAIFEX_URL), down.pageCalls())
    }

    @Test fun `Taifex is not asked again once it answered after the latest close and the page failure stands`() {
        val down = FakeHttp(mapOf(futurePageUrl("WTX&") to HttpResponse(500, ByteArray(0), null), TAIFEX_URL to taifex))
        val x = future(IndexFuture.TX, 2026, 11)
        val r = fetch(saturdayNight, down, x, since = taipei("2026-11-13T14:10:00"))
        assertEquals(listOf(futurePageUrl("WTX&")), down.pageCalls())
        assertEquals(FetchFailure.Network, r.getValue(x).failure())
    }

    @Test fun `Taifex is asked when the last fetch was before the latest close`() {
        val down = FakeHttp(mapOf(futurePageUrl("WTX&") to HttpResponse(500, ByteArray(0), null), TAIFEX_URL to taifex))
        val x = future(IndexFuture.TX, 2026, 11)
        val q = fetch(saturdayNight, down, x, since = taipei("2026-11-13T13:50:00")).getValue(x).quote()
        assertEquals(listOf(futurePageUrl("WTX&"), TAIFEX_URL), down.pageCalls())
        assertEquals(Freshness.END_OF_DAY, q.freshness)
    }

    @Test fun `Taifex file can change only after a weekday day session close plus ten minutes`() {
        assertEquals(taipei("2026-11-13T13:55:00"), publishedSince(saturdayNight))
        assertEquals(taipei("2026-11-13T13:55:00"), publishedSince(taipei("2026-11-16T13:54:59")))
        assertEquals(taipei("2026-11-16T13:55:00"), publishedSince(taipei("2026-11-16T13:55:00")))
    }

    @Test fun `page without a readable quote object is Unparseable`() {
        assertIs<FetchFailure.Unparseable>(parseFuturePage("<html></html>", "WTX&", null).failure())
        assertIs<FetchFailure.Unparseable>(parseFuturePage(wtxPage, "WZZ&", null).failure())
    }

    @Test fun `during the ambiguous roll window TX goes straight to Taifex`() {
        val http = FakeHttp(mapOf(TAIFEX_URL to taifex))
        val x = future(IndexFuture.TX, 2026, 12)
        fetch(taipei("2026-11-18T14:00:00"), http, x).getValue(x).quote()
        assertEquals(listOf(TAIFEX_URL), http.pageCalls())
    }

    @Test fun `TX far month and TMF and stock futures never ask Yahoo`() {
        val http = FakeHttp(mapOf(TAIFEX_URL to taifex))
        val far = future(IndexFuture.TX, 2027, 3)
        val micro = future(IndexFuture.TMF, 2026, 11)
        val stock = tw.personal.holdings.core.TwFuture(
            StockFuture.of("CDF", TwCode.parse("2330")!!, StockFutureSize.STANDARD)!!,
            ContractMonth.of(2026, 11),
        )
        val r = fetch(saturdayNight, http, far, micro, stock)
        assertEquals(listOf(TAIFEX_URL), http.pageCalls())
        assertEquals(price("43445"), r.getValue(far).quote().last)
        assertEquals(price("43177"), r.getValue(micro).quote().last)
        assertEquals(price("2340"), r.getValue(stock).quote().last)
    }

    @Test fun `Taifex row is the regular session and previousClose is last minus change`() {
        val http = FakeHttp(mapOf(TAIFEX_URL to taifex))
        val x = future(IndexFuture.TMF, 2026, 12)
        val q = fetch(saturdayNight, http, x).getValue(x).quote()
        assertEquals(price("43219"), q.last)
        assertEquals(price("44150"), q.previousClose)
        assertEquals(taipei("2026-11-12T13:45:00"), q.asOf)
        assertEquals(Freshness.END_OF_DAY, q.freshness)
        assertNull(q.low)
        assertNull(q.high)
    }

    @Test fun `Taifex is asked once per round however many contracts`() {
        val http = FakeHttp(mapOf(TAIFEX_URL to taifex))
        val items = arrayOf<Quotable>(
            future(IndexFuture.TMF, 2026, 11),
            future(IndexFuture.TMF, 2026, 12),
            future(IndexFuture.TX, 2027, 3),
        )
        fetch(saturdayNight, http, *items)
        assertEquals(1, http.calls.size)
    }

    @Test fun `missing contract is NotFound and a dash price is Unparseable`() {
        val http = FakeHttp(mapOf(TAIFEX_URL to taifex))
        val missing = future(IndexFuture.TMF, 2030, 12)
        val noTrade = tw.personal.holdings.core.TwFuture(
            StockFuture.of("CDF", TwCode.parse("2330")!!, StockFutureSize.STANDARD)!!,
            ContractMonth.of(2027, 9),
        )
        val r = fetch(saturdayNight, http, missing, noTrade)
        assertEquals(FetchFailure.NotFound, r.getValue(missing).failure())
        assertIs<FetchFailure.Unparseable>(r.getValue(noTrade).failure())
    }

    @Test fun `Taifex body with a BOM and NULL strings still parses`() {
        val body = "﻿" + """[
          {"Date":"20261112","Contract":"TMF","ContractMonth(Week)":"202611","Last":"100","Change":"NULL","SettlementPrice":"NULL","TradingSession":"一般"},
          {"Date":"20261112","Contract":"TMF","ContractMonth(Week)":"202612","Last":"200","Change":"-10","SettlementPrice":"NULL","TradingSession":"一般"}
        ]"""
        val http = FakeHttp(mapOf(TAIFEX_URL to ok(body)))
        val a = future(IndexFuture.TMF, 2026, 11)
        val b = future(IndexFuture.TMF, 2026, 12)
        val r = fetch(saturdayNight, http, a, b)
        assertIs<FetchFailure.Unparseable>(r.getValue(a).failure())
        assertEquals(price("210"), r.getValue(b).quote().previousClose)
    }

    @Test fun `Taifex 429 is Throttled and garbage is Unparseable`() {
        val x = future(IndexFuture.TMF, 2026, 11)
        val throttled = FakeHttp(mapOf(TAIFEX_URL to HttpResponse(429, ByteArray(0), 60)))
        assertIs<FetchFailure.Throttled>(fetch(saturdayNight, throttled, x).getValue(x).failure())
        val garbage = FakeHttp(mapOf(TAIFEX_URL to ok("not json")))
        assertIs<FetchFailure.Unparseable>(fetch(saturdayNight, garbage, x).getValue(x).failure())
    }

    @Test fun `capability is intraday where Yahoo serves and close only where only Taifex does`() {
        assertEquals(SourceCapability.INTRADAY, capability(saturdayNight, tw("2330")))
        assertEquals(SourceCapability.INTRADAY, capability(saturdayNight, us("AAPL")))
        assertEquals(SourceCapability.INTRADAY, capability(saturdayNight, coin("BTC")))
        assertEquals(SourceCapability.INTRADAY, capability(saturdayNight, tw.personal.holdings.core.FxPair.USD_TWD))
        assertEquals(SourceCapability.INTRADAY, capability(saturdayNight, future(IndexFuture.TX, 2026, 11)))
        assertEquals(SourceCapability.INTRADAY, capability(saturdayNight, future(IndexFuture.MTX, 2026, 12)))
        assertEquals(SourceCapability.CLOSE_ONLY, capability(saturdayNight, future(IndexFuture.TX, 2027, 3)))
        assertEquals(SourceCapability.CLOSE_ONLY, capability(saturdayNight, future(IndexFuture.TMF, 2026, 11)))
        assertEquals(
            SourceCapability.CLOSE_ONLY,
            capability(taipei("2026-11-18T14:00:00"), future(IndexFuture.TX, 2026, 12)),
        )
    }

    @Test fun `every input gets a result even when everything fails or throws`() {
        val items = arrayOf<Quotable>(
            tw("2330"), us("AAPL"), coin("BTC"), tw.personal.holdings.core.FxPair.USD_TWD,
            future(IndexFuture.TX, 2026, 11), future(IndexFuture.TMF, 2026, 11),
        )
        val throwing = HttpGet { _, _ -> error("boom") }
        val r = fetch(saturdayNight, throwing, *items)
        assertEquals(items.toSet(), r.keys)
        r.values.forEach { it.failure() }
    }

    @Test fun `a mixed round answers all of them`() {
        val http = FakeHttp(
            mapOf(
                sparkUrlOf("2330.TW", "AAPL") to ok(fixture("yahoo_spark_1m.json")),
                futurePageUrl("WTX&") to ok(wtxPage),
                TAIFEX_URL to taifex,
            ),
        )
        val items = arrayOf<Quotable>(
            tw("2330"), us("AAPL"), future(IndexFuture.TX, 2026, 11), future(IndexFuture.TMF, 2026, 11),
        )
        val r = fetch(saturdayNight, http, *items)
        assertEquals(items.toSet(), r.keys)
        r.values.forEach { it.quote() }
    }
}
