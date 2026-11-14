package tw.personal.holdings.phone.symbols

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tw.personal.holdings.phone.TAG
import java.io.File

/**
 * 名單來源：App 每週下載的檔（filesDir/symbols/）優先，沒有或讀不懂就用 APK 內建的 assets/symbols/。
 * 載入一次後留在記憶體，更新工作寫完新檔再叫 [invalidate]。
 */
internal class SymbolRepository private constructor(private val context: Context) {
    private val mutex = Mutex()
    @Volatile private var cached: SymbolIndex? = null

    val updatedDir: File get() = File(context.filesDir, "symbols")

    suspend fun index(): SymbolIndex = cached ?: mutex.withLock {
        cached ?: withContext(Dispatchers.Default) { load() }.also { cached = it }
    }

    fun invalidate() {
        cached = null
    }

    fun bundledText(name: String): String = context.assets.open("symbols/$name").use { it.readBytes().toString(Charsets.UTF_8) }

    private fun load(): SymbolIndex {
        val tw = read("tw.tsv", SymbolLists::parseTw)
        val us = read("us.tsv", SymbolLists::parseUs)
        val coins = SymbolLists.parseCrypto(bundledText("crypto.csv"))
        return SymbolIndex(tw, us, coins)
    }

    private fun <T> read(name: String, parse: (String) -> List<T>): List<T> {
        val updated = File(updatedDir, name)
        if (updated.isFile) {
            runCatching { parse(updated.readText()) }
                .onSuccess { return it }
                .onFailure { Log.w(TAG, "symbols: $name 讀不懂，改用內建名單", it) }
        }
        return parse(bundledText(name))
    }

    companion object {
        @Volatile private var instance: SymbolRepository? = null

        fun get(context: Context): SymbolRepository =
            instance ?: synchronized(this) { instance ?: SymbolRepository(context.applicationContext).also { instance = it } }
    }
}

/** 新增或編輯成功存檔的商品，最新的在前，最多 [MAX_RECENT] 個。 */
internal class RecentSymbols(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("recent-symbols", Context.MODE_PRIVATE)

    fun keys(): List<String> = prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotEmpty() }

    fun record(key: String) {
        prefs.edit().putString(KEY, pushRecent(keys(), key).joinToString("\n")).apply()
    }

    companion object {
        private const val KEY = "keys"
        const val MAX_RECENT = 8
    }
}

internal fun pushRecent(keys: List<String>, key: String, max: Int = RecentSymbols.MAX_RECENT): List<String> =
    (listOf(key) + keys.filterNot { it == key }).take(max)
