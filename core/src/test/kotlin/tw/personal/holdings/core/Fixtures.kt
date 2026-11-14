package tw.personal.holdings.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

val TODAY: LocalDate = LocalDate.of(2026, 11, 16)

fun taipei(text: String): Instant = LocalDateTime.parse(text).atZone(TAIPEI).toInstant()
fun newYork(text: String): Instant = LocalDateTime.parse(text).atZone(ZoneId.of("America/New_York")).toInstant()

fun price(text: String): Price = Price.parse(text)!!

fun draft(
    instrument: InstrumentChoice,
    quantity: String,
    cost: String,
    side: Side = Side.LONG,
    stop: String = "",
    target: String = "",
    entryDate: LocalDate = LocalDate.of(2026, 11, 2),
) = HoldingDraft(
    instrument = instrument,
    quantityText = quantity,
    side = side,
    averageCostText = cost,
    stopLossText = stop,
    targetText = target,
    entryDate = entryDate,
)

fun holding(d: HoldingDraft, id: String = "h1", today: LocalDate = TODAY): Holding =
    when (val r = d.parse(HoldingId(id), today)) {
        is DraftResult.Ok -> r.holding
        is DraftResult.Invalid -> error("fixture draft rejected: ${r.problems}")
    }

fun twStock(code: String = "2330", unit: TwQtyUnit = TwQtyUnit.LOT, board: TwBoard = TwBoard.TWSE) =
    InstrumentChoice.TwStock(code, board, unit)

fun tx(month: String = "2026-12") = InstrumentChoice.IndexFut(IndexFuture.TX, ContractMonth.parse(month)!!)

fun quote(
    last: String,
    previousClose: String = last,
    asOf: Instant,
    freshness: Freshness = Freshness.LIVE,
    low: String? = null,
    high: String? = null,
) = Quote(price(last), price(previousClose), asOf, freshness, low?.let(::price), high?.let(::price))
