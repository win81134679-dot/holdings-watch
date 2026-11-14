package tw.personal.holdings.phone

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tw.personal.holdings.phone.symbols.Pick
import java.math.BigDecimal
import tw.personal.holdings.core.ContractMonth
import tw.personal.holdings.core.DraftField
import tw.personal.holdings.core.DraftProblem
import tw.personal.holdings.core.FutureProduct
import tw.personal.holdings.core.HoldingDraft
import tw.personal.holdings.core.IndexFuture
import tw.personal.holdings.core.InstrumentChoice
import tw.personal.holdings.core.Side
import tw.personal.holdings.core.StockFuture
import tw.personal.holdings.core.StockFutureSize
import tw.personal.holdings.core.TwBoard
import tw.personal.holdings.core.TwCode
import tw.personal.holdings.core.TwQtyUnit
import java.time.LocalDate

enum class MarketTab(val label: String) { TW("台股"), US("美股"), FUTURES("期貨"), CRYPTO("加密") }

enum class FutureKind(val label: String, val index: IndexFuture?) {
    TX("台指期 TX", IndexFuture.TX),
    MTX("小台 MTX", IndexFuture.MTX),
    TMF("微台 TMF", IndexFuture.TMF),
    STOCK("個股期貨", null),
}

/**
 * 編輯畫面的原始輸入，全是字串與選項。商品由搜尋選定（[pick]），每個市場的代號欄各自保留。
 * 解析與驗證只在 [toDraft] → `HoldingDraft.parse` 做一次。
 */
@Serializable
data class EditForm(
    val market: MarketTab = MarketTab.TW,
    val twCode: String = "",
    val twBoard: TwBoard = TwBoard.TWSE,
    val twUnit: TwQtyUnit = TwQtyUnit.LOT,
    val usTicker: String = "",
    val futureKind: FutureKind = FutureKind.TX,
    val stockFutureCode: String = "",
    val stockFutureUnderlying: String = "",
    val stockFutureSize: StockFutureSize = StockFutureSize.STANDARD,
    /** 空字串＝還沒選，用掛牌的第一個（近月）。 */
    val month: String = "",
    val coin: String = "",
    val quantity: String = "",
    val side: Side = Side.LONG,
    val averageCost: String = "",
    val stopLoss: String = "",
    val target: String = "",
    val entryDate: String,
    val name: String = "",
    val note: String = "",
    /** 新增時先搜尋商品；選定之後（或編輯既有持股）才顯示數量與成本。 */
    val picked: Boolean = false,
) {
    /** 跟 [Pick.key] 同一套格式，存「最近用過」也用它。 */
    fun key(): String = when (market) {
        MarketTab.TW -> "TW:${twCode.trim().uppercase()}"
        MarketTab.US -> "US:${usTicker.trim().uppercase()}"
        MarketTab.CRYPTO -> "C:${coin.trim().uppercase()}"
        MarketTab.FUTURES -> futureKind.index?.let { "F:${it.name}" }
            ?: "SF:${stockFutureUnderlying.trim().uppercase()}:${stockFutureCode.trim().uppercase()}"
    }

    /**
     * 選定一個商品：帶入市場、代號、上市櫃與名稱。名稱永遠可以再改；
     * 只有重新選到同一個商品時才保留使用者改過的名稱，換成別的商品就換成新商品的名稱。
     */
    fun pick(p: Pick): EditForm {
        val same = picked && sameInstrument(key(), p.key)
        val base = copy(picked = true, name = if (same && name.isNotBlank()) name else p.autoName)
        return when (p) {
            is Pick.TwStock -> base.copy(market = MarketTab.TW, twCode = p.code, twBoard = p.board)
            is Pick.UsStock -> base.copy(market = MarketTab.US, usTicker = p.code)
            is Pick.Coin -> base.copy(market = MarketTab.CRYPTO, coin = p.code)
            is Pick.IndexFut -> base.copy(
                market = MarketTab.FUTURES,
                futureKind = FutureKind.entries.first { it.index == p.product },
                month = if (same) month else "",
            )
            is Pick.StockFut -> base.copy(
                market = MarketTab.FUTURES,
                futureKind = FutureKind.STOCK,
                stockFutureUnderlying = p.underlying.code,
                stockFutureCode = p.taifexCode.ifBlank { if (same) stockFutureCode else "" },
                month = if (same) month else "",
            )
        }
    }

    /** 存檔前的一行預覽，例如「台積電 1 張 × 1,085 ＝ 1,085,000」；數量或成本還不是正數時回 null。 */
    fun preview(today: LocalDate): String? {
        val qty = positive(quantity) ?: return null
        val cost = positive(averageCost) ?: return null
        val q = grouped(qty, 8)
        val c = grouped(cost, 8)
        val text = when (market) {
            MarketTab.TW -> {
                val perUnit = if (twUnit == TwQtyUnit.LOT) 1000 else 1
                val unit = if (twUnit == TwQtyUnit.LOT) "張" else "股"
                "${name.ifBlank { twCode }} $q $unit × $c ＝ ${grouped(qty * cost * BigDecimal(perUnit), 2)}"
            }
            MarketTab.US -> "${name.ifBlank { usTicker }} $q 股 × $c ＝ ${grouped(qty * cost, 2)} USD"
            MarketTab.CRYPTO -> "${name.ifBlank { coin }} $q ${coin.trim().uppercase()} × $c ＝ ${grouped(qty * cost, 2)} USD"
            MarketTab.FUTURES -> {
                val what = futureKind.index?.let { futureName(it) } ?: "${name.ifBlank { stockFutureUnderlying }}期貨"
                "$what ${selectedMonth(today)} $q 口 × $c ＝ 名目 ${grouped(qty * cost * BigDecimal(pointValueTwd()), 2)}"
            }
        }
        return if (side == Side.SHORT) "空 $text" else text
    }

    fun toDraft(today: LocalDate): HoldingDraft {
        val choice = when (market) {
            MarketTab.TW -> InstrumentChoice.TwStock(twCode, twBoard, twUnit)
            MarketTab.US -> InstrumentChoice.UsStock(usTicker)
            MarketTab.CRYPTO -> InstrumentChoice.Coin(coin)
            MarketTab.FUTURES -> {
                val m = selectedMonth(today)
                futureKind.index?.let { InstrumentChoice.IndexFut(it, m) }
                    ?: InstrumentChoice.StockFut(stockFutureCode, stockFutureUnderlying, stockFutureSize, m)
            }
        }
        return HoldingDraft(
            instrument = choice,
            quantityText = quantity,
            side = side,
            averageCostText = averageCost,
            stopLossText = stopLoss,
            targetText = target,
            entryDate = LocalDate.parse(entryDate),
            name = name,
            note = note,
        )
    }

    fun selectedMonth(today: LocalDate): ContractMonth =
        ContractMonth.parse(month) ?: ContractMonth.listed(listingProduct(), today).first()

    /** 下拉選單：今天掛牌的月份；編輯舊持股時，原本的月份就算已不在清單上也要留著。 */
    fun monthOptions(today: LocalDate): List<ContractMonth> {
        val listed = ContractMonth.listed(listingProduct(), today)
        val current = ContractMonth.parse(month)
        return if (current == null || current in listed) listed else (listed + current).sorted()
    }

    /** 每點價值（元），唯讀顯示。 */
    fun pointValueTwd(): Int = futureKind.index?.pointValueTwd?.toInt() ?: stockFutureSize.shares

    /**
     * 掛牌規則只看「是不是指數期貨」，個股期貨的代號還沒打完時就用一個佔位商品算月份。
     */
    private fun listingProduct(): FutureProduct = futureKind.index
        ?: StockFuture.of("XX", TwCode.parse("0000")!!, stockFutureSize)!!

    companion object {
        fun blank(today: LocalDate) = EditForm(entryDate = today.toString())

        private val json = Json { ignoreUnknownKeys = true }
        fun encode(form: EditForm): String = json.encodeToString(serializer(), form)
        fun decode(text: String): EditForm = json.decodeFromString(serializer(), text)
    }
}

