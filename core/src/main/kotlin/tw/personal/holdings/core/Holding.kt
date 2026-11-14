package tw.personal.holdings.core

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@JvmInline
value class HoldingId(val value: String)

/** 商品與數量綁成一組，數量型別跟著市場走：台股拿不到口數、期貨拿不到小數口。 */
sealed interface Asset {
    val instrument: Instrument

    /** 價格每動 1（原幣），這筆部位損益動多少（未乘方向）。估值只靠這一個數字，不必對每個市場寫分支。 */
    val exposure: BigDecimal
}

data class TwEquityAsset(override val instrument: TwEquity, val shares: Shares, val inputUnit: TwQtyUnit) : Asset {
    override val exposure: BigDecimal get() = BigDecimal(shares.count)
}

data class UsEquityAsset(override val instrument: UsEquity, val shares: FractionalShares) : Asset {
    override val exposure: BigDecimal get() = shares.count
}

data class FutureAsset(override val instrument: TwFuture, val contracts: Contracts) : Asset {
    override val exposure: BigDecimal get() = instrument.pointValue * BigDecimal(contracts.count)
}

data class CryptoAsset(override val instrument: Crypto, val amount: CoinAmount) : Asset {
    override val exposure: BigDecimal get() = amount.amount
}

enum class ExitStatus { CLEAR, STOP_HIT, TARGET_HIT }

/** 停損與目標。不變式：兩者都有時，多單 停損 < 目標、空單 停損 > 目標。 */
@ConsistentCopyVisibility
data class ExitLevels private constructor(val stopLoss: Price?, val target: Price?) {

    /** 以目前價格看是否已在停損或目標之外；手錶的紅綠點用它，通知漏掉時看板上仍看得到。 */
    fun status(side: Side, price: Price): ExitStatus = when {
        stopLoss != null && crossed(side.stopDirection, price.value, price.value, stopLoss) -> ExitStatus.STOP_HIT
        target != null && crossed(side.targetDirection, price.value, price.value, target) -> ExitStatus.TARGET_HIT
        else -> ExitStatus.CLEAR
    }

    companion object {
        internal fun of(side: Side, stopLoss: Price?, target: Price?): ExitLevels? {
            if (stopLoss != null && target != null) {
                val ordered = when (side) {
                    Side.LONG -> stopLoss.value < target.value
                    Side.SHORT -> stopLoss.value > target.value
                }
                if (!ordered) return null
            }
            return ExitLevels(stopLoss, target)
        }
    }
}

/** 價格往哪個方向碰線算觸發。 */
internal enum class Direction { BELOW, ABOVE }

internal val Side.stopDirection: Direction get() = if (this == Side.LONG) Direction.BELOW else Direction.ABOVE
internal val Side.targetDirection: Direction get() = if (this == Side.LONG) Direction.ABOVE else Direction.BELOW

internal fun crossed(direction: Direction, low: BigDecimal, high: BigDecimal, level: Price): Boolean = when (direction) {
    Direction.BELOW -> low <= level.value
    Direction.ABOVE -> high >= level.value
}

/**
 * 一筆持股，使用者唯一會編輯的資料。只能經 [HoldingDraft.parse] 或存檔解碼建立。
 * 刻意沒有幣別、每點價值、結算日、提醒條件：都由別的欄位推導。
 */
@ConsistentCopyVisibility
data class Holding private constructor(
    val id: HoldingId,
    /** 使用者自填的顯示名稱；沒填就顯示代號。 */
    val name: String?,
    val asset: Asset,
    val side: Side,
    val averageCost: Price,
    val exits: ExitLevels,
    val entryDate: LocalDate,
    val note: String,
) {
    val stopLoss: Price? get() = exits.stopLoss
    val target: Price? get() = exits.target

    fun isExpired(now: Instant): Boolean = (asset.instrument as? TwFuture)?.isExpired(now) ?: false

    companion object {
        internal fun create(
            id: HoldingId,
            name: String?,
            asset: Asset,
            side: Side,
            averageCost: Price,
            stopLoss: Price?,
            target: Price?,
            entryDate: LocalDate,
            note: String,
        ): Holding? {
            if (id.value.isBlank()) return null
            val exits = ExitLevels.of(side, stopLoss, target) ?: return null
            return Holding(id, name?.trim()?.ifEmpty { null }, asset, side, averageCost, exits, entryDate, note)
        }
    }
}

