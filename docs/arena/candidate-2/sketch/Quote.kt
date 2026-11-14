package com.example.holdings.core

import java.time.Instant

/** 一筆報價（領域型別）。沒有 Yahoo 的欄位名、沒有 JSON；傳輸格式在 :quotes 的 adapter 內解析完就丟。 */
data class Quote(
    val last: Price,
    val prevClose: Price,
    val asOf: Instant,          // 交易所成交時間，不是抓取時間
)

/** 報價加上「我們何時抓的」。抓取時間只服務刷新規則，不進顯示。 */
data class QuoteRecord(val quote: Quote, val fetchedAt: Instant, val source: SourceId)

@JvmInline value class SourceId(val raw: String)

enum class Freshness { LIVE, FINAL, STALE, MISSING }

object QuoteRules {
    /**
     * 新鮮度由報價與時鐘推導，不存。
     * FINAL = 報價時間已越過它所屬那一盤的收盤（收盤價不會再變，不必再抓）。
     * LIVE  = 開盤中且報價不老於 [liveTolerance]（兩個刷新週期）；否則 STALE。
     * 24 小時市場永遠不是 FINAL。
     */
    fun freshness(i: Instrument, rec: QuoteRecord?, now: Instant, liveTolerance: java.time.Duration): Freshness =
        TODO("not implemented")

    /**
     * 刷新規則（唯一一條）：
     * due = 沒有報價 || 不是 FINAL || rec.fetchedAt < i.sessions.latestOpenAtOrBefore(now)
     * 第三項處理「新的一盤開了，昨天的收盤價作廢」；休市日也適用：開盤後抓一次拿到舊 asOf，
     * fetchedAt 就越過了最近一次開盤，之後不再重抓。不需要休市日曆。
     */
    fun due(i: Instrument, rec: QuoteRecord?, now: Instant): Boolean = TODO("not implemented")

    /** 匯率一天一次：以台北日期為準，資料日期比今天舊才抓。 */
    fun fxDue(rate: FxRate?, now: Instant): Boolean = TODO("not implemented: rate==null || taipeiDate(rate.asOf) < taipeiDate(now)")
}
