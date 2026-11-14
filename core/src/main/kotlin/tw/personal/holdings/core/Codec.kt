package tw.personal.holdings.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Refresher 的私有狀態：報價快取＋提醒狀態。兩者只由 Refresher 寫，放同一個檔、一次原子寫入。 */
data class RefreshState(
    val market: MarketData,
    val alerts: Map<AlertKey, AlertState>,
) {
    companion object {
        val EMPTY = RefreshState(MarketData.EMPTY, emptyMap())
    }
}

sealed interface Decoded<out T> {
    data class Ok<T>(val value: T) : Decoded<T>

    /** 格式版本比這支 App 新（手機先更新、手錶還沒）。手錶顯示「請更新手錶 App」。 */
    data class TooNew(val version: Int) : Decoded<Nothing>
    data class Corrupt(val detail: String) : Decoded<Nothing>
}

/** 手機→手錶的 Data Layer 合約。路徑字串不會有第二份。 */
object SyncContract {
    const val SNAPSHOT_PATH = "/holdings/snapshot"

    /** 存檔與同步封包共用。不相容改動才加 1；只加選填欄位不用加。 */
    const val VERSION = 1
}

/** 所有離開記憶體的格式集中在這裡。解碼就是邊界：任何一欄不合法整份回 Corrupt，不丟例外。 */
object PortfolioCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val holdingList = ListSerializer(HoldingDto.serializer())

    fun encodeHoldings(holdings: List<Holding>): ByteArray = encode(holdingList, holdings.map(::holdingDto))

    fun decodeHoldings(bytes: ByteArray): Decoded<List<Holding>> =
        decode(bytes, holdingList) { dtos -> dtos.map { it.toDomain() } }

    fun encodeRefreshState(state: RefreshState): ByteArray = encode(
        RefreshStateDto.serializer(),
        RefreshStateDto(
            entries = state.market.entries
                .map { (x, e) -> EntryDto(x.storageKey(), e.quote?.let(::quoteDto), e.fetchedAt?.toString(), e.failure?.let(::failureDto)) }
                .sortedBy { it.key },
            alerts = state.alerts.map { (k, s) -> alertDto(k, s) }.sortedBy { it.tag },
        ),
    )

    fun decodeRefreshState(bytes: ByteArray): Decoded<RefreshState> = decode(bytes, RefreshStateDto.serializer()) { dto ->
        RefreshState(
            market = MarketData(
                dto.entries.associate { e ->
                    quotable(e.key) to QuoteEntry(e.quote?.toDomain(), e.fetchedAt?.let(::instant), e.failure?.toDomain())
                },
            ),
            alerts = dto.alerts.associate { it.toDomain() },
        )
    }

    /** 刻意不含發布時間：內容沒變時位元組一樣，Data Layer 不會觸發變更（重送是冪等的）。 */
    fun encodeSnapshot(snapshot: PortfolioSnapshot): ByteArray = encode(
        SnapshotDto.serializer(),
        SnapshotDto(
            holdings = snapshot.holdings.map(::holdingDto),
            quotes = snapshot.quotes.map { (x, q) -> KeyedQuoteDto(x.storageKey(), quoteDto(q)) }.sortedBy { it.key },
        ),
    )

    fun decodeSnapshot(bytes: ByteArray): Decoded<PortfolioSnapshot> = decode(bytes, SnapshotDto.serializer()) { dto ->
        PortfolioSnapshot(
            holdings = dto.holdings.map { it.toDomain() },
            quotes = dto.quotes.associate { quotable(it.key) to it.quote.toDomain() },
        )
    }

    private fun <D> encode(serializer: KSerializer<D>, body: D): ByteArray {
        val root = buildJsonObject {
            put("version", SyncContract.VERSION)
            put("body", json.encodeToJsonElement(serializer, body))
        }
        return json.encodeToString(JsonElement.serializer(), root).toByteArray(Charsets.UTF_8)
    }

    private fun <D, T> decode(bytes: ByteArray, serializer: KSerializer<D>, toDomain: (D) -> T): Decoded<T> {
        return try {
            val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            val version = root["version"]?.jsonPrimitive?.intOrNull ?: return Decoded.Corrupt("沒有版本欄位")
            if (version > SyncContract.VERSION) return Decoded.TooNew(version)
            if (version < 1) return Decoded.Corrupt("版本 $version")
            val body = root["body"] ?: return Decoded.Corrupt("沒有 body")
            Decoded.Ok(toDomain(json.decodeFromJsonElement(serializer, body)))
        } catch (e: Exception) {
            // 這裡是「不丟例外」合約的唯一出口：JSON 語法、欄位型別、領域不變式的錯誤都收成 Corrupt。
            Decoded.Corrupt(e.message ?: e.javaClass.simpleName)
        }
    }
}

private class CorruptData(what: String) : Exception(what)

private fun <T : Any> T?.orCorrupt(what: String): T = this ?: throw CorruptData(what)

private inline fun <reified E : Enum<E>> enumOf(name: String?): E =
    enumValues<E>().firstOrNull { it.name == name }.orCorrupt("${E::class.simpleName}: $name")

