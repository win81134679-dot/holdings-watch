package holdings.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")

/**
 * 手錶 App 的分頁、抓價排程、交易時段都以市場為單位。
 * FX 不是可持有的市場，只為了讓匯率走跟報價同一條抓取與快取路徑。
 */
enum class Market(val holdable: Boolean) { TW_EQUITY(true), US_EQUITY(true), TW_FUTURES(true), CRYPTO(true), FX(false) }

/** 任何有報價的東西：可持有的 [Instrument]，或換匯用的 [FxPair]。報價快取以它為鍵。 */
sealed interface Quotable { val market: Market }

/** 換匯。目前只需要美元兌台幣（美股、加密都以 USD 計價）。 */
enum class FxPair(val base: Currency, val quote: Currency) : Quotable {
    USD_TWD(Currency.USD, Currency.TWD);
    override val market: Market get() = Market.FX
}

/**
 * 可持有的商品。幣別由型別決定，不存欄位。
 * 這裡刻意沒有 Yahoo 代號（"2330.TW"）或期交所代碼字串的組法：那是資料來源的私事，放在 :quotes 的 internal。
 */
sealed interface Instrument : Quotable {
    val currency: Currency
}

/** 台股上市、上櫃、ETF。ETF 在損益上跟股票沒差別，所以不分型別。 */
data class TwEquity(val code: TwCode, val board: TwBoard) : Instrument {
    override val market get() = Market.TW_EQUITY
    override val currency get() = Currency.TWD
}

/** 上市（證交所）或上櫃（櫃買）。決定 Yahoo 後綴 .TW／.TWO，但那個對照在 :quotes。 */
enum class TwBoard { TWSE, TPEX }

/** 台股代號：4–6 碼英數（2330、00878、006208、00632R）。 */
@JvmInline
value class TwCode private constructor(val value: String) {
    companion object { fun parse(text: String): TwCode? = TODO("not implemented: trim、大寫、^[0-9]{4}[0-9A-Z]{0,2}$") }
}

data class UsEquity(val ticker: UsTicker) : Instrument {
    override val market get() = Market.US_EQUITY
    override val currency get() = Currency.USD
}

@JvmInline
value class UsTicker private constructor(val value: String) {
    companion object { fun parse(text: String): UsTicker? = TODO("not implemented: 大寫、^[A-Z][A-Z0-9.\\-]{0,9}$（BRK-B）") }
}

/** 加密貨幣，一律以 USD 計價（實測 BTC-USD 可用）。 */
data class Crypto(val coin: CoinSymbol) : Instrument {
    override val market get() = Market.CRYPTO
    override val currency get() = Currency.USD
}

@JvmInline
value class CoinSymbol private constructor(val value: String) {
    companion object { fun parse(text: String): CoinSymbol? = TODO("not implemented: 大寫、^[A-Z0-9]{2,10}$") }
}

/**
 * 台灣期貨：商品＋合約月份。每點價值由商品推導（使用者不輸入、不存），
 * 結算日由合約月份推導（不存）。
 */
data class TwFuture(val product: FutureProduct, val month: ContractMonth) : Instrument {
    override val market get() = Market.TW_FUTURES
    override val currency get() = Currency.TWD
    val pointValue: BigDecimal get() = product.pointValueTwd
}

/** 期貨商品。一點值多少台幣是商品的屬性。 */
sealed interface FutureProduct {
    /** 期交所商品代碼（TX、MTX、TMF；個股期貨是三碼代碼，例如台積電期貨）。 */
    val taifexCode: String
    val pointValueTwd: BigDecimal
}

/**
 * 指數期貨。每點價值 2026-11-14 查期交所規格：臺股期貨 200、小型臺指 50、微型臺指 10（20:5:1）。
 * https://www.taifex.com.tw/cht/2/tMF
 */
enum class IndexFuture(override val taifexCode: String, pointValue: Int) : FutureProduct {
    TX("TX", 200), MTX("MTX", 50), TMF("TMF", 10);
    override val pointValueTwd: BigDecimal = BigDecimal(pointValue)
}

/** 個股期貨。一點＝標的股價 1 元，所以每點價值＝契約股數。 */
data class StockFuture(
    override val taifexCode: String,
    val underlying: TwCode,
    val size: StockFutureSize,
) : FutureProduct {
    override val pointValueTwd: BigDecimal get() = BigDecimal(size.shares)
}

/** 2026-11-14 查：一般股票期貨每口 2,000 股；小型股票期貨每口 100 股。 */
enum class StockFutureSize(val shares: Int) { STANDARD(2000), SMALL(100) }

/** 期貨交割月份。 */
data class ContractMonth(val year: Int, val month: Int) : Comparable<ContractMonth> {
    /**
     * 最後交易日＝交割月份第三個星期三（期交所規格，TX／MTX／TMF／個股期貨相同）。
     * TODO 遇國定假日順延的規則與期交所公告的實際日期；沒有假日表之前，回傳的是「規則日」。
     */
    fun lastTradingDay(): LocalDate = TODO("not implemented: 該月 1 日 → 第一個星期三 → +14 天")

    override fun compareTo(other: ContractMonth): Int = TODO("not implemented")

    companion object {
        /**
         * 表單下拉選單用：今天可交易的合約月份。
         * TODO 規則因商品而異（指數期貨：連續三個月＋三個季月；個股期貨：連續兩個月＋三個季月）。
         * 已過最後交易日的當月合約要排除。
         */
        fun listed(product: FutureProduct, today: LocalDate): List<ContractMonth> = TODO("not implemented")
    }
}
