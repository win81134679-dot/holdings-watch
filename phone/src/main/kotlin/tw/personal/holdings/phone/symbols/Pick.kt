package tw.personal.holdings.phone.symbols

import tw.personal.holdings.core.IndexFuture
import tw.personal.holdings.core.TwBoard
import tw.personal.holdings.phone.MarketTab

/** 搜尋結果裡的一個商品。選了之後由 EditForm.pick 帶入市場、代號、上市櫃與名稱。 */
sealed interface Pick {
    /** 「最近用過」存的鍵，也用來判斷兩次選的是不是同一個商品。 */
    val key: String
    val market: MarketTab
    val code: String

    /** 自動帶入「名稱」欄的字；指數期貨的標籤本身就是中文，不帶。 */
    val autoName: String

    /** 名單裡沒有、照使用者打的代號直接建的。台股要讓使用者自己選上市或上櫃。 */
    val manual: Boolean get() = false

    data class TwStock(
        override val code: String,
        val name: String,
        val english: String,
        val board: TwBoard,
        val volume: Long,
        override val manual: Boolean = false,
    ) : Pick {
        override val key get() = "TW:$code"
        override val market get() = MarketTab.TW
        override val autoName get() = name
    }

    data class UsStock(
        override val code: String,
        val zh: String,
        val english: String,
        /** us_zh_names.csv 的順序（1 最常用）；不在手工表裡是 null。 */
        val rank: Int?,
        override val manual: Boolean = false,
    ) : Pick {
        override val key get() = "US:$code"
        override val market get() = MarketTab.US
        override val autoName get() = zh.ifBlank { english }
    }

    data class Coin(
        override val code: String,
        val zh: String,
        val english: String,
        val rank: Int?,
        override val manual: Boolean = false,
    ) : Pick {
        override val key get() = "C:$code"
        override val market get() = MarketTab.CRYPTO
        override val autoName get() = zh.takeIf { it != code } ?: english
    }

    data class IndexFut(val product: IndexFuture) : Pick {
        override val key get() = "F:${product.name}"
        override val market get() = MarketTab.FUTURES
        override val code get() = product.name
        override val autoName get() = ""
        val title: String get() = INDEX_FUTURE_NAMES.getValue(product).first()
        val aliases: List<String> get() = INDEX_FUTURE_NAMES.getValue(product)
    }

    /** 期交所代號拿不到對照表，選了之後由使用者自己填；持有中的那幾檔已經知道。 */
    data class StockFut(val underlying: TwStock, val taifexCode: String) : Pick {
        override val key get() = "SF:${underlying.code}:$taifexCode"
        override val market get() = MarketTab.FUTURES
        override val code get() = underlying.code
        override val autoName get() = underlying.name
    }

    companion object {
        val INDEX_FUTURE_NAMES: Map<IndexFuture, List<String>> = mapOf(
            IndexFuture.TX to listOf("台指期", "大台", "台指", "臺股期貨"),
            IndexFuture.MTX to listOf("小台指", "小台", "小型台指"),
            IndexFuture.TMF to listOf("微台指", "微台", "微型台指"),
        )
    }
}

/** 搜尋列表與商品卡上的第二行，例如「2330・台股上市」。 */
fun Pick.detail(): String = when (this) {
    is Pick.TwStock -> if (manual) "$code・台股（名單裡沒有）" else "$code・台股${if (board == TwBoard.TWSE) "上市" else "上櫃"}"
    is Pick.UsStock -> if (manual) "$code・美股（名單裡沒有）" else "$code・美股${if (zh.isNotBlank()) "・$english" else ""}"
    is Pick.Coin -> if (manual) "$code・加密貨幣（名單裡沒有）" else "$code・加密貨幣（以美元計價）"
    is Pick.IndexFut -> "${product.name}・期貨・每點 ${product.pointValueTwd.toPlainString()} 元"
    is Pick.StockFut -> "${underlying.code}・個股期貨${if (taifexCode.isNotBlank()) "・期交所代號 $taifexCode" else ""}"
}

/** 搜尋列表與商品卡上的大字。 */
fun Pick.title(): String = when (this) {
    is Pick.TwStock -> name.ifBlank { code }
    is Pick.UsStock -> zh.ifBlank { english.ifBlank { code } }
    is Pick.Coin -> zh.ifBlank { code }
    is Pick.IndexFut -> title
    is Pick.StockFut -> "${underlying.name.ifBlank { underlying.code }} 個股期貨"
}
