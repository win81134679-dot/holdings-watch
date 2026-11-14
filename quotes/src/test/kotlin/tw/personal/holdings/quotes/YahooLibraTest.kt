package tw.personal.holdings.quotes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.personal.holdings.core.FetchFailure
import tw.personal.holdings.core.Freshness
import tw.personal.holdings.core.IndexFuture
import tw.personal.holdings.core.Quotable
import java.time.Instant

class YahooLibraTest {
    private val now = taipei("2026-11-14T04:21:30")
    private val txNear = future(IndexFuture.TX, 2026, 11)
    private val mtxFar = future(IndexFuture.MTX, 2026, 12)
    private val body = fixture("yahoo_tw_libra_tick.json")

    private suspend fun fetch(
        items: List<Quotable>,
        http: FakeHttp,
        since: (Quotable) -> Instant? = { null },
    ) = YahooLibraSource(http).fetch(items, Round(now, since, pause = {}))

    @Test
    fun `one request carries every held contract`() = runTest {
        val http = FakeHttp(mapOf(libraUrlOf("WTX&", "WMT@") to ok(body)))
        val answers = fetch(listOf(txNear, mtxFar), http)
        assertEquals(listOf(libraUrlOf("WTX&", "WMT@")), http.calls)
        assertEquals(price("43624"), answers.getValue(txNear).quote().last)
        assertEquals(price("43839"), answers.getValue(txNear).quote().previousClose)
        assertEquals(price("43680"), answers.getValue(mtxFar).quote().last)
        assertEquals(price("43917"), answers.getValue(mtxFar).quote().previousClose)
    }

    @Test
    fun `asOf is the real refresh time, not the trading-day timestamp`() = runTest {
        val http = FakeHttp(mapOf(libraUrlOf("WTX&") to ok(body)))
        val quote = fetch(listOf(txNear), http).getValue(txNear).quote()
        assertEquals(Instant.parse("2026-11-13T20:20:23Z"), quote.asOf)
        assertEquals(Freshness.LIVE, quote.freshness)
    }

    @Test
    fun `first fetch of the session uses the day range`() = runTest {
        val http = FakeHttp(mapOf(libraUrlOf("WTX&") to ok(body)))
        val quote = fetch(listOf(txNear), http).getValue(txNear).quote()
        assertEquals(price("42956"), quote.low)
        assertEquals(price("44423"), quote.high)
    }

    @Test
    fun `later fetches use minute bars after since, shifted back to real time`() = runTest {
        val since = taipei("2026-11-14T04:00:00")
        val http = FakeHttp(mapOf(libraUrlOf("WTX&", "WMT@") to ok(body)))
        val answers = fetch(listOf(txNear, mtxFar), http) { since }
        assertEquals(price("43561"), answers.getValue(txNear).quote().low)
        assertEquals(price("43668"), answers.getValue(txNear).quote().high)
        assertEquals(price("43635"), answers.getValue(mtxFar).quote().low)
        assertEquals(price("43735"), answers.getValue(mtxFar).quote().high)
    }

    @Test
    fun `contract missing from the response is NotFound`() = runTest {
        val http = FakeHttp(mapOf(libraUrlOf("WTX&") to ok("[]")))
        assertEquals(FetchFailure.NotFound, fetch(listOf(txNear), http).getValue(txNear).failure())
    }

    @Test
    fun `garbage body is Unparseable`() = runTest {
        val http = FakeHttp(mapOf(libraUrlOf("WTX&") to ok("<html>")))
        assertIs<FetchFailure.Unparseable>(fetch(listOf(txNear), http).getValue(txNear).failure())
    }

    @Test
    fun `network failure fails every contract in the request`() = runTest {
        val answers = fetch(listOf(txNear, mtxFar), FakeHttp())
        assertEquals(FetchFailure.Network, answers.getValue(txNear).failure())
        assertEquals(FetchFailure.Network, answers.getValue(mtxFar).failure())
    }

    @Test
    fun `contracts Yahoo does not carry are Unsupported without a request`() = runTest {
        val tmf = future(IndexFuture.TMF, 2026, 11)
        val http = FakeHttp()
        assertEquals(FetchFailure.Unsupported, fetch(listOf(tmf), http).getValue(tmf).failure())
        assertEquals(emptyList<String>(), http.calls)
    }
}
