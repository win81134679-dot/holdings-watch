package holdings.core

import java.math.BigDecimal

/** App 認得的幣別。窮舉：多一種幣（例如日股）時，每個 `when` 都會編譯失敗，逼你補換匯。 */
enum class Currency { TWD, USD }

/**
 * 原幣金額。只用在單一部位的損益顯示（每檔原幣顯示）。
 * 跨部位加總一律先轉成 [Twd]，所以這個型別沒有 plus：混幣相加寫不出來。
 */
data class Money(val amount: BigDecimal, val currency: Currency)

/** 台幣金額。總損益、今日總損益只會是這個型別，加總不需要執行期檢查幣別。 */
@JvmInline
value class Twd(val amount: BigDecimal) {
    operator fun plus(other: Twd): Twd = TODO("not implemented")
    companion object { val ZERO = Twd(BigDecimal.ZERO) }
}

/**
 * 單價（原幣、每股／每點／每顆）。不變式：> 0。
 * 幣別不放在這裡：價格的幣別由商品決定（[Instrument.currency]），存兩份就會不一致。
 */
@JvmInline
value class Price private constructor(val value: BigDecimal) {
    companion object {
        /** 邊界用：表單、存檔、報價 JSON 進來時走這裡；內部一律信任 Price。 */
        fun of(value: BigDecimal): Price? = TODO("not implemented: value.signum() > 0 才回傳")
        fun parse(text: String): Price? = TODO("not implemented: 去千分位逗號 → BigDecimal → of()")
    }
}

/** 多空。空單損益反向，停損／目標的觸發方向也反向，全部從這一個值推導。 */
enum class Side(val sign: Int) { LONG(+1), SHORT(-1) }

// ---- 各市場的數量型別：單位不同就是不同型別，台股的「股」不可能被當成期貨的「口」 ----

/** 台股持有股數（一律存「股」）。不變式：> 0。張只是輸入與顯示單位，見 [TwQtyUnit]。 */
@JvmInline value class Shares(val count: Long)

/** 台股輸入時用的單位；只影響表單與顯示，損益計算只看 [Shares]。 */
enum class TwQtyUnit(val sharesPerUnit: Long) { LOT(1000), SHARE(1) }

/** 美股股數，可小數（零股、碎股）。不變式：> 0。 */
@JvmInline value class FractionalShares(val count: BigDecimal)

/** 期貨口數。不變式：> 0；方向由 [Side] 表示，不用負數口數。 */
@JvmInline value class Contracts(val count: Int)

/** 加密貨幣顆數。不變式：> 0 且小數最多 8 位（在解析邊界檢查）。 */
@JvmInline value class CoinAmount(val amount: BigDecimal)
