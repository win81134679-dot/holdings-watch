package tw.personal.holdings.core

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")

/** FX 不可持有；它存在只為了讓匯率跟報價走同一條抓取與快取路徑。 */
enum class Market { TW_EQUITY, US_EQUITY, TW_FUTURES, CRYPTO, FX }

/** 任何有報價的東西。報價快取以它為鍵。 */
sealed interface Quotable {
    val market: Market
}

enum class FxPair : Quotable {
    USD_TWD;

    override val market: Market get() = Market.FX
}

/** 可持有的商品。資料來源的代號（2330.TW、WTX&）是 :quotes 的事，不在這裡。 */
sealed interface Instrument : Quotable {
    val currency: Currency
}

/** 台股上市、上櫃、ETF。ETF 在損益上跟股票沒差別，所以不分型別。 */
data class TwEquity(val code: TwCode, val board: TwBoard) : Instrument {
    override val market get() = Market.TW_EQUITY
    override val currency get() = Currency.TWD
}

enum class TwBoard { TWSE, TPEX }

@JvmInline
value class TwCode private constructor(val value: String) {
    companion object {
        private val PATTERN = Regex("^[0-9]{4}[0-9A-Z]{0,2}$")
        fun parse(text: String): TwCode? = text.trim().uppercase().takeIf(PATTERN::matches)?.let(::TwCode)
    }
}

data class UsEquity(val ticker: UsTicker) : Instrument {
    override val market get() = Market.US_EQUITY
    override val currency get() = Currency.USD
}

@JvmInline
value class UsTicker private constructor(val value: String) {
    companion object {
        private val PATTERN = Regex("^[A-Z][A-Z0-9.\\-]{0,9}$")
        fun parse(text: String): UsTicker? = text.trim().uppercase().takeIf(PATTERN::matches)?.let(::UsTicker)
    }
}

/** 一律以 USD 計價。 */
data class Crypto(val coin: CoinSymbol) : Instrument {
    override val market get() = Market.CRYPTO
    override val currency get() = Currency.USD
}

@JvmInline
value class CoinSymbol private constructor(val value: String) {
    companion object {
        private val PATTERN = Regex("^[A-Z0-9]{2,10}$")
        fun parse(text: String): CoinSymbol? = text.trim().uppercase().takeIf(PATTERN::matches)?.let(::CoinSymbol)
    }
}

data class TwFuture(val product: FutureProduct, val month: ContractMonth) : Instrument {
    override val market get() = Market.TW_FUTURES
    override val currency get() = Currency.TWD
    val pointValue: BigDecimal get() = product.pointValueTwd

    /** 最後交易日當天 13:30 收盤之後，這口合約就不再交易。 */
    val expiresAt: Instant get() = month.lastTradingDay().atTime(LAST_DAY_CLOSE).atZone(TAIPEI).toInstant()

    fun isExpired(now: Instant): Boolean = now >= expiresAt

    private companion object {
        val LAST_DAY_CLOSE: LocalTime = LocalTime.of(13, 30)
    }
}

sealed interface FutureProduct {
    val taifexCode: String
    val pointValueTwd: BigDecimal
    val hasNightSession: Boolean
}

/** 每點價值：臺股期貨 200、小型臺指 50、微型臺指 10（期交所契約規格）。 */
enum class IndexFuture(pointValue: Int) : FutureProduct {
    TX(200), MTX(50), TMF(10);

    override val taifexCode: String get() = name
    override val pointValueTwd: BigDecimal = BigDecimal(pointValue)
    override val hasNightSession: Boolean get() = true
}

/** 個股期貨。一點＝標的股價 1 元，所以每點價值＝契約股數。夜盤只有部分標的有，先一律當成只有日盤。 */
@ConsistentCopyVisibility
data class StockFuture private constructor(
    override val taifexCode: String,
    val underlying: TwCode,
    val size: StockFutureSize,
) : FutureProduct {
    override val pointValueTwd: BigDecimal get() = BigDecimal(size.shares)
    override val hasNightSession: Boolean get() = false

    companion object {
        private val PATTERN = Regex("^[A-Z0-9]{2,4}$")
        fun of(taifexCode: String, underlying: TwCode, size: StockFutureSize): StockFuture? =
            taifexCode.trim().uppercase().takeIf(PATTERN::matches)?.let { StockFuture(it, underlying, size) }
    }
}

enum class StockFutureSize(val shares: Int) { STANDARD(2000), SMALL(100) }

data class ContractMonth(val yearMonth: YearMonth) : Comparable<ContractMonth> {
    /** 交割月份第三個星期三（規則日；沒有假日表，不處理順延）。 */
    fun lastTradingDay(): LocalDate = firstWednesday(yearMonth).plusWeeks(2)

    override fun compareTo(other: ContractMonth): Int = yearMonth.compareTo(other.yearMonth)
    override fun toString(): String = yearMonth.toString()

    companion object {
        fun of(year: Int, month: Int): ContractMonth = ContractMonth(YearMonth.of(year, month))
        fun parse(text: String): ContractMonth? = runCatching { ContractMonth(YearMonth.parse(text.trim())) }.getOrNull()

        /**
         * 表單下拉選單：今天掛牌的合約月份。指數期貨＝連續三個月＋接續三個季月；個股期貨＝連續兩個月＋接續三個季月。
         * 當月合約過了最後交易日就從下個月算起。
         */
        fun listed(product: FutureProduct, today: LocalDate): List<ContractMonth> {
            val thisMonth = ContractMonth(YearMonth.from(today))
            val first = if (today > thisMonth.lastTradingDay()) thisMonth.yearMonth.plusMonths(1) else thisMonth.yearMonth
            val consecutive = if (product is IndexFuture) 3 else 2
            val near = (0 until consecutive).map { first.plusMonths(it.toLong()) }
            val quarterly = generateSequence(near.last().plusMonths(1)) { it.plusMonths(1) }
                .filter { it.monthValue % 3 == 0 }
                .take(3)
            return (near + quarterly).map(::ContractMonth)
        }
    }
}

internal val WEEKDAYS: Set<DayOfWeek> = DayOfWeek.entries.toSet() - DayOfWeek.SATURDAY - DayOfWeek.SUNDAY

internal fun firstWednesday(month: YearMonth): LocalDate =
    month.atDay(1).with(TemporalAdjusters.firstInMonth(DayOfWeek.WEDNESDAY))
