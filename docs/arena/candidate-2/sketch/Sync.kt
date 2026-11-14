package com.example.holdings.core

/**
 * 同步合約：手機對手錶只有一個方向、一個項目：路徑 /board/v1，內容是 Board 的編碼位元組。
 * 手錶不寫任何東西回手機，所以沒有雙寫者、沒有合併、沒有版本衝突。
 * 持股本身不上手錶；手錶只收「已算好的看板」。
 *
 * 線上格式（DTO）是 internal，公開面只有 encode/decode 兩個函式與 [DecodeResult]。
 */
object BoardCodec {
    const val SCHEMA = 1
    const val PATH = "/board/v1"      // schema 升級改路徑；舊手錶讀不到新路徑，會顯示「請更新手錶 App」而不是亂解析

    fun encode(b: Board): ByteArray = TODO("not implemented: internal BoardDto + kotlinx.serialization JSON；BigDecimal 一律用字串")
    fun decode(bytes: ByteArray): DecodeResult = TODO("not implemented: 邊界驗證在這裡做完（價格 > 0、Instrument.parse 成功），失敗回 Unreadable，不丟例外")

    sealed interface DecodeResult {
        data class Ok(val board: Board) : DecodeResult
        data class Unreadable(val reason: String) : DecodeResult
    }
}

/** 手錶端讀取看板的唯一路徑（Tile、小格、App 共用）。 */
interface BoardReplica {
    sealed interface State {
        data object NeverSynced : State                                   // 手錶還沒收過任何資料：顯示「請先在手機開啟 App」
        data class Ready(val board: Board) : State
        data class Unreadable(val reason: String) : State
    }
    /** 一次性讀（Tile／小格的請求回呼用）。 */
    suspend fun current(): State
    /** 持續觀察（手錶 App 用）。 */
    fun observe(): kotlinx.coroutines.flow.Flow<State>
}
