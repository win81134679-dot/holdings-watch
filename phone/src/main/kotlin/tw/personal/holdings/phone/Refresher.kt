package tw.personal.holdings.phone

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.first
import tw.personal.holdings.core.AlertFiring
import tw.personal.holdings.core.Currency
import tw.personal.holdings.core.FetchResult
import tw.personal.holdings.core.FxPair
import tw.personal.holdings.core.Holding
import tw.personal.holdings.core.PortfolioSnapshot
import tw.personal.holdings.core.Quotable
import tw.personal.holdings.core.RefreshMode
import tw.personal.holdings.core.RefreshState
import tw.personal.holdings.core.dueForFetch
import tw.personal.holdings.core.stepAlerts
import tw.personal.holdings.quotes.QuoteFetcher
import java.time.Duration
import java.time.Instant

/**
 * 一輪刷新：整個 App 唯一抓報價、判斷提醒、發布到手錶的路徑。只在 WorkManager 唯一工作 `refresh-now` 裡跑，
 * 所以同一時間只有一個，refresh-state.json 只有它寫。
 *
 * 每一步都從存著的狀態推導：在任何一行當掉，下一輪重讀兩個檔就收斂到同樣的結果。
 * 通知在存狀態之前發：兩步之間當掉，下一輪會用同一個 tag 再發一次（取代、不重複震動）；反過來會漏發。
 * 發布放最後、不看抓取成敗：沒網路時改了持股，手錶仍會收到新持股。
 */
internal class Refresher(
    private val holdings: DataStore<List<Holding>>,
    private val refreshState: DataStore<RefreshState>,
    private val fetcher: QuoteFetcher,
    /** 回傳是否真的發出（通知權限沒開時是 false）。 */
    private val post: (AlertFiring) -> Boolean,
    /** 回傳一句給 log 的結果（已送出、內容沒變、失敗原因）；不丟例外。 */
    private val publish: suspend (PortfolioSnapshot) -> String,
    private val clock: () -> Instant,
    private val log: (String) -> Unit,
) {
    suspend fun run(mode: RefreshMode) {
        val now = clock()
        val hs = holdings.data.first()
        val st = refreshState.data.first()

        val due = dueForFetch(hs, st.market, mode, now, fetcher::capabilityOf)
        log("round start mode=$mode holdings=${hs.size} due=${due.size} [${due.joinToString { it.logName() }}]")

        val results = if (due.isEmpty()) emptyMap() else fetcher.fetch(due) { st.market.entries[it]?.fetchedAt }
        for ((x, r) in results) log("  fetch ${x.logName()}: ${describe(r)}")

        val market = st.market.merge(results, now).retainOnly(wantedQuotables(hs, now))
        val step = stepAlerts(hs, market.quotes(), st.alerts, now)
        val posted = step.fire.count { firing -> post(firing).also { log("  alert ${firing.key.stableTag()} posted=$it") } }
        log("alerts fired=${step.fire.size} posted=$posted states=${step.next.size}")

        refreshState.updateData { RefreshState(market, step.next) }
        log("state saved quotes=${market.entries.size} alerts=${step.next.size}")

        val outcome = publish(PortfolioSnapshot(hs, market.quotes()))
        log("watch publish: $outcome")
        log("round end in ${Duration.between(now, clock()).toMillis()} ms")
    }
}

/**
 * 報價快取要留的商品：還沒到期的持股，加上有美元部位時的匯率。跟 core 的 dueForFetch 用同一條規則；
 * 已到期的期貨不抓也不留，估值照樣顯示「已到期」。
 */
internal fun wantedQuotables(holdings: List<Holding>, now: Instant): Set<Quotable> {
    val instruments = holdings.filterNot { it.isExpired(now) }.map { it.asset.instrument }.toSet()
    return if (instruments.any { it.currency == Currency.USD }) instruments + FxPair.USD_TWD else instruments
}

private fun describe(r: FetchResult): String = when (r) {
    is FetchResult.Got -> "ok last=${r.quote.last.value.toPlainString()} prev=${r.quote.previousClose.value.toPlainString()} " +
        "asOf=${r.quote.asOf} ${r.quote.freshness}"
    is FetchResult.Failed -> "failed ${r.failure}"
}
