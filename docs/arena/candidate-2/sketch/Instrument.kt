package com.example.holdings.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/**
 * 商品是 sealed：四個市場各一個分支，每個分支自己回答「幣別、倍數、數量規則、報價鍵」。
 * 損益公式因此只有一條：side.sign * (last - cost) * qty * multiplier（股票 multiplier = 1，期貨 = 每點價值）。
 * 沒有任何 `if (market == FUTURES)` 散在外面。
 */
sealed interface Instrument {
    val key: InstrumentKey
    val currency: Currency
    val multiplier: BigDecimal
    val sessions: MarketSessions

    /** 邊界：表單輸入 -> Quantity。小數位規則、張與股換算都在這裡。 */
    fun quantity(raw: BigDecimal, unit: QtyInputUnit): Result<Quantity>

    data class TwStock(val code: String, val board: Board, val kind: Kind) : Instrument {
        enum class Board { LISTED, OTC }          // 上市 .TW / 上櫃 .TWO；Yahoo 後綴是 adapter 的事，不在這裡
        enum class Kind { STOCK, ETF }
        override val key: InstrumentKey get() = TODO("not implemented: 'TWSE:2330' / 'TPEX:6488'")
        override val currency get() = Currency.TWD
        override val multiplier: BigDecimal get() = BigDecimal.ONE
        override val sessions: MarketSessions get() = MarketSessions.TW_CASH
        override fun quantity(raw: BigDecimal, unit: QtyInputUnit): Result<Quantity> =
            TODO("not implemented: TW_LOT -> raw*1000 股；SHARE 必須整數；其他單位 UnitNotAllowed")
    }

    data class UsStock(val ticker: String) : Instrument {
        override val key: InstrumentKey get() = TODO("not implemented: 'US:AAPL'")
        override val currency get() = Currency.USD
        override val multiplier: BigDecimal get() = BigDecimal.ONE
        override val sessions: MarketSessions get() = MarketSessions.US_CASH
        override fun quantity(raw: BigDecimal, unit: QtyInputUnit): Result<Quantity> =
            TODO("not implemented: SHARE，小數位 <= 6")
    }

    data class Crypto(val base: String) : Instrument {     // 報價幣固定 USD；要 USDT 再開分支
        override val key: InstrumentKey get() = TODO("not implemented: 'CRYPTO:BTC'")
        override val currency get() = Currency.USD
        override val multiplier: BigDecimal get() = BigDecimal.ONE
        override val sessions: MarketSessions get() = MarketSessions.ALWAYS
        override fun quantity(raw: BigDecimal, unit: QtyInputUnit): Result<Quantity> =
            TODO("not implemented: COIN，小數位 <= 8")
    }

    /** 期貨：商品＋交割月。乘數來自 [FuturesProduct]，不是使用者輸入（決策 Q10：自動帶每點價值）。 */
    data class Future(val contract: FutureContract) : Instrument {
        override val key: InstrumentKey get() = TODO("not implemented: 'TAIFEX:TX:2026-12'")
        override val currency get() = Currency.TWD
        override val multiplier: BigDecimal get() = contract.product.multiplier
        override val sessions: MarketSessions get() = MarketSessions.TW_FUTURES
        override fun quantity(raw: BigDecimal, unit: QtyInputUnit): Result<Quantity> =
            TODO("not implemented: CONTRACT，必須正整數")
    }

    companion object {
        /** 儲存與同步唯一的身分表示法；Room 與 BoardCodec 都只存 key 字串，還原靠這個。 */
        fun parse(key: InstrumentKey): Instrument? = TODO("not implemented: 與 key getter 互為反函數；測試做 round-trip")
    }
}

@JvmInline
value class InstrumentKey(val raw: String)

data class FutureContract(val product: FuturesProduct, val delivery: YearMonth) {
    /** 結算日由規則推導，不存。月契約：交割月第三個星期三；遇休市順延（需要日曆，見 [SettlementCalendar]）。開工前向期交所規格核對。 */
    fun settlementDate(calendar: SettlementCalendar): LocalDate = TODO("not implemented")

    /** 轉倉提醒日 = 結算日前 3 天（決策 Q10）。純推導，沒有「提醒已排程」這種狀態。 */
    fun rolloverFrom(calendar: SettlementCalendar): LocalDate = settlementDate(calendar).minusDays(ROLLOVER_LEAD_DAYS)

    companion object { const val ROLLOVER_LEAD_DAYS = 3L }
}

/** 期交所結算日需要知道休市日。介面一個函式，內容是打包進 App 的休市表（TODO：資料來源與更新方式）。 */
fun interface SettlementCalendar {
    fun nextTradingDayOnOrAfter(date: LocalDate): LocalDate
}

/**
 * 期貨商品登記表：新增商品只改這一張表（單一真相來源），乘數、代號、顯示名都從這裡來。
 * 數字依期交所契約規格：TX 每點 200、MTX 50、TMF 10；個股期貨以標的股數計（標準 2000 股、小型 100 股）。
 * 這些數字本 session 沒有向期交所原文核對，實作前核對（見 design.md 未解問題）。
 */
sealed class FuturesProduct(val code: String, val multiplier: BigDecimal) {
    data object TX : FuturesProduct("TX", BigDecimal(200))
    data object MTX : FuturesProduct("MTX", BigDecimal(50))
    data object TMF : FuturesProduct("TMF", BigDecimal(10))
    /** 個股期貨：商品代號與標的由使用者從清單挑，乘數 = 每口股數。 */
    data class SingleStock(val productCode: String, val underlying: String, val sharesPerContract: Int) :
        FuturesProduct(productCode, BigDecimal(sharesPerContract))

    companion object {
        val indexProducts: List<FuturesProduct> = listOf(TX, MTX, TMF)
    }
}

/** 開盤時段。純資料；「現在開不開」「某報價是否已是收盤終值」都從它推導，不存旗標。 */
interface MarketSessions {
    /** [at] 當下或之前最近一次排定的開盤時刻。刷新規則用它判斷「這筆報價是不是比最近一次開盤還舊」。 */
    fun latestOpenAtOrBefore(at: java.time.Instant): java.time.Instant?
    /** 報價時間戳落在哪一盤，該盤的收盤時刻；報價時間已 >= 收盤時刻（含容許誤差）即為終值。24 小時市場回 null。 */
    fun closeOfSessionContaining(at: java.time.Instant): java.time.Instant?

    companion object {
        val TW_CASH: MarketSessions = TODO("not implemented: 週一至五 09:00–13:30 Asia/Taipei（零股另有盤，先不分）")
        val TW_FUTURES: MarketSessions = TODO("not implemented: 日盤 08:45–13:45、夜盤 15:00–次日 05:00")
        val US_CASH: MarketSessions = TODO("not implemented: 09:30–16:00 America/New_York，含夏令；盤前盤後不算正常盤")
        val ALWAYS: MarketSessions = TODO("not implemented: latestOpen = at，closeOf = null")
    }
}
