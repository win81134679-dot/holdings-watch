package tw.personal.holdings.quotes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import tw.personal.holdings.core.FetchFailure
import tw.personal.holdings.core.FetchResult
import tw.personal.holdings.core.Quotable
import tw.personal.holdings.core.SourceCapability
import java.time.Duration
import java.time.Instant

/** 抓一批報價。輸入的每個 Quotable 在輸出裡都有一格，不丟例外。[since] 回傳上次抓取時間，用來算兩次之間的高低。 */
interface QuoteFetcher {
    suspend fun fetch(items: Set<Quotable>, since: (Quotable) -> Instant?): Map<Quotable, FetchResult>

    fun capabilityOf(x: Quotable): SourceCapability
}

object QuoteFetchers {
    fun default(http: HttpGet, clock: () -> Instant): QuoteFetcher = create(http, clock, ::delay)

    internal fun create(http: HttpGet, clock: () -> Instant, pause: suspend (Long) -> Unit): QuoteFetcher =
        RoutedFetcher(routeTable(http), clock, pause)
}

internal const val PACING_MILLIS = 500L

/** 一輪裡所有 Yahoo 請求共用：第一個請求不等，之後每個請求前先等。 */
internal class Round(val now: Instant, val since: (Quotable) -> Instant?, private val pause: suspend (Long) -> Unit) {
    private var first = true

    suspend fun beforeYahooRequest() {
        if (!first) pause(PACING_MILLIS)
        first = false
    }
}

/** 回傳裡沒有的商品＝這個來源這次不問它，結果沿用前一條路線的失敗。 */
internal interface Source {
    suspend fun fetch(items: List<Quotable>, round: Round): Map<Quotable, FetchResult>
}

/** [serves] 依序比對；同一個商品被多條路線接住時，前面失敗就換下一條。 */
internal class Route(
    val serves: (Quotable, Instant) -> Boolean,
    val capability: SourceCapability,
    val source: Source,
)

/** 路由表只在這裡寫一次。順序就是後備順序。 */
private fun routeTable(http: HttpGet): List<Route> {
    val spark = YahooSparkSource(http)
    val libra = YahooLibraSource(http)
    val page = YahooFuturePageSource(http)
    val taifex = TaifexSource(http)
    return listOf(
        Route({ x, _ -> yahooSymbol(x) != null }, SourceCapability.INTRADAY, spark),
        Route({ x, now -> yahooFutureSymbol(x, now) != null }, SourceCapability.INTRADAY, libra),
        Route({ x, now -> yahooFutureSymbol(x, now) != null }, SourceCapability.INTRADAY, page),
        Route({ x, _ -> taifexKey(x) != null }, SourceCapability.CLOSE_ONLY, taifex),
    )
}

internal class RoutedFetcher(
    private val routes: List<Route>,
    private val clock: () -> Instant,
    private val pause: suspend (Long) -> Unit,
) : QuoteFetcher {

    override fun capabilityOf(x: Quotable): SourceCapability {
        val now = clock()
        return routes.firstOrNull { it.serves(x, now) }?.capability ?: SourceCapability.INTRADAY
    }

    override suspend fun fetch(items: Set<Quotable>, since: (Quotable) -> Instant?): Map<Quotable, FetchResult> {
        val now = clock()
        val round = Round(now, since, pause)
        val results = LinkedHashMap<Quotable, FetchResult>()
        val earlier = HashMap<Quotable, FetchResult>()
        for ((i, route) in routes.withIndex()) {
            val mine = items.filter { x -> x !in results && route.serves(x, now) }
            if (mine.isEmpty()) continue
            val answers = ask(route.source, mine, round)
            for (x in mine) {
                val result = answers[x] ?: earlier[x] ?: FetchResult.Failed(FetchFailure.Unparseable("source returned no answer"))
                val hasFallback = routes.drop(i + 1).any { it.serves(x, now) }
                if (result is FetchResult.Got || !hasFallback) results[x] = result else earlier[x] = result
            }
        }
        return items.associateWith { results[it] ?: FetchResult.Failed(FetchFailure.Unsupported) }
    }

    private suspend fun ask(source: Source, items: List<Quotable>, round: Round): Map<Quotable, FetchResult> =
        try {
            source.fetch(items, round)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            items.associateWith { FetchResult.Failed(FetchFailure.Network) }
        }
}

/** HTTP 狀態與例外到失敗的對照，三個來源共用。回傳 null 表示 2xx，由來源自己解析 body。 */
internal fun failureOf(response: HttpResponse): FetchFailure? = when (response.status) {
    in 200..299 -> null
    404 -> FetchFailure.NotFound
    429 -> FetchFailure.Throttled(response.retryAfterSeconds?.let(Duration::ofSeconds))
    else -> FetchFailure.Network
}

internal suspend fun HttpGet.getOrFailure(url: String): Pair<HttpResponse?, FetchFailure?> =
    try {
        val response = get(url, mapOf("User-Agent" to "Mozilla/5.0"))
        response to failureOf(response)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null to FetchFailure.Network
    }

internal fun failed(f: FetchFailure) = FetchResult.Failed(f)
