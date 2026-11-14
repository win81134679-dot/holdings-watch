package holdings.phone

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import holdings.core.AlertFiring
import holdings.core.Holding
import holdings.core.PortfolioSnapshot
import holdings.core.RefreshMode
import holdings.core.RefreshState
import holdings.quotes.QuoteFetcher
import kotlinx.coroutines.flow.first
import java.time.Clock

/**
 * 一輪刷新。整個 App 唯一的報價抓取、提醒判斷、手錶發布路徑；背景、前景、手動都走這裡。
 *
 * 冪等與當機：每一步都從「現在存的狀態」推導，不從上一輪打算做什麼推導。
 *   - 在任何一行當掉 → 下一輪重讀兩個存檔，算出同樣的 due、同樣的 firing。
 *   - 通知在存狀態「之前」發：當機在兩者之間 → 下一輪再發一次同一則，tag 相同 → 取代，不重複震動。
 *     反過來（先存後發）當機會漏發，漏發比重發糟。
 *   - 發布到手錶放最後、不看抓取成敗：沒網路時編輯持股，手錶仍會收到新持股。
 *     內容沒變 → 位元組相同 → Data Layer 不觸發變更（Codec 刻意不放發布時間）。
 */
internal class Refresher(
    private val holdings: DataStore<List<Holding>>,
    private val refreshState: DataStore<RefreshState>,
    private val fetcher: QuoteFetcher,
    private val notifier: AlertNotifier,
    private val watch: WatchPublisher,
    private val clock: Clock,
) {
    /**
     * TODO 虛擬碼：
     *   now = clock.instant()
     *   hs = holdings.data.first();  st = refreshState.data.first()
     *   due = dueForFetch(hs, st.market, mode, now)
     *   results = if (due.isEmpty()) emptyMap() else fetcher.fetch(due)
     *   market = st.market.merge(results, now).retainOnly(wanted(hs))
     *   step = stepAlerts(hs, market.quotes(), st.alerts, now)
     *   step.fire.forEach(notifier::post)
     *   refreshState.updateData { RefreshState(market, step.next) }   // 單一寫入者，直接覆蓋
     *   watch.publish(PortfolioSnapshot(hs, market.quotes()))
     */
    suspend fun run(mode: RefreshMode): Unit = TODO("not implemented")
}

/**
 * WorkManager 外殼。永遠回 success：抓取失敗已記在 RefreshState 裡、由 FetchPolicy 退避；
 * 回 retry 會讓 WorkManager 另外排一套退避，變成兩個真相來源。
 */
internal class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result =
        TODO("not implemented: AppGraph.refresher(applicationContext).run(mode 從 inputData 讀，預設 BACKGROUND)；return Result.success()")
}

/**
 * 排程。Refresher 只在一個唯一工作名下跑，所以同時只有一個（單一寫入者由 WorkManager 保證，不靠鎖）：
 *   "refresh-now"      OneTimeWork(RefreshWorker)，enqueueUniqueWork(APPEND_OR_REPLACE)。
 *                      編輯、前景、手動、週期叫醒全都排進這條隊伍；排在進行中那輪之後，剛存的持股一定被看到。
 *                      不設網路限制：沒網路時這輪抓取全部失敗，但提醒與發布到手錶照跑。
 *   "refresh-periodic" PeriodicWork(PeriodicKickWorker, 15 分)，Application.onCreate 時 enqueueUniquePeriodicWork(KEEP)。
 *                      它本身什麼都不做，只呼叫 runSoon(BACKGROUND)。WorkManager 跨重開機保存，不需要開機接收器。
 * 週期工作不直接跑 Refresher：兩個唯一名稱可以同時執行，直接跑就會有兩個寫入者。
 */
internal object RefreshScheduler {
    fun ensurePeriodic(context: Context): Unit = TODO("not implemented")
    fun runSoon(context: Context, mode: RefreshMode): Unit = TODO("not implemented")
}

/** 到價／轉倉通知。手機發，系統自動橋接到 Pixel Watch 並震動。 */
internal class AlertNotifier(private val context: Context) {
    /**
     * TODO：
     *   channel "price-alerts"（IMPORTANCE_HIGH，震動）；Android 13+ 要 POST_NOTIFICATIONS 權限（首次新增停損／目標時請求）。
     *   NotificationManagerCompat.notify(tag = firing.key.stableTag(), id = 1, …)
     *   setOnlyAlertOnce(true)：同 tag 重發時若舊通知還在，不再震動。
     *   文字：「2330 台積電 觸及停損 580（現價 578）」「TX 2026-12 結算日 12/16，3 天後」
     *   點擊 → 開手機 App 該持股的編輯頁。
     */
    fun post(firing: AlertFiring): Unit = TODO("not implemented")
}
