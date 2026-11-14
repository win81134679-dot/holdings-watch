package tw.personal.holdings.phone.symbols

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.personal.holdings.phone.TAG
import tw.personal.holdings.phone.todayTaipei
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 每週一次、只在 Wi-Fi（不計量網路）＋充電時更新股票名單。跟報價刷新用不同的唯一工作名，互不排隊。
 * 這份下載約 9 MB（櫃買日行情含權證，佔大半），所以不在行動網路或電池供電時做。
 * APK 只內建常用的美股（完整名單不跟 APK 散布），所以還沒下載過時，第一次連上 Wi-Fi 就先抓一次、不等充電。
 */
internal object SymbolsUpdate {
    private const val NAME = "symbols-weekly"
    private const val FIRST = "symbols-first"

    fun ensureWeekly(context: Context) {
        if (!File(SymbolRepository.get(context).updatedDir, "us.tsv").isFile) {
            val first = OneTimeWorkRequestBuilder<SymbolsUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(FIRST, ExistingWorkPolicy.KEEP, first)
        }
        val request = PeriodicWorkRequestBuilder<SymbolsUpdateWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

internal class SymbolsUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = SymbolRepository.get(applicationContext)
        val raw = mutableMapOf<String, String>()
        for ((name, url) in SymbolLists.SOURCES) {
            try {
                raw[name] = SymbolLists.decode(download(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "symbols: $url ${e.javaClass.simpleName}: ${e.message}")
                if (name !in SymbolLists.OPTIONAL) return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            }
        }
        return try {
            val tw = SymbolLists.buildTw(
                raw.getValue("twse_companies.json"),
                raw.getValue("twse_day_all.json"),
                raw.getValue("tpex_companies.json"),
                raw["tpex_daily.json"],
            )
            val zh = SymbolLists.zhNames(SymbolLists.parseUs(repo.bundledText("us.tsv")))
            val us = SymbolLists.buildUs(raw.getValue("nasdaqlisted.txt"), raw.getValue("otherlisted.txt"), zh)
            // 來源偶爾回半份資料；比內建名單少一大截就不覆蓋，下週再試。
            if (tw.size < MIN_TW || us.size < MIN_US) {
                Log.w(TAG, "symbols: too few rows tw=${tw.size} us=${us.size}, kept the old lists")
                return Result.success()
            }
            val stamp = todayTaipei().toString()
            withContext(Dispatchers.IO) {
                repo.updatedDir.mkdirs()
                replace(File(repo.updatedDir, "tw.tsv"), SymbolLists.twTsv(tw, stamp))
                replace(File(repo.updatedDir, "us.tsv"), SymbolLists.usTsv(us, stamp))
            }
            repo.invalidate()
            Log.i(TAG, "symbols: updated tw=${tw.size} us=${us.size}")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "symbols: build failed", e)
            Result.success()
        }
    }

    private suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MILLIS
            conn.readTimeout = TIMEOUT_MILLIS
            // v0.2 研究時櫃買對某些用戶端回 403，一律帶 User-Agent。
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) holdings-app")
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun replace(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 60_000
        const val MAX_ATTEMPTS = 3
        const val MIN_TW = 1500
        const val MIN_US = 5000
    }
}
