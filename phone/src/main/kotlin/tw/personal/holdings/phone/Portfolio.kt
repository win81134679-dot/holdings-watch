package tw.personal.holdings.phone

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import tw.personal.holdings.core.DraftResult
import tw.personal.holdings.core.Holding
import tw.personal.holdings.core.HoldingDraft
import tw.personal.holdings.core.HoldingId
import tw.personal.holdings.core.PortfolioSnapshot
import tw.personal.holdings.core.Quotable
import tw.personal.holdings.core.QuoteEntry
import tw.personal.holdings.core.RefreshMode
import tw.personal.holdings.core.RefreshState
import tw.personal.holdings.core.Valuation
import tw.personal.holdings.core.parse
import tw.personal.holdings.core.valuate
import java.time.Instant
import java.util.UUID

/** 手機 UI 唯一的入口。UI 不知道有 DataStore、WorkManager、Data Layer、Yahoo。 */
interface Portfolio {
    /** 持股＋報價快取＋現在時間的估值；任一存檔變動或每分鐘重算一次。 */
    val board: Flow<Board>

    /** 排一輪刷新，等那一輪跑完才回來（最多 1 分鐘）。抓不抓、抓哪些由 core 的 dueForFetch 決定。 */
    suspend fun refresh(mode: RefreshMode)

    /** id == null 是新增。解析失敗回 Invalid、不寫任何東西；成功就存檔並排一輪刷新（手錶幾秒內更新）。 */
    suspend fun save(id: HoldingId?, draft: HoldingDraft): DraftResult

    /** 對應的報價與提醒狀態在下一輪刷新時對帳掉。 */
    suspend fun delete(id: HoldingId)

    companion object {
        fun get(context: Context): Portfolio = AppGraph.get(context).portfolio
    }
}

data class Board(
    val holdings: List<Holding>,
    val valuation: Valuation,
    /** 每個商品最後一次抓取的狀態，給「讀不到報價」補上原因。 */
    val fetchStatus: Map<Quotable, QuoteEntry>,
    val now: Instant,
)

internal class PhonePortfolio(
    private val context: Context,
    private val holdings: DataStore<List<Holding>>,
    private val refreshState: DataStore<RefreshState>,
) : Portfolio {

    override val board: Flow<Board> = combine(holdings.data, refreshState.data, minuteTicker()) { h, s, now ->
        Board(h, valuate(PortfolioSnapshot(h, s.market.quotes()), now), s.market.entries, now)
    }

    override suspend fun refresh(mode: RefreshMode) {
        val id = RefreshScheduler.runSoon(context, mode)
        withTimeoutOrNull(ROUND_WAIT_MILLIS) {
            WorkManager.getInstance(context).getWorkInfoByIdFlow(id).first { it == null || it.state.isFinished }
        }
    }

    override suspend fun save(id: HoldingId?, draft: HoldingDraft): DraftResult {
        val result = draft.parse(id ?: HoldingId(UUID.randomUUID().toString()), todayTaipei())
        if (result is DraftResult.Ok) {
            holdings.updateData { upsert(it, result.holding) }
            RefreshScheduler.runSoon(context, RefreshMode.FOREGROUND)
        }
        return result
    }

    override suspend fun delete(id: HoldingId) {
        holdings.updateData { list -> list.filterNot { it.id == id } }
        RefreshScheduler.runSoon(context, RefreshMode.FOREGROUND)
    }

    private companion object {
        const val ROUND_WAIT_MILLIS = 60_000L
    }
}

/** 同 id 就原地取代（保留順序），否則接在最後。 */
internal fun upsert(list: List<Holding>, h: Holding): List<Holding> =
    if (list.any { it.id == h.id }) list.map { if (it.id == h.id) h else it } else list + h

private fun minuteTicker(): Flow<Instant> = flow {
    while (true) {
        emit(Instant.now())
        delay(60_000)
    }
}