// ---- 表單邊界：編輯畫面只產生字串與選項，解析成 Holding 是純函式 ----

sealed interface InstrumentChoice {
    data class TwStock(val codeText: String, val board: TwBoard, val unit: TwQtyUnit) : InstrumentChoice
    data class UsStock(val tickerText: String) : InstrumentChoice
    data class IndexFut(val product: IndexFuture, val month: ContractMonth) : InstrumentChoice
    data class StockFut(
        val taifexCodeText: String,
        val underlyingText: String,
        val size: StockFutureSize,
        val month: ContractMonth,
    ) : InstrumentChoice
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
    val name: String = "",
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

/** 新增時 id 由呼叫端給新的 UUID；編輯時沿用舊 id，提醒狀態的鍵才會延續。 */
fun HoldingDraft.parse(id: HoldingId, today: LocalDate): DraftResult {
    val p = Problems()
    val asset = parseAsset(instrument, quantityText, today, p)
    val cost = p.take(DraftField.AVERAGE_COST, price(averageCostText))
    val stop = p.take(DraftField.STOP_LOSS, optionalPrice(stopLossText))
    val target = p.take(DraftField.TARGET, optionalPrice(targetText))
    val levelsReadable = DraftField.STOP_LOSS !in p && DraftField.TARGET !in p
    if (levelsReadable && ExitLevels.of(side, stop, target) == null) {
        p.bad(DraftField.STOP_LOSS, DraftProblem.STOP_WRONG_SIDE_OF_TARGET)
    }
    if (entryDate > today) p.bad(DraftField.ENTRY_DATE, DraftProblem.FUTURE_DATE)

    if (asset == null || cost == null || p.any()) return DraftResult.Invalid(p.map)
    val holding = Holding.create(id, name, asset, side, cost, stop, target, entryDate, note)
        ?: return DraftResult.Invalid(mapOf(DraftField.STOP_LOSS to DraftProblem.STOP_WRONG_SIDE_OF_TARGET))
    return DraftResult.Ok(holding)
}

/** 編輯畫面開啟時把既有持股還原成表單（張／股依 inputUnit 還原）。 */
fun Holding.toDraft(): HoldingDraft {
    val (choice, quantity) = when (val a = asset) {
        is TwEquityAsset -> InstrumentChoice.TwStock(a.instrument.code.value, a.instrument.board, a.inputUnit) to
            plain(BigDecimal(a.shares.count).divide(BigDecimal(a.inputUnit.sharesPerUnit)))
        is UsEquityAsset -> InstrumentChoice.UsStock(a.instrument.ticker.value) to plain(a.shares.count)
        is CryptoAsset -> InstrumentChoice.Coin(a.instrument.coin.value) to plain(a.amount.amount)
        is FutureAsset -> {
            val month = a.instrument.month
            val c = when (val product = a.instrument.product) {
                is IndexFuture -> InstrumentChoice.IndexFut(product, month)
                is StockFuture -> InstrumentChoice.StockFut(product.taifexCode, product.underlying.value, product.size, month)
            }
            c to a.contracts.count.toString()
        }
    }
    return HoldingDraft(
        instrument = choice,
        quantityText = quantity,
        side = side,
        averageCostText = plain(averageCost.value),
        stopLossText = stopLoss?.let { plain(it.value) } ?: "",
        targetText = target?.let { plain(it.value) } ?: "",
        entryDate = entryDate,
        name = name ?: "",
        note = note,
    )
}

private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

private sealed interface Check<out T> {
    data class Ok<T>(val value: T) : Check<T>
    data class Bad(val problem: DraftProblem) : Check<Nothing>
}

private class Problems {
    val map = linkedMapOf<DraftField, DraftProblem>()

    fun <T> take(field: DraftField, check: Check<T>): T? = when (check) {
        is Check.Ok -> check.value
        is Check.Bad -> bad(field, check.problem)
    }