private fun price(text: String): Price = text.toBigDecimalOrNull()?.let(Price::of).orCorrupt("價格: $text")

private fun decimal(text: String): BigDecimal = text.toBigDecimalOrNull().orCorrupt("數量: $text")

/** ISO 字串保留奈秒；截成毫秒的話，同一筆報價重開後會被當成比 judgedAsOf 新而再判斷一次。 */
private fun instant(text: String): Instant = Instant.parse(text)

private fun Price.text(): String = value.toPlainString()

// ---- Quotable ↔ 穩定字串鍵（存檔與同步共用，跟 Yahoo 代號無關） ----

internal fun Quotable.storageKey(): String = when (this) {
    is TwEquity -> "TW_EQUITY:${board.name}:${code.value}"
    is UsEquity -> "US_EQUITY:${ticker.value}"
    is Crypto -> "CRYPTO:${coin.value}"
    is TwFuture -> when (val p = product) {
        is IndexFuture -> "TW_FUTURES:${p.name}:$month"
        is StockFuture -> "TW_FUTURES:STOCK:${p.taifexCode}:${p.underlying.value}:${p.size.name}:$month"
    }
    is FxPair -> "FX:$name"
}

private fun quotable(key: String): Quotable {
    val p = key.split(":")
    fun month(i: Int) = ContractMonth.parse(p[i]).orCorrupt("月份: $key")
    return when {
        p[0] == "TW_EQUITY" && p.size == 3 -> TwEquity(TwCode.parse(p[2]).orCorrupt(key), enumOf(p[1]))
        p[0] == "US_EQUITY" && p.size == 2 -> UsEquity(UsTicker.parse(p[1]).orCorrupt(key))
        p[0] == "CRYPTO" && p.size == 2 -> Crypto(CoinSymbol.parse(p[1]).orCorrupt(key))
        p[0] == "TW_FUTURES" && p.size == 3 -> TwFuture(enumOf<IndexFuture>(p[1]), month(2))
        p[0] == "TW_FUTURES" && p.size == 6 && p[1] == "STOCK" -> TwFuture(
            StockFuture.of(p[2], TwCode.parse(p[3]).orCorrupt(key), enumOf(p[4])).orCorrupt(key),
            month(5),
        )
        p[0] == "FX" && p.size == 2 -> enumOf<FxPair>(p[1])
        else -> throw CorruptData("商品鍵: $key")
    }
}

// ---- DTO：數值一律以字串存，避免浮點 ----

@Serializable
internal data class HoldingDto(
    val id: String,
    val name: String? = null,
    val market: String,
    /** 台股代號、美股代號、幣種；期貨為期交所商品代碼。 */
    val symbol: String,
    val twBoard: String? = null,
    val twUnit: String? = null,
    val futureMonth: String? = null,
    val stockFutureUnderlying: String? = null,
    val stockFutureSize: String? = null,
    /** 台股一律是股數。 */
    val quantity: String,
    val side: String,
    val averageCost: String,
    val stopLoss: String? = null,
    val target: String? = null,
    val entryDate: String,
    val note: String = "",
) {
    fun toDomain(): Holding {
        val asset: Asset = when (enumOf<Market>(market)) {
            Market.TW_EQUITY -> TwEquityAsset(
                TwEquity(TwCode.parse(symbol).orCorrupt(symbol), enumOf(twBoard)),
                Shares.of(quantity.toLongOrNull().orCorrupt(quantity)).orCorrupt(quantity),
                enumOf(twUnit),
            )
            Market.US_EQUITY -> UsEquityAsset(
                UsEquity(UsTicker.parse(symbol).orCorrupt(symbol)),
                FractionalShares.of(decimal(quantity)).orCorrupt(quantity),
            )
            Market.CRYPTO -> CryptoAsset(
                Crypto(CoinSymbol.parse(symbol).orCorrupt(symbol)),
                CoinAmount.of(decimal(quantity)).orCorrupt(quantity),
            )
            Market.TW_FUTURES -> {
                val product: FutureProduct = if (stockFutureUnderlying == null) {
                    enumOf<IndexFuture>(symbol)
                } else {
                    val underlying = TwCode.parse(stockFutureUnderlying).orCorrupt(stockFutureUnderlying)
                    StockFuture.of(symbol, underlying, enumOf(stockFutureSize)).orCorrupt(symbol)
                }
                FutureAsset(
                    TwFuture(product, ContractMonth.parse(futureMonth.orCorrupt("月份")).orCorrupt("月份: $futureMonth")),
                    Contracts.of(quantity.toIntOrNull().orCorrupt(quantity)).orCorrupt(quantity),
                )
            }
            Market.FX -> throw CorruptData("匯率不是持股")
        }
        return Holding.create(
            id = HoldingId(id),
            name = name,
            asset = asset,
            side = enumOf(side),
            averageCost = price(averageCost),
            stopLoss = stopLoss?.let(::price),
            target = target?.let(::price),
            entryDate = LocalDate.parse(entryDate),
            note = note,
        ).orCorrupt("持股 $id")
    }
}

