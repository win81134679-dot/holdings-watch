package tw.personal.holdings.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tw.personal.holdings.core.ContractMonth
import tw.personal.holdings.core.DraftResult
import tw.personal.holdings.core.HoldingDraft
import tw.personal.holdings.core.HoldingId
import tw.personal.holdings.core.IndexFuture
import tw.personal.holdings.core.InstrumentChoice
import tw.personal.holdings.core.RefreshMode
import tw.personal.holdings.core.TwBoard
import tw.personal.holdings.core.TwQtyUnit
import java.time.LocalDate

/**
 * 只在 debug 建置：
 *   adb shell am broadcast -a tw.personal.holdings.DEBUG_SEED -p tw.personal.holdings
 *   adb shell am broadcast -a tw.personal.holdings.DEBUG_REFRESH -p tw.personal.holdings
 * 範例持股用固定 id，重送 SEED 是覆蓋同一組、不會越加越多。
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SEED -> seed(context, intent.getStringExtra(EXTRA_STOP) ?: "99999")
                    ACTION_REFRESH -> Log.i(TAG, "debug: refresh requested")
                }
                // 收到廣播的這段時間行程算在前景、有網路；等這輪跑完才放手，否則還沒打開過的 App
                // （待機分組 NEVER）會被斷網、工作被改排到 JobScheduler 後就拿不到額度。
                val finished = withTimeoutOrNull(RECEIVER_BUDGET_MILLIS) { Portfolio.get(context).refresh(RefreshMode.MANUAL) }
                Log.i(TAG, "debug: refresh ${if (finished == null) "still queued after ${RECEIVER_BUDGET_MILLIS} ms" else "finished"}")
            } catch (e: Exception) {
                Log.e(TAG, "debug ${intent.action} failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun seed(context: Context, stop: String) {
        val portfolio = Portfolio.get(context)
        val today = todayTaipei()
        for ((id, draft) in sampleDrafts(today, stop)) {
            val result = portfolio.save(HoldingId(id), draft)
            Log.i(TAG, "debug seed $id: ${if (result is DraftResult.Ok) "saved" else result}")
        }
    }

    private companion object {
        const val ACTION_SEED = "tw.personal.holdings.DEBUG_SEED"
        const val ACTION_REFRESH = "tw.personal.holdings.DEBUG_REFRESH"
        const val EXTRA_STOP = "stop"
        const val RECEIVER_BUDGET_MILLIS = 9_000L
    }
}

/**
 * 2330 的停損預設 99999：多單停損在現價之上，一定已經碰到，用來驗證提醒只響一次。
 * 停損價是提醒規則的鍵，換一個價（`--es stop 88888`）就是新規則，會再響一次。
 */
private fun sampleDrafts(today: LocalDate, stop: String): List<Pair<String, HoldingDraft>> {
    val nearTx = ContractMonth.listed(IndexFuture.TX, today).first()
    val nearTmf = ContractMonth.listed(IndexFuture.TMF, today).first()
    fun draft(choice: InstrumentChoice, qty: String, cost: String, name: String = "", stop: String = "", daysAgo: Long = 30) =
        HoldingDraft(
            instrument = choice,
            quantityText = qty,
            averageCostText = cost,
            stopLossText = stop,
            entryDate = today.minusDays(daysAgo),
            name = name,
        )
    return listOf(
        "seed-2330" to draft(InstrumentChoice.TwStock("2330", TwBoard.TWSE, TwQtyUnit.LOT), "1", "1000", "台積電", stop = stop),
        "seed-6488" to draft(InstrumentChoice.TwStock("6488", TwBoard.TPEX, TwQtyUnit.LOT), "1", "400", "環球晶"),
        "seed-aapl" to draft(InstrumentChoice.UsStock("AAPL"), "10", "200", "蘋果"),
        "seed-tx" to draft(InstrumentChoice.IndexFut(IndexFuture.TX, nearTx), "1", "27000", daysAgo = 5),
        "seed-tmf" to draft(InstrumentChoice.IndexFut(IndexFuture.TMF, nearTmf), "1", "27000", daysAgo = 5),
        "seed-btc" to draft(InstrumentChoice.Coin("BTC"), "0.01", "90000"),
    )
}