    fun bad(field: DraftField, problem: DraftProblem): Nothing? {
        map.putIfAbsent(field, problem)
        return null
    }

    fun any(): Boolean = map.isNotEmpty()
    operator fun contains(field: DraftField): Boolean = field in map
}

private fun decimal(text: String): Check<BigDecimal> {
    val t = text.trim().replace(",", "")
    if (t.isEmpty()) return Check.Bad(DraftProblem.REQUIRED)
    val d = t.toBigDecimalOrNull() ?: return Check.Bad(DraftProblem.NOT_A_NUMBER)
    return if (d.signum() > 0) Check.Ok(d) else Check.Bad(DraftProblem.NOT_POSITIVE)
}

private fun price(text: String): Check<Price> = when (val d = decimal(text)) {
    is Check.Ok -> Price.of(d.value)?.let { Check.Ok(it) } ?: Check.Bad(DraftProblem.NOT_POSITIVE)
    is Check.Bad -> d
}

private fun optionalPrice(text: String): Check<Price?> = if (text.isBlank()) Check.Ok(null) else price(text)

private fun whole(d: BigDecimal): Check<Long> =
    runCatching { d.longValueExact() }.fold({ Check.Ok(it) }, { Check.Bad(DraftProblem.TOO_MANY_DECIMALS) })

private fun parseAsset(choice: InstrumentChoice, quantityText: String, today: LocalDate, p: Problems): Asset? {
    val qty = p.take(DraftField.QUANTITY, decimal(quantityText))
    return when (choice) {
        is InstrumentChoice.TwStock -> {
            val code = TwCode.parse(choice.codeText) ?: p.bad(DraftField.INSTRUMENT, DraftProblem.BAD_SYMBOL)
            val shares = qty?.let { p.take(DraftField.QUANTITY, whole(it * BigDecimal(choice.unit.sharesPerUnit))) }
                ?.let(Shares::of)
            if (code != null && shares != null) TwEquityAsset(TwEquity(code, choice.board), shares, choice.unit) else null
        }
        is InstrumentChoice.UsStock -> {
            val ticker = UsTicker.parse(choice.tickerText) ?: p.bad(DraftField.INSTRUMENT, DraftProblem.BAD_SYMBOL)
            val shares = qty?.let(FractionalShares::of)
            if (ticker != null && shares != null) UsEquityAsset(UsEquity(ticker), shares) else null
        }
        is InstrumentChoice.Coin -> {
            val coin = CoinSymbol.parse(choice.symbolText) ?: p.bad(DraftField.INSTRUMENT, DraftProblem.BAD_SYMBOL)
            val amount = qty?.let { CoinAmount.of(it) ?: p.bad(DraftField.QUANTITY, DraftProblem.TOO_MANY_DECIMALS) }
            if (coin != null && amount != null) CryptoAsset(Crypto(coin), amount) else null
        }
        is InstrumentChoice.IndexFut -> future(choice.product, choice.month, qty, today, p)
        is InstrumentChoice.StockFut -> {
            val product = TwCode.parse(choice.underlyingText)
                ?.let { StockFuture.of(choice.taifexCodeText, it, choice.size) }
                ?: p.bad(DraftField.INSTRUMENT, DraftProblem.BAD_SYMBOL)
            future(product, choice.month, qty, today, p)
        }
    }
}

private fun future(product: FutureProduct?, month: ContractMonth, qty: BigDecimal?, today: LocalDate, p: Problems): FutureAsset? {
    if (month.lastTradingDay() < today) p.bad(DraftField.INSTRUMENT, DraftProblem.CONTRACT_EXPIRED)
    val contracts = qty?.let { p.take(DraftField.QUANTITY, whole(it)) }?.let { n ->
        if (n > Int.MAX_VALUE) p.bad(DraftField.QUANTITY, DraftProblem.NOT_A_NUMBER) else Contracts.of(n.toInt())
    }
    if (product == null || contracts == null || DraftField.INSTRUMENT in p) return null
    return FutureAsset(TwFuture(product, month), contracts)
}