private fun holdingDto(h: Holding): HoldingDto {
    val base = HoldingDto(
        id = h.id.value,
        name = h.name,
        market = h.asset.instrument.market.name,
        symbol = "",
        quantity = "",
        side = h.side.name,
        averageCost = h.averageCost.text(),
        stopLoss = h.stopLoss?.text(),
        target = h.target?.text(),
        entryDate = h.entryDate.toString(),
        note = h.note,
    )
    return when (val a = h.asset) {
        is TwEquityAsset -> base.copy(
            symbol = a.instrument.code.value,
            twBoard = a.instrument.board.name,
            twUnit = a.inputUnit.name,
            quantity = a.shares.count.toString(),
        )
        is UsEquityAsset -> base.copy(symbol = a.instrument.ticker.value, quantity = a.shares.count.toPlainString())
        is CryptoAsset -> base.copy(symbol = a.instrument.coin.value, quantity = a.amount.amount.toPlainString())
        is FutureAsset -> when (val p = a.instrument.product) {
            is IndexFuture -> base
            is StockFuture -> base.copy(stockFutureUnderlying = p.underlying.value, stockFutureSize = p.size.name)
        }.copy(
            symbol = a.instrument.product.taifexCode,
            futureMonth = a.instrument.month.toString(),
            quantity = a.contracts.count.toString(),
        )
    }
}

@Serializable
internal data class QuoteDto(
    val last: String,
    val previousClose: String,
    val asOf: String,
    val freshness: String,
    val low: String? = null,
    val high: String? = null,
) {
    fun toDomain() = Quote(
        last = price(last),
        previousClose = price(previousClose),
        asOf = instant(asOf),
        freshness = enumOf(freshness),
        low = low?.let(::price),
        high = high?.let(::price),
    )
}

private fun quoteDto(q: Quote) = QuoteDto(
    last = q.last.text(),
    previousClose = q.previousClose.text(),
    asOf = q.asOf.toString(),
    freshness = q.freshness.name,
    low = q.low?.text(),
    high = q.high?.text(),
)

@Serializable
internal data class KeyedQuoteDto(val key: String, val quote: QuoteDto)

@Serializable
internal data class SnapshotDto(val holdings: List<HoldingDto>, val quotes: List<KeyedQuoteDto>)

private enum class FailureKind { NOT_FOUND, THROTTLED, NETWORK, UNPARSEABLE, UNSUPPORTED }

@Serializable
internal data class FailureDto(
    val at: String,
    val kind: String,
    val consecutive: Int,
    val retryAfterSeconds: Long? = null,
    val detail: String? = null,
) {
    fun toDomain(): QuoteEntry.Failure {
        val reason = when (enumOf<FailureKind>(kind)) {
            FailureKind.NOT_FOUND -> FetchFailure.NotFound
            FailureKind.THROTTLED -> FetchFailure.Throttled(retryAfterSeconds?.let(Duration::ofSeconds))
            FailureKind.NETWORK -> FetchFailure.Network
            FailureKind.UNPARSEABLE -> FetchFailure.Unparseable(detail ?: "")
            FailureKind.UNSUPPORTED -> FetchFailure.Unsupported
        }
        return QuoteEntry.Failure(instant(at), reason, consecutive)
    }
}

private fun failureDto(f: QuoteEntry.Failure): FailureDto {
    val (kind, retryAfter, detail) = when (val r = f.reason) {
        FetchFailure.NotFound -> Triple(FailureKind.NOT_FOUND, null, null)
        is FetchFailure.Throttled -> Triple(FailureKind.THROTTLED, r.retryAfter?.seconds, null)
        FetchFailure.Network -> Triple(FailureKind.NETWORK, null, null)
        is FetchFailure.Unparseable -> Triple(FailureKind.UNPARSEABLE, null, r.detail)
        FetchFailure.Unsupported -> Triple(FailureKind.UNSUPPORTED, null, null)
    }
    return FailureDto(f.at.toString(), kind.name, f.consecutive, retryAfter, detail)
}

@Serializable
internal data class EntryDto(
    val key: String,
    val quote: QuoteDto? = null,
    val fetchedAt: String? = null,
    val failure: FailureDto? = null,
)

@Serializable
internal data class AlertDto(
    val holdingId: String,
    val kind: String,
    val discriminator: String,
    val firedAt: String? = null,
    val judgedAsOf: String? = null,
) {
    val tag: String get() = "$holdingId|$kind|$discriminator"

    fun toDomain(): Pair<AlertKey, AlertState> {
        val key = AlertKey(HoldingId(holdingId), enumOf(kind), discriminator)
        val judged = judgedAsOf?.let(::instant)
        val state = firedAt?.let { AlertState.Fired(instant(it), judged) } ?: AlertState.Armed(judged)
        return key to state
    }
}

private fun alertDto(k: AlertKey, s: AlertState) = AlertDto(
    holdingId = k.holdingId.value,
    kind = k.kind.name,
    discriminator = k.discriminator,
    firedAt = (s as? AlertState.Fired)?.at?.toString(),
    judgedAsOf = s.judgedAsOf?.toString(),
)

@Serializable
internal data class RefreshStateDto(val entries: List<EntryDto>, val alerts: List<AlertDto>)
