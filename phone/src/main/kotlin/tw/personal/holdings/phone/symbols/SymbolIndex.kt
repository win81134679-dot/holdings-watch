package tw.personal.holdings.phone.symbols

import tw.personal.holdings.core.CoinSymbol
import tw.personal.holdings.core.Crypto
import tw.personal.holdings.core.Holding
import tw.personal.holdings.core.IndexFuture
import tw.personal.holdings.core.StockFuture
import tw.personal.holdings.core.TwBoard
import tw.personal.holdings.core.TwCode
import tw.personal.holdings.core.TwEquity
import tw.personal.holdings.core.TwFuture
import tw.personal.holdings.core.UsEquity
import tw.personal.holdings.core.UsTicker
import java.text.Normalizer
import java.util.Locale

/** 空白輸入時的兩段建議。持有中已經出現在最近用過的不重複列。 */
data class Suggestions(val recent: List<Pick>, val held: List<Pick>)

/**
 * 本機搜尋。吻合度：代號完全符合 > 代號開頭 > 名稱開頭 > 名稱包含 > 英文名包含；
 * 同分時台股 > 指數期貨 > 加密 > 美股常用 > 其他美股，同類再以成交量（台股）或常用順序優先。
 */
class SymbolIndex(tw: List<Pick.TwStock>, us: List<Pick.UsStock>, coins: List<Pick.Coin>) {

    private class Entry(val pick: Pick, val tier: Int, val popularity: Long, val codes: List<String>, val names: List<String>, val english: String)

    private val entries: List<Entry> = buildList {
        for (t in tw) add(Entry(t, 0, t.volume, listOf(normalize(t.code)), listOf(normalize(t.name)), words(t.english)))
        for (f in IndexFuture.entries) {
            val p = Pick.IndexFut(f)
            add(Entry(p, 1, (IndexFuture.entries.size - f.ordinal).toLong(), listOf(normalize(f.name)), p.aliases.map(::normalize), ""))
        }
        for (c in coins) add(Entry(c, 2, -(c.rank ?: Int.MAX_VALUE).toLong(), listOf(normalize(c.code)), listOf(normalize(c.zh)), words(c.english)))
        for (u in us) {
            val tier = if (u.rank != null) 3 else 5
            val names = if (u.zh.isBlank()) emptyList() else listOf(normalize(u.zh))
            add(Entry(u, tier, -(u.rank ?: Int.MAX_VALUE).toLong(), listOf(normalize(u.code)), names, words(u.english)))
        }
    }

    private val byKey: Map<String, Pick> = entries.associate { it.pick.key to it.pick }
    private val twByCode: Map<String, Pick.TwStock> = tw.associateBy { it.code }

    fun search(query: String, limit: Int = 30): List<Pick> {
        val q = Query(normalize(query), words(query))
        if (q.compact.isEmpty()) return emptyList()
        val ranked = rank(q)
        val futuresIntent = FUTURES_SUFFIXES.firstOrNull { q.compact.endsWith(it) && q.compact.length > it.length }
        val stockFutures = if (futuresIntent != null) {
            val base = q.compact.removeSuffix(futuresIntent)
            stockFuturesFor(rank(Query(base, base)))
        } else {
            stockFuturesFor(ranked)
        }
        val manual = if (ranked.any { score(it, q) == EXACT_CODE }) emptyList() else manualPicks(query)
        val main = ranked.map { it.pick }
        return if (futuresIntent != null) {
            (stockFutures + main + manual).take(limit)
        } else {
            main.take(limit - stockFutures.size - manual.size) + stockFutures + manual
        }
    }

    fun suggest(recentKeys: List<String>, holdings: List<Holding>): Suggestions {
        val recent = recentKeys.mapNotNull(::resolve).distinctBy { it.key }
        val shown = recent.map { it.key }.toSet()
        val held = holdings.map(::pickFor).distinctBy { it.key }.filterNot { it.key in shown }
        return Suggestions(recent, held)
    }

    fun resolve(key: String): Pick? {
        byKey[key]?.let { return it }
        if (!key.startsWith("SF:")) return null
        val parts = key.split(":")
        val underlying = twByCode[parts.getOrNull(1)] ?: return null
        return Pick.StockFut(underlying, parts.getOrNull(2).orEmpty())
    }

