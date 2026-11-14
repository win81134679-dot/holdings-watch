package tw.personal.holdings.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import tw.personal.holdings.core.ContractMonth
import tw.personal.holdings.core.DraftResult
import tw.personal.holdings.core.FxPair
import tw.personal.holdings.core.Freshness
import tw.personal.holdings.core.Holding
import tw.personal.holdings.core.HoldingDraft
import tw.personal.holdings.core.HoldingId
import tw.personal.holdings.core.IndexFuture
import tw.personal.holdings.core.InstrumentChoice
import tw.personal.holdings.core.PortfolioCodec
import tw.personal.holdings.core.PortfolioSnapshot
import tw.personal.holdings.core.Price
import tw.personal.holdings.core.Quotable
import tw.personal.holdings.core.Quote
import tw.personal.holdings.core.Side
import tw.personal.holdings.core.SyncContract
import tw.personal.holdings.core.TAIPEI
import tw.personal.holdings.core.TwBoard
import tw.personal.holdings.core.TwQtyUnit
import tw.personal.holdings.core.parse
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * 只在 debug 建置：把一份範例快照寫到手機會寫的同一條 Data Layer 路徑，手機 App 還沒好之前就能在真手錶上看畫面。
 *   adb shell am broadcast -a tw.personal.holdings.DEBUG_SAMPLE -p tw.personal.holdings
 */
class DebugSampleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = PortfolioCodec.encodeSnapshot(sampleSnapshot(Instant.now()))
                val request = PutDataRequest.create(SyncContract.SNAPSHOT_PATH).setData(bytes).setUrgent()
                val item = Wearable.getDataClient(context).putDataItem(request).await()
                Log.i(TAG, "sample snapshot written: ${item.uri} (${bytes.size} bytes)")
                // 手錶自己寫的 DataItem 不會觸發本機的 SnapshotListenerService（實機確認），只好在這裡直接叫。
                requestSurfaceUpdates(context.applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "sample snapshot failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugSample"
    }
}

private fun sampleSnapshot(now: Instant): PortfolioSnapshot {
    val today = now.atZone(TAIPEI).toLocalDate()
    val nearMonth = ContractMonth.listed(IndexFuture.TX, today).first()
    val expiredMonth = ContractMonth(nearMonth.yearMonth.minusMonths(1))
    val expiredDay = expiredMonth.lastTradingDay()

    val tsmc = holding(
        "sample-2330",
        HoldingDraft(
            instrument = InstrumentChoice.TwStock("2330", TwBoard.TWSE, TwQtyUnit.LOT),
            quantityText = "2",
            averageCostText = "980",
            stopLossText = "900",
            targetText = "1200",
            entryDate = today.minusDays(103),
            name = "台積電",
        ),
        today,
    )
    val aapl = holding(
        "sample-aapl",
        HoldingDraft(
            instrument = InstrumentChoice.UsStock("AAPL"),
            quantityText = "10",
            averageCostText = "210.5",
            stopLossText = "230",
            entryDate = today.minusDays(41),
        ),
        today,
    )
    val tx = holding(
        "sample-tx",
        HoldingDraft(
            instrument = InstrumentChoice.IndexFut(IndexFuture.TX, nearMonth),
            quantityText = "1",
            averageCostText = "22800",
            targetText = "23100",
            entryDate = today.minusDays(3),
        ),
        today,
    )
    val btc = holding(
        "sample-btc",
        HoldingDraft(
            instrument = InstrumentChoice.Coin("BTC"),
            quantityText = "0.05",
            averageCostText = "85000",
            entryDate = today.minusDays(200),
            name = "比特幣",
        ),
        today,
    )
    // 已到期的合約過了最後交易日就無法經表單建立，所以用到期前一天當作「建立當天」。
    val expiredMtx = holding(
        "sample-mtx-expired",
        HoldingDraft(
            instrument = InstrumentChoice.IndexFut(IndexFuture.MTX, expiredMonth),
            quantityText = "2",
            side = Side.SHORT,
            averageCostText = "22500",
            entryDate = expiredDay.minusDays(20),
            name = "小台",
        ),
        expiredDay.minusDays(1),
    )

    val quotes: Map<Quotable, Quote> = mapOf(
        tsmc.asset.instrument to quote("1085", "1060", now.minus(Duration.ofMinutes(4))),
        aapl.asset.instrument to quote("228.3", "233.1", now.minus(Duration.ofMinutes(9))),
        tx.asset.instrument to quote("23150", "23020", now.minus(Duration.ofMinutes(2))),
        btc.asset.instrument to quote("96500", "98200", now.minus(Duration.ofMinutes(1))),
        FxPair.USD_TWD to quote("32.45", "32.40", now.minus(Duration.ofMinutes(30))),
    )
    return PortfolioSnapshot(listOf(tsmc, aapl, tx, btc, expiredMtx), quotes)
}

private fun holding(id: String, draft: HoldingDraft, today: LocalDate): Holding =
    when (val r = draft.parse(HoldingId(id), today)) {
        is DraftResult.Ok -> r.holding
        is DraftResult.Invalid -> error("sample $id invalid: ${r.problems}")
    }

private fun quote(last: String, previousClose: String, asOf: Instant) = Quote(
    last = Price.parse(last)!!,
    previousClose = Price.parse(previousClose)!!,
    asOf = asOf,
    freshness = Freshness.LIVE,
)
