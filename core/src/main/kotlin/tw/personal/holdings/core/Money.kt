package tw.personal.holdings.core

import java.math.BigDecimal

/** 窮舉：多一種幣時，每個 `when` 都會編譯失敗，逼你補換匯。 */
enum class Currency { TWD, USD }

/** 原幣金額。沒有 plus：跨部位加總一律先轉成 [Twd]，混幣相加寫不出來。 */
data class Money(val amount: BigDecimal, val currency: Currency)

@JvmInline
value class Twd(val amount: BigDecimal) {
    operator fun plus(other: Twd): Twd = Twd(amount + other.amount)

    companion object {
        val ZERO = Twd(BigDecimal.ZERO)
    }
}

/**
 * 單價，> 0。幣別由商品決定，不放這裡。
 * 建立時去掉尾端的 0，讓 580 與 580.0 相等（BigDecimal.equals 會比 scale）。
 */
@JvmInline
value class Price private constructor(val value: BigDecimal) {
    companion object {
        fun of(value: BigDecimal): Price? = if (value.signum() > 0) Price(normalized(value)) else null
        fun parse(text: String): Price? = decimalOrNull(text)?.let(::of)
    }
}

enum class Side(val sign: Int) { LONG(+1), SHORT(-1) }

/** 台股持有股數，一律存「股」；張只是輸入與顯示單位（[TwQtyUnit]）。 */
@JvmInline
value class Shares private constructor(val count: Long) {
    companion object {
        fun of(count: Long): Shares? = if (count > 0) Shares(count) else null
    }
}

enum class TwQtyUnit(val sharesPerUnit: Long) { LOT(1000), SHARE(1) }

/** 美股股數，可小數。 */
@JvmInline
value class FractionalShares private constructor(val count: BigDecimal) {
    companion object {
        fun of(count: BigDecimal): FractionalShares? =
            if (count.signum() > 0) FractionalShares(normalized(count)) else null
    }
}

/** 期貨口數。方向由 [Side] 表示，不用負數口數。 */
@JvmInline
value class Contracts private constructor(val count: Int) {
    companion object {
        fun of(count: Int): Contracts? = if (count > 0) Contracts(count) else null
    }
}

const val COIN_MAX_DECIMALS = 8

@JvmInline
value class CoinAmount private constructor(val amount: BigDecimal) {
    companion object {
        fun of(amount: BigDecimal): CoinAmount? {
            if (amount.signum() <= 0) return null
            val n = normalized(amount)
            return if (n.scale() <= COIN_MAX_DECIMALS) CoinAmount(n) else null
        }
    }
}

internal fun normalized(value: BigDecimal): BigDecimal =
    value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

internal fun decimalOrNull(text: String): BigDecimal? =
    text.trim().replace(",", "").takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
