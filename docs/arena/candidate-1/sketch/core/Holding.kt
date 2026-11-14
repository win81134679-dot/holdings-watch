package holdings.core

import java.math.BigDecimal
import java.time.LocalDate

@JvmInline value class HoldingId(val value: String) // UUID 字串，手機建立時產生，手錶只讀

/**
 * 「持有什麼、多少」。商品與數量綁成一組，數量型別跟著市場走：
 * 台股不可能拿到口數、期貨不可能拿到小數口。
 */
sealed interface Asset {
    val instrument: Instrument

    /**
     * 曝險乘數：價格每動 1（原幣），這筆部位的損益動多少（原幣，未乘方向）。
     * 股票＝股數；期貨＝口數 × 每點價值；加密＝顆數。損益只需要這一個數字，
     * 所以估值不必對每個市場寫分支。
     */
    val exposure: BigDecimal
}

data class TwEquityAsset(override val instrument: TwEquity, val shares: Shares, val inputUnit: TwQtyUnit) : Asset {
    override val exposure: BigDecimal get() = TODO("not implemented: BigDecimal(shares.count)")
}

data class UsEquityAsset(override val instrument: UsEquity, val shares: FractionalShares) : Asset {
    override val exposure: BigDecimal get() = TODO("not implemented: shares.count")
}

data class FutureAsset(override val instrument: TwFuture, val contracts: Contracts) : Asset {
    override val exposure: BigDecimal get() = TODO("not implemented: instrument.pointValue × contracts.count")
}

data class CryptoAsset(override val instrument: Crypto, val amount: CoinAmount) : Asset {
    override val exposure: BigDecimal get() = TODO("not implemented: amount.amount")
}

/**
 * 一筆持股。使用者唯一會編輯的資料；只在手機上被寫。
 *
 * 不變式（在 [HoldingDraft.parse] 與存檔解碼兩個邊界檢查，內部信任）：
 * - 停損與目標都有時：多單 停損 < 目標；空單 停損 > 目標。
 * - entryDate 不晚於建立當天。
 *
 * 刻意沒有的欄位：幣別（由商品推）、每點價值（由期貨商品推）、結算日（由合約月份推）、
 * 提醒條件（由停損／目標／合約推，見 Alerts.kt）。存了就要同步，推導就不會錯。
 */
data class Holding(
    val id: HoldingId,
    val asset: Asset,
    val side: Side,
    val averageCost: Price,
    val stopLoss: Price?,
    val target: Price?,
    val entryDate: LocalDate,
    val note: String,
)

/** 停損／目標相對於方向是否合理。兩個邊界共用這一個判斷。 */
internal fun levelsConsistent(side: Side, stopLoss: Price?, target: Price?): Boolean =
    TODO("not implemented: 任一為 null → true；LONG → stop < target；SHORT → stop > target")

// ---------------------------------------------------------------------------------------------
// 表單邊界：手機的編輯畫面只產生 HoldingDraft（全是字串與選項），解析成 Holding 是純函式，可在 JVM 測。
// ---------------------------------------------------------------------------------------------

/** 表單上的商品選擇。期貨選商品＋月份；每點價值不在表單上，因為它是推導的。 */
sealed interface InstrumentChoice {
    data class TwStock(val codeText: String, val board: TwBoard, val unit: TwQtyUnit) : InstrumentChoice
    data class UsStock(val tickerText: String) : InstrumentChoice
    data class IndexFut(val product: IndexFuture, val month: ContractMonth) : InstrumentChoice
    data class StockFut(val product: StockFuture, val month: ContractMonth) : InstrumentChoice
    data class Coin(val symbolText: String) : InstrumentChoice
}

data class HoldingDraft(
    val instrument: InstrumentChoice,
    val quantityText: String,
    val side: Side = Side.LONG,
    val averageCostText: String,
    val stopLossText: String = "",
    val targetText: String = "",
    val entryDate: LocalDate,
    val note: String = "",
)

enum class DraftField { INSTRUMENT, QUANTITY, AVERAGE_COST, STOP_LOSS, TARGET, ENTRY_DATE }

enum class DraftProblem {
    REQUIRED, NOT_A_NUMBER, NOT_POSITIVE, TOO_MANY_DECIMALS, BAD_SYMBOL,
    STOP_WRONG_SIDE_OF_TARGET, FUTURE_DATE, CONTRACT_EXPIRED,
}

sealed interface DraftResult {
    data class Ok(val holding: Holding) : DraftResult
    /** 每個欄位最多一個問題，表單直接在該欄下方顯示。 */
    data class Invalid(val problems: Map<DraftField, DraftProblem>) : DraftResult
}

/**
 * 表單 → 持股。新增時 id 由呼叫端給新的 UUID；編輯時沿用舊 id（id 不變，提醒狀態的鍵才延續）。
 *
 * TODO 虛擬碼：
 *   instrument + 數量:
 *     TwStock → TwCode.parse；數量必須整數；shares = n × unit.sharesPerUnit
 *     UsStock → UsTicker.parse；數量 BigDecimal > 0
 *     IndexFut/StockFut → TwFuture(product, month)；month.lastTradingDay() < today → CONTRACT_EXPIRED；口數正整數
 *     Coin → CoinSymbol.parse；數量 scale ≤ 8 否則 TOO_MANY_DECIMALS
 *   成本 Price.parse 必填；停損／目標選填，空字串 → null
 *   levelsConsistent(side, stop, target) 否則 STOP_WRONG_SIDE_OF_TARGET（掛在 STOP_LOSS 欄）
 *   entryDate > today → FUTURE_DATE
 */
fun HoldingDraft.parse(id: HoldingId, today: LocalDate): DraftResult = TODO("not implemented")

/** 編輯畫面開啟時把既有持股還原成表單（張／股依 inputUnit 還原）。 */
fun Holding.toDraft(): HoldingDraft = TODO("not implemented")
