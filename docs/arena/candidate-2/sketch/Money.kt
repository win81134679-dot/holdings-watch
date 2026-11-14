package com.example.holdings.core

import java.math.BigDecimal
import java.math.RoundingMode

// 所有金額、價格、數量都是 BigDecimal 包起來的 value class；不用 Double，避免加密貨幣 8 位小數與損益加總漂移。
// 不變式在建立時驗一次，往下一律信任型別。

enum class Currency { TWD, USD }

/** 價格：必為正數。期貨與股票共用；單位由 Instrument.currency 決定，所以 Price 本身不帶幣別。 */
@JvmInline
value class Price private constructor(val value: BigDecimal) : Comparable<Price> {
    override fun compareTo(other: Price): Int = TODO("not implemented")

    companion object {
        /** 邊界解析：<= 0 回 null。呼叫端（表單、Yahoo 解析器）各自決定怎麼處理 null。 */
        fun of(raw: BigDecimal): Price? = TODO("not implemented")
    }
}

/** 一筆帶幣別的金額。跨幣別相加編譯得過但執行期丟錯會被測到：所以加法只提供 [plus]，並在不同幣別時 require 失敗；真正的換算只走 [FxRate.convert]。 */
data class Money(val amount: BigDecimal, val currency: Currency) {
    operator fun plus(other: Money): Money = TODO("not implemented: require(currency == other.currency)")
    operator fun unaryMinus(): Money = TODO("not implemented")
}

/** 百分比以小數存（0.0123 = 1.23%），顯示才乘 100。 */
@JvmInline
value class Ratio(val value: BigDecimal)

/**
 * 數量，一律存「最小成交單位」：台股存股數（1 張 = 1000 股）、美股存股（可小數）、期貨存口、加密存顆。
 * 建立只能經 [Instrument.quantity]，所以小數位數規則（台股整數、加密 ≤ 8 位）寫在商品型別裡，不散在表單。
 */
@JvmInline
value class Quantity internal constructor(val value: BigDecimal)

/** 使用者輸入的數量單位。台股可輸入「張」或「股」，其他市場只有一種。 */
enum class QtyInputUnit { TW_LOT, SHARE, CONTRACT, COIN }

sealed interface QuantityError {
    data class NotPositive(val raw: BigDecimal) : QuantityError
    data class TooManyDecimals(val raw: BigDecimal, val max: Int) : QuantityError
    data class UnitNotAllowed(val unit: QtyInputUnit) : QuantityError
}

/** 匯率：1 單位 [from] 等於 [rate] 單位 [to]。目前只有 USD->TWD，但型別不把方向寫死。 */
data class FxRate(val from: Currency, val to: Currency, val rate: Price, val asOf: java.time.Instant) {
    /** 純函式。from 對不上就 require 失敗（呼叫端在邊界挑對匯率，內部不再檢查）。 */
    fun convert(m: Money): Money = TODO("not implemented: require(m.currency == from); amount*rate, scale 2, HALF_UP")
}

internal val DISPLAY_ROUNDING = RoundingMode.HALF_UP
