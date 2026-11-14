package tw.personal.holdings.phone

import tw.personal.holdings.core.AlertFiring
import tw.personal.holdings.core.AlertKind
import tw.personal.holdings.core.Crypto
import tw.personal.holdings.core.CryptoAsset
import tw.personal.holdings.core.Currency
import tw.personal.holdings.core.FetchFailure
import tw.personal.holdings.core.FutureAsset
import tw.personal.holdings.core.FutureProduct
import tw.personal.holdings.core.FxPair
import tw.personal.holdings.core.Holding
import tw.personal.holdings.core.IndexFuture
import tw.personal.holdings.core.Money
import tw.personal.holdings.core.Price
import tw.personal.holdings.core.Quotable
import tw.personal.holdings.core.StockFuture
import tw.personal.holdings.core.StockFutureSize
import tw.personal.holdings.core.TAIPEI
import tw.personal.holdings.core.TwEquity
import tw.personal.holdings.core.TwEquityAsset
import tw.personal.holdings.core.TwFuture
import tw.personal.holdings.core.TwQtyUnit
import tw.personal.holdings.core.Twd
import tw.personal.holdings.core.UsEquity
import tw.personal.holdings.core.UsEquityAsset
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

internal fun todayTaipei(): LocalDate = LocalDate.now(TAIPEI)

/** 代號＋使用者填的名稱；期貨用中文商品名＋合約月份。 */
internal fun Holding.label(): String {
    val base = when (val x = asset.instrument) {
        is TwEquity -> x.code.value
        is UsEquity -> x.ticker.value
        is Crypto -> x.coin.value
        is TwFuture -> "${futureName(x.product)} ${x.month}"
    }
    return name?.let { "$base $it" } ?: base
}

internal fun futureName(p: FutureProduct): String = when (p) {
    is IndexFuture -> when (p) {
        IndexFuture.TX -> "台指期"
        IndexFuture.MTX -> "小台"
        IndexFuture.TMF -> "微台"
    }
    is StockFuture -> "${p.underlying.value}${if (p.size == StockFutureSize.SMALL) "小型" else ""}個股期"
}

internal fun Holding.quantityText(): String = when (val a = asset) {
    is TwEquityAsset -> when (a.inputUnit) {
        TwQtyUnit.LOT -> "${grouped(BigDecimal(a.shares.count).divide(BigDecimal(1000)), 3)} 張"
        TwQtyUnit.SHARE -> "${grouped(BigDecimal(a.shares.count), 0)} 股"
    }
    is UsEquityAsset -> "${grouped(a.shares.count, 6)} 股"
    is FutureAsset -> "${a.contracts.count} 口"
    is CryptoAsset -> "${grouped(a.amount.amount, 8)} ${a.instrument.coin.value}"
}

/** 給 log 看的商品名，不是存檔鍵。 */
internal fun Quotable.logName(): String = when (this) {
    is TwEquity -> "${code.value}.$board"
    is UsEquity -> ticker.value
    is Crypto -> coin.value
    is TwFuture -> "${product.taifexCode}${(product as? StockFuture)?.let { "(${it.underlying.value})" } ?: ""}-$month"
    is FxPair -> name
}

/** 首頁「讀不到報價」底下的原因：講使用者能怎麼做，不講技術名詞。 */
internal fun failureText(f: FetchFailure): String = when (f) {
    FetchFailure.NotFound -> "查不到這個代號，請確認代號或合約月份"
    is FetchFailure.Throttled -> "報價網站暫時忙碌，稍後會自動再試"
    FetchFailure.Network -> "網路連不上，恢復後會自動更新"
    is FetchFailure.Unparseable -> "報價網站改版了，暫時讀不到"
    FetchFailure.Unsupported -> "這類商品目前沒有報價來源"
}

private val SYMBOLS = DecimalFormatSymbols(Locale.US)

internal fun grouped(value: BigDecimal, maxFraction: Int, minFraction: Int = 0): String {
    val f = DecimalFormat("#,##0", SYMBOLS)
    f.maximumFractionDigits = maxFraction
    f.minimumFractionDigits = minFraction
    f.roundingMode = RoundingMode.HALF_UP
    val rounded = value.setScale(maxFraction, RoundingMode.HALF_UP)
    return f.format(if (rounded.signum() == 0) BigDecimal.ZERO else rounded)
}

/** 正數帶「+」；四捨五入後是 0 的不帶正負號（不會出現「-0」）。 */
internal fun signed(value: BigDecimal, maxFraction: Int, minFraction: Int = 0): String {
    val rounded = value.setScale(maxFraction, RoundingMode.HALF_UP)
    val sign = if (rounded.signum() > 0) "+" else ""
    return sign + grouped(rounded, maxFraction, minFraction)
}

/** 顯示用的方向：四捨五入到顯示位數後的正負，顏色跟著它走，才不會出現紅色的「0.00%」。 */
internal fun displaySign(value: BigDecimal, maxFraction: Int): Int = value.setScale(maxFraction, RoundingMode.HALF_UP).signum()

internal fun priceText(p: Price): String = grouped(p.value, 4)

internal fun pctText(pct: BigDecimal): String = signed(pct, 2, 2) + "%"

internal fun twdText(t: Twd): String = signed(t.amount, 0)

internal fun moneyText(m: Money): String = when (m.currency) {
    Currency.TWD -> signed(m.amount, 0)
    Currency.USD -> signed(m.amount, 2, 2) + " USD"
}

internal fun moneyFraction(m: Money): Int = if (m.currency == Currency.USD) 2 else 0

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(TAIPEI)

internal fun clockText(at: Instant): String = CLOCK_FORMAT.format(at)

internal fun agoText(at: Instant, now: Instant): String {
    val minutes = ChronoUnit.MINUTES.between(at, now)
    return when {
        minutes < 1 -> "剛剛"
        minutes < 60 -> "$minutes 分鐘前"
        minutes < 60 * 24 -> "${minutes / 60} 小時前"
        else -> "${minutes / (60 * 24)} 天前"
    }
}

/** 通知文字。中文字串只在手機組，core 不碰。 */
internal fun alertText(f: AlertFiring, today: LocalDate): String = when (f) {
    is AlertFiring.PriceCrossed -> {
        val what = if (f.key.kind == AlertKind.STOP_LOSS) "停損" else "目標"
        "${f.holding.label()} 碰到$what ${priceText(f.level)}（現價 ${priceText(f.last)}）"
    }
    is AlertFiring.Rollover ->
        "${f.holding.label()} ${daysAhead(ChronoUnit.DAYS.between(today, f.lastTradingDay))}最後交易日，記得轉倉"
}

private fun daysAhead(days: Long): String = when (days) {
    0L -> "今天"
    1L -> "明天"
    2L -> "兩天後"
    3L -> "三天後"
    else -> if (days < 0) "已過" else "$days 天後"
}
