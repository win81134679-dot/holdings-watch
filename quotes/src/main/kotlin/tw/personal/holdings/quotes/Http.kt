package tw.personal.holdings.quotes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 讓 :quotes 不依賴 Android，測試時餵固定內容。連線失敗丟 IOException。 */
fun interface HttpGet {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse
}

class HttpResponse(val status: Int, val body: ByteArray, val retryAfterSeconds: Long?)

/** 預設實作：HttpURLConnection，不加依賴。非 2xx 也回傳內容（Yahoo 的 404 body 帶錯誤碼）。 */
class UrlConnectionHttpGet(private val timeoutMillis: Int = 15_000) : HttpGet {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMillis
                conn.readTimeout = timeoutMillis
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { it.readBytes() } ?: ByteArray(0)
                HttpResponse(status, body, conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
            } finally {
                conn.disconnect()
            }
        }
}
