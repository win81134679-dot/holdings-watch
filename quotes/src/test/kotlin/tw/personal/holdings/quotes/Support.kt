package tw.personal.holdings.quotes

import tw.personal.holdings.core.CoinSymbol
import tw.personal.holdings.core.ContractMonth
import tw.personal.holdings.core.Crypto
import tw.personal.holdings.core.FetchFailure
import tw.personal.holdings.core.FetchResult
import tw.personal.holdings.core.IndexFuture
import tw.personal.holdings.core.Price
import tw.personal.holdings.core.Quote
import tw.personal.holdings.core.TAIPEI
import tw.personal.holdings.core.TwBoard
import tw.personal.holdings.core.TwCode
import tw.personal.holdings.core.TwEquity
import tw.personal.holdings.core.TwFuture
import tw.personal.holdings.core.UsEquity
import tw.personal.holdings.core.UsTicker
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime

private object Anchor

fun fixture(name: String): ByteArray =
    checkNotNull(Anchor.javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }.readBytes()

fun fixtureText(name: String): String = fixture(name).toString(Charsets.UTF_8)

fun ok(body: ByteArray) = HttpResponse(200, body, null)
fun ok(body: String) = ok(body.toByteArray(Charsets.UTF_8))

fun taipei(text: String): Instant = LocalDateTime.parse(text).atZone(TAIPEI).toInstant()

fun tw(code: String, board: TwBoard = TwBoard.TWSE) = TwEquity(TwCode.parse(code)!!, board)
fun us(ticker: String) = UsEquity(UsTicker.parse(ticker)!!)
fun coin(symbol: String) = Crypto(CoinSymbol.parse(symbol)!!)
fun future(product: IndexFuture, year: Int, month: Int) = TwFuture(product, ContractMonth.of(year, month))

fun price(text: String) = Price.parse(text)!!

/** 依網址回固定內容；沒登記的網址丟 IOException，模擬連不上。 */
class FakeHttp(
    private val responses: Map<String, HttpResponse> = emptyMap(),
    private val log: MutableList<String> = mutableListOf(),
) : HttpGet {
    val calls: List<String> get() = log.filter { it.startsWith("GET ") }.map { it.removePrefix("GET ") }
    val headers = mutableListOf<Map<String, String>>()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        log += "GET $url"
        this.headers += headers
        return responses[url] ?: throw IOException("no route for $url")
    }
}

const val TAIFEX_URL = "https://openapi.taifex.com.tw/v1/DailyMarketReportFut"
fun sparkUrlOf(vararg symbols: String) =
    "https://query1.finance.yahoo.com/v7/finance/spark?symbols=${symbols.joinToString(",")}&range=1d&interval=1m"
fun futurePageUrl(symbol: String) = "https://tw.stock.yahoo.com/future/$symbol"
const val LIBRA_PREFIX = "https://tw.stock.yahoo.com/_td-stock/api/resource/FinanceChartService.ApacLibraCharts"
fun libraUrlOf(vararg symbols: String) = libraUrl(symbols.toList())

fun FetchResult.quote(): Quote = assertIs<FetchResult.Got>(this).quote
fun FetchResult.failure(): FetchFailure = assertIs<FetchResult.Failed>(this).failure

inline fun <reified T> assertIs(value: Any?): T {
    org.junit.Assert.assertTrue("expected ${T::class.simpleName} but was $value", value is T)
    return value as T
}
