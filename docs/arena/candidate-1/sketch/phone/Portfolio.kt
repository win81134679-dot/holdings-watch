package holdings.phone

import android.content.Context
import holdings.core.DraftResult
import holdings.core.HoldingDraft
import holdings.core.HoldingId
import holdings.core.Holding
import holdings.core.QuoteEntry
import holdings.core.Quotable
import holdings.core.Valuation
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * 手機 UI 唯一需要的介面。UI 不知道有 DataStore、WorkManager、Data Layer、Yahoo。
 *
 * 藏在後面的：表單解析與驗證、兩個存檔、估值、刷新排程與單一寫入者保證、發布到手錶。
 */
interface Portfolio {
    /** 持股＋報價快取的組合，每次任一存檔變動就重新估值。 */
    val board: Flow<Board>

    /**
     * 新增或編輯。解析失敗回 Invalid，不寫任何東西；成功就寫入持股存檔、排一次刷新
     * （刷新會重新判斷提醒並發布到手錶，所以手錶在幾秒內看到改動，不必等 15 分鐘）。
     * id == null 表示新增。
     */
    suspend fun save(id: HoldingId?, draft: HoldingDraft): DraftResult

    /** 刪除。對應的報價快取與提醒狀態在下一輪刷新時自然被對帳掉。 */
    suspend fun delete(id: HoldingId)

    /** 手動下拉刷新；App 回到前景時用 FOREGROUND。實際抓不抓由 FetchPolicy 決定（有下限）。 */
    fun refresh(foreground: Boolean)

    companion object {
        fun get(context: Context): Portfolio = TODO("not implemented: Application 範圍的單例 PhonePortfolio")
    }
}

/** 手機首頁要的一切。 */
data class Board(
    val holdings: List<Holding>,
    val valuation: Valuation,
    /** 每個商品最後一次抓取的狀態（失敗原因、抓到的時間），給「資料 3 分鐘前／抓取失敗」的小字。 */
    val fetchStatus: Map<Quotable, QuoteEntry>,
)

// ---- 呼叫端的樣子（草圖） ----
//
// @Composable fun BoardScreen(portfolio: Portfolio = Portfolio.get(LocalContext.current)) {
//     val board by portfolio.board.collectAsStateWithLifecycle(initialValue = null)
//     LifecycleResumeEffect(Unit) { portfolio.refresh(foreground = true); onPauseOrDispose { } }
//     PullToRefreshBox(onRefresh = { portfolio.refresh(foreground = false) }) {
//         board?.valuation?.byMarket()?.forEach { (market, rows) -> MarketSection(market, rows) }
//     }
// }
//
// 編輯畫面：
//     when (val r = portfolio.save(existing?.id, draft)) {
//         is DraftResult.Ok -> navigateBack()
//         is DraftResult.Invalid -> showErrors(r.problems)
//     }
//
// 期貨月份下拉：ContractMonth.listed(product, LocalDate.now(TAIPEI))；每點價值顯示 product.pointValueTwd（唯讀）。

/** 給 :phone 內部用的今天（台北）。 */
internal fun todayTaipei(): LocalDate = TODO("not implemented")
