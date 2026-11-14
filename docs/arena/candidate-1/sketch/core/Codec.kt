package holdings.core

import kotlinx.serialization.Serializable

/*
 * 所有「離開記憶體」的格式集中在這一個檔：手機的兩個存檔、手機→手錶的同步封包。
 * DTO 全是 internal：:phone、:wear 匯入不到，只能拿到領域型別。
 * 一份 DTO 家族同時服務存檔與同步，所以加一個欄位只改一處。
 * 解碼就是邊界：每個解碼函式都把 DTO 交給領域型別的 parse/of，任何一欄不合法 → 整份回 Corrupt，不丟例外。
 */

/** Refresher 的私有狀態：報價快取＋提醒狀態。兩者都只由 Refresher 寫，所以放同一個檔、一次原子寫入。 */
data class RefreshState(
    val market: MarketData,
    val alerts: Map<AlertKey, AlertState>,
) {
    companion object { val EMPTY = RefreshState(MarketData.EMPTY, emptyMap()) }
}

sealed interface Decoded<out T> {
    data class Ok<T>(val value: T) : Decoded<T>
    /** 封包版本比這支 App 新（例如手機先更新、手錶還沒）。手錶顯示「請更新手錶 App」。 */
    data class TooNew(val version: Int) : Decoded<Nothing>
    data class Corrupt(val detail: String) : Decoded<Nothing>
}

/** 手機→手錶的 Data Layer 合約。兩支 App 都從這裡讀，路徑字串不會有第二份。 */
object SyncContract {
    const val SNAPSHOT_PATH = "/holdings/snapshot"
    /** 不相容改動才加 1；只加選填欄位不用加（JSON 解碼 ignoreUnknownKeys＋預設值）。 */
    const val VERSION = 1
}

object PortfolioCodec {
    // 手機存檔：持股（只有編輯畫面寫）
    fun encodeHoldings(holdings: List<Holding>): ByteArray = TODO("not implemented")
    fun decodeHoldings(bytes: ByteArray): Decoded<List<Holding>> = TODO("not implemented")

    // 手機存檔：Refresher 狀態
    fun encodeRefreshState(state: RefreshState): ByteArray = TODO("not implemented")
    fun decodeRefreshState(bytes: ByteArray): Decoded<RefreshState> = TODO("not implemented")

    /**
     * 同步封包。刻意不含「發布時間」：內容沒變時位元組就一樣，Data Layer 不會觸發 onDataChanged，
     * 手錶不會白白重畫 Tile。重送同一份＝無事發生（冪等）。
     */
    fun encodeSnapshot(snapshot: PortfolioSnapshot): ByteArray = TODO("not implemented")
    fun decodeSnapshot(bytes: ByteArray): Decoded<PortfolioSnapshot> = TODO("not implemented")
}

// ---- internal DTO（草圖只列形狀；BigDecimal 一律以字串存，避免浮點） ----

@Serializable
internal data class EnvelopeDto<T>(val version: Int, val body: T)

@Serializable
internal data class HoldingDto(
    val id: String,
    /** "TW_EQUITY" / "US_EQUITY" / "TW_FUTURES" / "CRYPTO" */
    val market: String,
    /** 台股代號、美股代號、幣種；期貨為期交所商品代碼 */
    val symbol: String,
    val twBoard: String? = null,
    val twUnit: String? = null,
    val futureMonth: String? = null,     // "2026-12"
    val stockFutureUnderlying: String? = null,
    val stockFutureSize: String? = null,
    val quantity: String,
    val side: String = "LONG",
    val averageCost: String,
    val stopLoss: String? = null,
    val target: String? = null,
    val entryDate: String,
    val note: String = "",
)

@Serializable
internal data class QuoteDto(
    val key: String,                     // Quotable 的穩定字串，例如 "TW_EQUITY:TWSE:2330"、"FX:USD_TWD"
    val last: String,
    val previousClose: String,
    val asOfEpochMs: Long,
    val freshness: String,
)

@Serializable
internal data class SnapshotDto(val holdings: List<HoldingDto>, val quotes: List<QuoteDto>)

/** Quotable ↔ 穩定字串鍵。存檔與同步都用它；跟 Yahoo 代號無關。 */
internal fun Quotable.storageKey(): String = TODO("not implemented")
internal fun parseQuotableKey(key: String): Quotable? = TODO("not implemented")