    /** 持有中的商品就算已經不在名單上（下市、改名）也要列得出來，用持股自己的資料補。 */
    fun pickFor(h: Holding): Pick = when (val x = h.asset.instrument) {
        is TwEquity -> twByCode[x.code.value] ?: Pick.TwStock(x.code.value, h.name.orEmpty(), "", x.board, 0, manual = true)
        is UsEquity -> byKey["US:${x.ticker.value}"] ?: Pick.UsStock(x.ticker.value, h.name.orEmpty(), "", null, manual = true)
        is Crypto -> byKey["C:${x.coin.value}"] ?: Pick.Coin(x.coin.value, h.name.orEmpty(), "", null, manual = true)
        is TwFuture -> when (val p = x.product) {
            is IndexFuture -> Pick.IndexFut(p)
            is StockFuture -> Pick.StockFut(
                twByCode[p.underlying.value] ?: Pick.TwStock(p.underlying.value, h.name.orEmpty(), "", TwBoard.TWSE, 0, manual = true),
                p.taifexCode,
            )
        }
    }

    private fun rank(q: Query): List<Entry> =
        entries.asSequence()
            .map { it to score(it, q) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Entry, Int>> { it.second }
                    .thenBy { it.first.tier }
                    .thenByDescending { it.first.popularity }
                    .thenBy { it.first.pick.code.length }
                    .thenBy { it.first.pick.code },
            )
            .map { it.first }
            .toList()

    private fun score(e: Entry, q: Query): Int = when {
        e.codes.any { it == q.compact } -> EXACT_CODE
        e.codes.any { it.startsWith(q.compact) } -> 4
        e.names.any { it.startsWith(q.compact) } -> 3
        e.names.any { it.contains(q.compact) } -> 2
        e.english.contains(q.words) -> 1
        else -> 0
    }

    /** 中文名與代號去掉空白比對；英文名保留字間空白，「tsmc」才不會配到「Short SMCI」。 */
    private class Query(val compact: String, val words: String)

    /**
     * 期交所的個股期貨代號對照拿不到，所以股票與 ETF 都可能有（ETN 沒有）；
     * 只列最前面幾檔，避免整頁都是期貨。
     */
    private fun stockFuturesFor(ranked: List<Entry>): List<Pick> =
        ranked.asSequence().map { it.pick }.filterIsInstance<Pick.TwStock>()
            .filter { it.code.length == 4 || it.code.startsWith("00") }
            .take(STOCK_FUTURE_ROWS)
            .map { Pick.StockFut(it, "") }.toList()

    /** 名單還沒收錄（剛上市、名單過期）時，照打的代號直接建，至少能新增。 */
    private fun manualPicks(query: String): List<Pick> {
        val raw = Normalizer.normalize(query, Normalizer.Form.NFKC).trim().uppercase(Locale.ROOT)
        TwCode.parse(raw)?.let { return listOf(Pick.TwStock(it.value, "", "", TwBoard.TWSE, 0, manual = true)) }
        if (raw.length > MANUAL_LETTERS_MAX || raw.any { it !in 'A'..'Z' && it !in '0'..'9' && it != '.' && it != '-' }) return emptyList()
        return listOfNotNull(
            UsTicker.parse(raw.replace('.', '-'))?.let { Pick.UsStock(it.value, "", "", null, manual = true) },
            CoinSymbol.parse(raw)?.let { Pick.Coin(it.value, "", "", null, manual = true) },
        )
    }

    companion object {
        private const val EXACT_CODE = 5
        private const val STOCK_FUTURE_ROWS = 3
        private const val MANUAL_LETTERS_MAX = 6
        private val FUTURES_SUFFIXES = listOf("期貨", "期")
        private val WHITESPACE = Regex("\\s+")

        /** 全形轉半形、不分大小寫、臺＝台、去掉空白，代號的「.」與「-」視為同一個。 */
        fun normalize(text: String): String = words(text).filterNot { it == ' ' }

        /** 同 [normalize]，但字間留一個空白。 */
        fun words(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .replace('臺', '台')
                .replace('.', '-')
                .split(WHITESPACE)
                .filter { it.isNotEmpty() }
                .joinToString(" ")
    }
}
