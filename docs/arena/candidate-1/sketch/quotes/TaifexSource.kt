package holdings.quotes

import holdings.core.FetchResult
import holdings.core.Quotable
import java.time.Clock

/**
 * 台灣期貨報價（使用者指定期交所資料）。
 *
 * 端點未定：grounding 指向的研究檔（上架收費與資料授權研究.md）在 2026-11-14 只有授權面的 C-FUT 節，
 * 沒有「期交所資料取得方式」。所以這個來源先做成可替換的殼：形狀確定、端點留 TODO。
 *
 * 已知的候選（都未實測，實作前要查、要問使用者接受哪種新鮮度）：
 *  - 期交所 OpenAPI（openapi.taifex.com.tw，政府資料開放授權）：盤後資料 → Freshness.END_OF_DAY，
 *    一天抓一次，previousClose 用前一日結算價。
 *  - 期交所網站行情頁背後的資料請求：可能有盤中價，但條款與穩定性未知。
 *
 * 一個請求多半能拿到所有期貨商品的當日行情，所以這個來源應「一輪一個請求、自己篩出持有的合約」，
 * 而不是一個合約一個請求。合約識別：product.taifexCode ＋ 交割月份（YYYYMM）。
 */
internal class TaifexSource(private val http: HttpGet, private val clock: Clock) : Source {

    override fun handles(item: Quotable): Boolean = isFuture(item)

    override suspend fun fetch(items: Set<Quotable>): Map<Quotable, FetchResult> =
        TODO("not implemented: 端點未定。打一次 → 解析成 (taifexCode, YYYYMM) → Quote 的表 → 對每個持有的合約查表；查不到 → NotFound")
}
