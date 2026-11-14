package tw.personal.holdings.phone

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.personal.holdings.core.Crypto
import tw.personal.holdings.core.CoinSymbol
import tw.personal.holdings.core.DraftResult
import tw.personal.holdings.core.FetchResult
import tw.personal.holdings.core.Freshness
import tw.personal.holdings.core.FxPair
import tw.personal.holdings.core.Holding
import tw.personal.holdings.core.HoldingDraft
import tw.personal.holdings.core.HoldingId
import tw.personal.holdings.core.InstrumentChoice
import tw.personal.holdings.core.PortfolioSnapshot
import tw.personal.holdings.core.Price
import tw.personal.holdings.core.Quotable
import tw.personal.holdings.core.Quote
import tw.personal.holdings.core.RefreshMode
import tw.personal.holdings.core.RefreshState
import tw.personal.holdings.core.SourceCapability
import tw.personal.holdings.core.parse
import tw.personal.holdings.quotes.QuoteFetcher
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class RefresherTest {
    private val start = Instant.parse("2026-11-14T10:00:00Z")
    private val btc = Crypto(CoinSymbol.parse("BTC")!!)

    private fun btcHolding(id: String = "h1", stop: String = "100000"): Holding {
        val draft = HoldingDraft(
            instrument = InstrumentChoice.Coin("BTC"),
            quantityText = "0.01",
            averageCostText = "90000",
            stopLossText = stop,
            entryDate = LocalDate.of(2026, 11, 1),
        )
        return (draft.parse(HoldingId(id), LocalDate.of(2026, 11, 14)) as DraftResult.Ok).holding
    }

    private fun quote(last: String, at: Instant) = Quote(Price.parse(last)!!, Price.parse(last)!!, at, Freshness.LIVE)

    private class MemoryStore<T>(initial: T, private val beforeWrite: (T) -> Unit = {}) : DataStore<T> {
        val flow = MutableStateFlow(initial)
        override val data: Flow<T> = flow
        override suspend fun updateData(transform: suspend (t: T) -> T): T {
            val next = transform(flow.value)
            beforeWrite(next)
            flow.value = next
            return next
        }
    }

    /** 每次 fetch 回 [prices] 目前的值，asOf＝當下時鐘前 1 分鐘；記下每次收到的 since。 */
    private class FakeFetcher(val clock: () -> Instant) : QuoteFetcher {
        var prices: Map<Quotable, String> = emptyMap()
        val sinceSeen = mutableListOf<Pair<Quotable, Instant?>>()
        val fetched = mutableListOf<Set<Quotable>>()

        override suspend fun fetch(items: Set<Quotable>, since: (Quotable) -> Instant?): Map<Quotable, FetchResult> {
            fetched += items
            return items.associateWith { x ->
                sinceSeen += x to since(x)
                val p = Price.parse(prices.getValue(x))!!
                FetchResult.Got(Quote(p, p, clock().minusSeconds(60), Freshness.LIVE))
            }
        }

        override fun capabilityOf(x: Quotable) = SourceCapability.INTRADAY
    }

    private class Harness(holdings: List<Holding>, val clockRef: Array<Instant>, failNextStateWrite: Boolean = false) {
        val events = mutableListOf<String>()
        val published = mutableListOf<PortfolioSnapshot>()
        private var failWrite = failNextStateWrite
        val holdingsStore = MemoryStore(holdings)
        val stateStore = MemoryStore(RefreshState.EMPTY) {
            if (failWrite) {
                failWrite = false
                throw IllegalStateException("crash between notify and save")
            }
            events += "save"
        }
        val fetcher = FakeFetcher { clockRef[0] }
        val refresher = Refresher(
            holdings = holdingsStore,
            refreshState = stateStore,
            fetcher = fetcher,
            post = { events += "post:${it.key.stableTag()}"; true },
            publish = { events += "publish"; published += it; "ok" },
            clock = { clockRef[0] },
            log = {},
        )
    }

    @Test
    fun `notifies before saving state and publishes last`() = runBlocking {
        val h = Harness(listOf(btcHolding()), arrayOf(start))
        h.fetcher.prices = mapOf(btc to "95000", FxPair.USD_TWD to "32")

        h.refresher.run(RefreshMode.FOREGROUND)

        assertEquals(listOf("post:h1|STOP_LOSS|100000", "save", "publish"), h.events)
        val snapshot = h.published.single()
        assertEquals(listOf(HoldingId("h1")), snapshot.holdings.map { it.id })
        assertEquals(setOf<Quotable>(btc, FxPair.USD_TWD), snapshot.quotes.keys)
    }

    @Test
    fun `a fired alert does not post again on later rounds`() = runBlocking {
        val clock = arrayOf(start)
        val h = Harness(listOf(btcHolding()), clock)
        h.fetcher.prices = mapOf(btc to "95000", FxPair.USD_TWD to "32")

        h.refresher.run(RefreshMode.FOREGROUND)
        clock[0] = start + Duration.ofMinutes(3)
        h.fetcher.prices = mapOf(btc to "94000", FxPair.USD_TWD to "32")
        h.refresher.run(RefreshMode.FOREGROUND)
        h.refresher.run(RefreshMode.MANUAL)

        assertEquals(1, h.events.count { it.startsWith("post:") })
        assertEquals(2, h.fetcher.fetched.size)
    }

    @Test
    fun `a crash between notify and save re-posts the same tag next round`() = runBlocking {
        val clock = arrayOf(start)
        val h = Harness(listOf(btcHolding()), clock, failNextStateWrite = true)
        h.fetcher.prices = mapOf(btc to "95000", FxPair.USD_TWD to "32")

        val crashed = runCatching { h.refresher.run(RefreshMode.FOREGROUND) }
        assertTrue(crashed.isFailure)
        h.refresher.run(RefreshMode.FOREGROUND)

        assertEquals(
            listOf("post:h1|STOP_LOSS|100000", "post:h1|STOP_LOSS|100000", "save", "publish"),
            h.events,
        )
    }

    @Test
    fun `fetch receives the previous fetch time as since`() = runBlocking {
        val clock = arrayOf(start)
        val h = Harness(listOf(btcHolding(stop = "1")), clock)
        h.fetcher.prices = mapOf(btc to "95000", FxPair.USD_TWD to "32")

        h.refresher.run(RefreshMode.FOREGROUND)
        clock[0] = start + Duration.ofMinutes(31)
        h.refresher.run(RefreshMode.FOREGROUND)

        val btcSince = h.fetcher.sinceSeen.filter { it.first == btc }.map { it.second }
        assertEquals(listOf(null, start), btcSince)
    }

    @Test
    fun `deleted holdings drop their quotes and alert states`() = runBlocking {
        val clock = arrayOf(start)
        val h = Harness(listOf(btcHolding()), clock)
        h.fetcher.prices = mapOf(btc to "95000", FxPair.USD_TWD to "32")
        h.refresher.run(RefreshMode.FOREGROUND)

        h.holdingsStore.flow.value = emptyList()
        h.refresher.run(RefreshMode.FOREGROUND)

        val state = h.stateStore.flow.value
        assertTrue(state.market.entries.isEmpty())
        assertTrue(state.alerts.isEmpty())
        assertTrue(h.published.last().holdings.isEmpty())
    }
}