private fun positive(text: String): BigDecimal? =
    text.trim().replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

/** 「SF:2330:CDF」與「SF:2330:」是同一個標的，期交所代號是使用者後填的。 */
private fun sameInstrument(a: String, b: String): Boolean = a.split(":").take(2) == b.split(":").take(2)

fun HoldingDraft.toForm(): EditForm {
    val base = EditForm(
        picked = true,
        quantity = quantityText,
        side = side,
        averageCost = averageCostText,
        stopLoss = stopLossText,
        target = targetText,
        entryDate = entryDate.toString(),
        name = name,
        note = note,
    )
    return when (val c = instrument) {
        is InstrumentChoice.TwStock -> base.copy(market = MarketTab.TW, twCode = c.codeText, twBoard = c.board, twUnit = c.unit)
        is InstrumentChoice.UsStock -> base.copy(market = MarketTab.US, usTicker = c.tickerText)
        is InstrumentChoice.Coin -> base.copy(market = MarketTab.CRYPTO, coin = c.symbolText)
        is InstrumentChoice.IndexFut -> base.copy(
            market = MarketTab.FUTURES,
            futureKind = FutureKind.entries.first { it.index == c.product },
            month = c.month.toString(),
        )
        is InstrumentChoice.StockFut -> base.copy(
            market = MarketTab.FUTURES,
            futureKind = FutureKind.STOCK,
            stockFutureCode = c.taifexCodeText,
            stockFutureUnderlying = c.underlyingText,
            stockFutureSize = c.size,
            month = c.month.toString(),
        )
    }
}

fun problemText(field: DraftField, problem: DraftProblem, form: EditForm): String = when (problem) {
    DraftProblem.REQUIRED -> "必填"
    DraftProblem.NOT_A_NUMBER -> "請填數字"
    DraftProblem.NOT_POSITIVE -> "要大於 0"
    DraftProblem.TOO_MANY_DECIMALS -> when {
        field != DraftField.QUANTITY -> "小數位太多"
        form.market == MarketTab.CRYPTO -> "最多 8 位小數"
        form.market == MarketTab.FUTURES -> "口數要是整數"
        form.market == MarketTab.TW -> if (form.twUnit == TwQtyUnit.LOT) "換算成股數要是整數（1 張＝1000 股）" else "股數要是整數"
        else -> "小數位太多"
    }
    DraftProblem.BAD_SYMBOL -> when (form.market) {
        MarketTab.TW -> "台股代號是 4 碼數字（可再加 1–2 碼）"
        MarketTab.US -> "美股代號是英文字母，例如 AAPL"
        MarketTab.CRYPTO -> "幣別是 2–10 個英數字，例如 BTC"
        MarketTab.FUTURES -> "期交所代號 2–4 碼英數字，標的是台股代號"
    }
    DraftProblem.STOP_WRONG_SIDE_OF_TARGET ->
        if (form.side == Side.LONG) "多單的停損要低於目標" else "空單的停損要高於目標"
    DraftProblem.FUTURE_DATE -> "進場日不能在今天之後"
    DraftProblem.CONTRACT_EXPIRED -> "這個合約已過最後交易日"
}
