package tw.personal.holdings.wear

import tw.personal.holdings.core.Crypto
import tw.personal.holdings.core.Currency
import tw.personal.holdings.core.ExitStatus
import tw.personal.holdings.core.Money
import tw.personal.holdings.core.PositionValue
import tw.personal.holdings.core.Side
import tw.personal.holdings.core.TAIPEI
import tw.personal.holdings.core.TwEquity
import tw.personal.holdings.core.TwFuture
import tw.personal.holdings.core.UsEquity
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Tile、App、小格共用的顯示規則。三個介面的字串與顏色只在這裡決定。 */

/** 台灣習慣漲紅跌綠：正數紅、負數綠。 */
enum class Tone { UP, DOWN, FLAT }

const val UP_ARGB: Int = 0xFFFF6B6B.toInt()
const val DOWN_ARGB: Int = 0xFF4ADE80.toInt()

fun tone(value: BigDecimal): Tone = when (value.signum()) {
    1 -> Tone.UP
    -1 -> Tone.DOWN
    else -> Tone.FLAT
}

/** 停損是賠出場、目標是賺出場，所以點的顏色跟損益同一套。 */
fun ExitStatus.tone(): Tone? = when (this) {
    ExitStatus.CLEAR -> null
    ExitStatus.STOP_HIT -> Tone.DOWN
    ExitStatus.TARGET_HIT -> Tone.UP
}

fun ExitStatus.label(): String? = when (this) {
    ExitStatus.CLEAR -> null
    ExitStatus.STOP_HIT -> "碰停損"
    ExitStatus.TARGET_HIT -> "達目標"
}

fun PositionValue.label(): String {
    val h = holding
    val base = h.name ?: when (val x = h.asset.instrument) {
        is TwEquity -> x.code.value
        is UsEquity -> x.ticker.value
        is Crypto -> x.coin.value
        is TwFuture -> "${x.product.taifexCode} ${x.month.yearMonth.monthValue}月"
    }
    return if (h.side == Side.SHORT) "空 $base" else base
}

fun signedPct(pct: BigDecimal, decimals: Int): String =
    signed(pct.setScale(decimals, RoundingMode.HALF_UP), decimals) + "%"

fun signedTwd(amount: BigDecimal): String = signed(amount.setScale(0, RoundingMode.HALF_UP), 0)

fun signedMoney(m: Money): String = when (m.currency) {
    Currency.TWD -> signedTwd(m.amount)
    Currency.USD -> {
        val rounded = m.amount.setScale(2, RoundingMode.HALF_UP)
        val sign = if (rounded.signum() > 0) "+" else if (rounded.signum() < 0) "-" else ""
        sign + "US$" + grouped(rounded.abs(), 2)
    }
}

fun priceText(value: BigDecimal): String =
    if (value >= BigDecimal.ONE) grouped(value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros(), 2)
    else value.stripTrailingZeros().toPlainString()

fun dataTime(at: Instant?, now: Instant): String {
    if (at == null) return "--"
    val t = at.atZone(TAIPEI)
    val sameDay = t.toLocalDate() == now.atZone(TAIPEI).toLocalDate()
    return t.format(if (sameDay) HH_MM else M_D_HH_MM)
}

private fun signed(value: BigDecimal, decimals: Int): String {
    val sign = if (value.signum() > 0) "+" else if (value.signum() < 0) "-" else ""
    return sign + grouped(value.abs(), decimals)
}

private fun grouped(value: BigDecimal, maxDecimals: Int): String =
    DecimalFormat("#,##0").apply {
        minimumFractionDigits = if (maxDecimals == 0) 0 else minOf(value.scale().coerceAtLeast(0), maxDecimals)
        maximumFractionDigits = maxDecimals
    }.format(value)

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val M_D_HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")
