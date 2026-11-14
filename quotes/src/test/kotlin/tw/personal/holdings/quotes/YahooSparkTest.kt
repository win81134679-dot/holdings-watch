package tw.personal.holdings.quotes

import kotlinx.coroutines.runBlocking
import tw.personal.holdings.core.FetchFailure
import tw.personal.holdings.core.Freshness
import tw.personal.holdings.core.FxPair
import tw.personal.holdings.core.IndexFuture
import tw.personal.holdings.core.Quotable
import tw.personal.holdings.core.TwBoard
import java.time.Duration
import java.time.Instant
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class YahooSparkTest {
    private val now = taipei("2026-11-14T03:45:00")
    private val spark = fixtureText("yahoo_spark_1m.json")
    private val all = sparkUrlOf("2330.TW", "6488.TWO", "AAPL", "BTC-USD", "TWD=X")

    private fun fetch(http: HttpGet, vararg items: Quotable, since: Instant? = null) =
        runBlocking { QuoteFetchers.create(http, { now }, {}).fetch(linkedSetOf(*items)) { since } }

    private fun one(x: Quotable, http: HttpGet, since: Instant? = null) = fetch(http, x, since = since).getValue(x)

    private fun only2330(body: String) = FakeHttp(mapOf(sparkUrlOf("2330.TW") to ok(body)))

    private val tw2330Body = spark

    @Test fun `five symbols in one round are one request and each gets its own quote`() {
        val http = FakeHttp(mapOf(all to ok(spark)))
        val r = fetch(http, tw("2330"), tw("6488", TwBoard.TPEX), us("AAPL"), coin("BTC"), FxPair.USD_TWD)
        assertEquals(listOf(all), http.calls)

        val twse = r.getValue(tw("2330")).quote()
        assertEquals(price("2350"), twse.last)
        assertEquals(price("2340"), twse.previousClose)
        assertEquals(Instant.ofEpochSecond(1794547804), twse.asOf)
        assertEquals(Freshness.DELAYED, twse.freshness)

        val tpex = r.getValue(tw("6488", TwBoard.TPEX)).quote()
        assertEquals(price("630"), tpex.last)
        assertEquals(price("622"), tpex.previousClose)

        val aapl = r.getValue(us("AAPL")).quote()
        assertEquals(price("357.545"), aapl.last)
        assertEquals(price("354.88"), aapl.previousClose)
        assertEquals(Instant.ofEpochSecond(1794599286), aapl.asOf)
        assertEquals(Freshness.LIVE, aapl.freshness)

        val btc = r.getValue(coin("BTC")).quote()
        assertEquals(price("68906.7"), btc.last)
        assertEquals(price("71371.766"), btc.previousClose)
        assertEquals(Freshness.LIVE, btc.freshness)

        val fx = r.getValue(FxPair.USD_TWD).quote()
        assertEquals(price("32.88"), fx.last)
        assertEquals(price("32.94"), fx.previousClose)
        assertEquals(Freshness.LIVE, fx.freshness)
    }

    @Test fun `stocks without since get the exact day range from meta`() {
        val q = one(tw("2330"), only2330(tw2330Body)).quote()
        assertEquals(price("2340"), q.low)
        assertEquals(price("2390"), q.high)
    }

    @Test fun `since before the first point of the day still gets the exact day range`() {
        val q = one(tw("2330"), only2330(tw2330Body), since = Instant.ofEpochSecond(1794531000)).quote()
        assertEquals(price("2340"), q.low)
        assertEquals(price("2390"), q.high)
    }

    @Test fun `since inside the day keeps only closes strictly later`() {
        val q = one(tw("2330"), only2330(tw2330Body), since = Instant.ofEpochSecond(1794539700)).quote()
        assertEquals(price("2345"), q.low)
        assertEquals(price("2370"), q.high)
    }

    @Test fun `crypto without since takes the whole close series not the meta day range`() {
        val q = one(coin("BTC"), FakeHttp(mapOf(sparkUrlOf("BTC-USD") to ok(spark)))).quote()
        assertEquals(price("67881.53"), q.low)
        assertEquals(price("71421.99"), q.high)
    }

    @Test fun `null closes are skipped`() {
        val body = tw2330Body.replace("2360.0,2350.0]", "2360.0,null]")
        val q = one(tw("2330"), only2330(body), since = Instant.ofEpochSecond(1794547400)).quote()
        assertEquals(price("2360"), q.low)
        assertEquals(price("2360"), q.high)
    }

    @Test fun `since after the last point leaves low and high null`() {
        val q = one(tw("2330"), only2330(tw2330Body), since = Instant.ofEpochSecond(1794547800)).quote()
        assertNull(q.low)
        assertNull(q.high)
        assertEquals(price("2350"), q.last)
    }

    @Test fun `a symbol Yahoo leaves out of the answer is NotFound and the rest still parse`() {
        val http = FakeHttp(mapOf(sparkUrlOf("2330.TW", "9999.TW") to ok(fixture("yahoo_spark_unknown.json"))))
        val r = fetch(http, tw("2330"), tw("9999"))
        r.getValue(tw("2330")).quote()
        assertEquals(FetchFailure.NotFound, r.getValue(tw("9999")).failure())
    }

    @Test fun `meta for a different symbol is Unparseable`() {
        val body = tw2330Body.replace("\"currency\":\"TWD\",\"symbol\":\"2330.TW\"", "\"currency\":\"TWD\",\"symbol\":\"2317.TW\"")
        assertIs<FetchFailure.Unparseable>(one(tw("2330"), only2330(body)).failure())
    }

    @Test fun `answer in a different currency is Unparseable`() {
        val body = tw2330Body.replace("\"currency\":\"TWD\",\"symbol\":\"2330.TW\"", "\"currency\":\"USD\",\"symbol\":\"2330.TW\"")
        assertIs<FetchFailure.Unparseable>(one(tw("2330"), only2330(body)).failure())
    }

    @Test fun `non positive price is Unparseable`() {
        val body = tw2330Body.replace("\"regularMarketPrice\":2350.0", "\"regularMarketPrice\":0.0")
        assertIs<FetchFailure.Unparseable>(one(tw("2330"), only2330(body)).failure())
    }

    @Test fun `garbage body or a spark error fails every symbol in the batch`() {
        val garbage = FakeHttp(mapOf(sparkUrlOf("2330.TW", "AAPL") to ok("<html>oops</html>")))
        fetch(garbage, tw("2330"), us("AAPL")).values.forEach { assertIs<FetchFailure.Unparseable>(it.failure()) }
        val error = FakeHttp(mapOf(sparkUrlOf("2330.TW") to ok("""{"spark":{"result":null,"error":{"code":"Bad Request"}}}""")))
        assertIs<FetchFailure.Unparseable>(one(tw("2330"), error).failure())
    }

    @Test fun `429 carries Retry-After to every symbol in the batch`() {
        val http = FakeHttp(mapOf(sparkUrlOf("2330.TW", "AAPL") to HttpResponse(429, ByteArray(0), 120)))
        val r = fetch(http, tw("2330"), us("AAPL"))
        r.values.forEach { assertEquals(FetchFailure.Throttled(Duration.ofSeconds(120)), it.failure()) }
    }

    @Test fun `429 without Retry-After is Throttled with null`() {
        val http = FakeHttp(mapOf(sparkUrlOf("2330.TW") to HttpResponse(429, ByteArray(0), null)))
        assertEquals(FetchFailure.Throttled(null), one(tw("2330"), http).failure())
    }

    @Test fun `404 is NotFound and server error and connection failure are Network`() {
        val http404 = FakeHttp(mapOf(sparkUrlOf("2330.TW") to HttpResponse(404, ByteArray(0), null)))
        assertEquals(FetchFailure.NotFound, one(tw("2330"), http404).failure())
        val http500 = FakeHttp(mapOf(sparkUrlOf("2330.TW") to HttpResponse(500, ByteArray(0), null)))
        assertEquals(FetchFailure.Network, one(tw("2330"), http500).failure())
        assertEquals(FetchFailure.Network, one(tw("2330"), FakeHttp()).failure())
    }

    @Test fun `more than 20 symbols are split into batches of 20`() {
        val http = FakeHttp()
        val codes = (1101..1121).map { tw(it.toString()) }
        fetch(http, *codes.toTypedArray())
        assertEquals(2, http.calls.size)
        assertEquals(sparkUrlOf(*(1101..1120).map { "$it.TW" }.toTypedArray()), http.calls[0])
        assertEquals(sparkUrlOf("1121.TW"), http.calls[1])
    }

    @Test fun `Yahoo requests in a round go out one after another with a 500 ms pause and no retry`() {
        val log = mutableListOf<String>()
        val stocks = sparkUrlOf("2330.TW", "AAPL", "BTC-USD")
        val http = FakeHttp(
            mapOf(
                stocks to HttpResponse(500, ByteArray(0), null),
                libraUrlOf("WTX&") to ok(fixture("yahoo_tw_libra_tick.json")),
            ),
            log,
        )
        val fetcher = QuoteFetchers.create(http, { now }, { log += "PAUSE $it" })
        val items = linkedSetOf<Quotable>(tw("2330"), us("AAPL"), coin("BTC"), future(IndexFuture.TX, 2026, 11))
        runBlocking { fetcher.fetch(items) { null } }
        assertEquals(listOf("GET $stocks", "PAUSE 500", "GET ${libraUrlOf("WTX&")}"), log)
    }

    @Test fun `requests carry a browser style User-Agent`() {
        val http = only2330(tw2330Body)
        one(tw("2330"), http)
        assertEquals("Mozilla/5.0", http.headers.single()["User-Agent"])
    }
}
