package com.example.holdings.core

import java.time.LocalDate

@JvmInline value class HoldingId(val raw: String)

enum class Side(val sign: Int) { LONG(1), SHORT(-1) }

/** 停損與目標。建構子私有，只能經 [of]：停損／目標的先後順序依方向檢查一次，往下信任。 */
class ExitLevels private constructor(val stop: Price?, val target: Price?) {

    /** 單一真相：「這個價位對這筆持股算不算碰線」。提醒判斷與手錶上的紅點／綠點都從它推導，不各算一份。 */
    fun status(side: Side, last: Price): ExitStatus = TODO(
        """not implemented:
        LONG : last <= stop -> StopHit; last >= target -> TargetHit
        SHORT: last >= stop -> StopHit; last <= target -> TargetHit
        else Within"""
    )

    companion object {
        val NONE: ExitLevels = ExitLevels(null, null)
        /** LONG 要求 stop < target；SHORT 要求 stop > target。兩個都給才檢查順序。 */
        fun of(side: Side, stop: Price?, target: Price?): Result<ExitLevels> = TODO("not implemented")
    }
}

enum class ExitStatus { WITHIN, STOP_HIT, TARGET_HIT }

/**
 * 一筆持股。唯一寫入者是手機（決策 Q2），所以沒有版本向量、沒有合併。
 * 建構子私有：數量、成本、出場價、進場日的跨欄位規則在 [create] 驗一次。
 */
class Holding private constructor(
    val id: HoldingId,
    val instrument: Instrument,
    val side: Side,
    val qty: Quantity,
    val avgCost: Price,
    val exits: ExitLevels,
    val entryDate: LocalDate,
    val name: String,     // 使用者自填的顯示名；Yahoo 沒有中文名（見 Yahoo報價實測.md）。空字串 = 顯示代號
    val note: String,
) {
    data class Draft(
        val instrument: Instrument?, val side: Side, val qtyRaw: String, val qtyUnit: QtyInputUnit,
        val avgCostRaw: String, val stopRaw: String, val targetRaw: String,
        val entryDate: LocalDate, val name: String, val note: String,
    )

    /** 表單草稿（全是字串）-> 領域型別。一次回報所有欄位錯誤，給表單逐欄顯示。 */
    sealed interface Parsed {
        data class Ok(val holding: Holding) : Parsed
        data class Invalid(val fieldErrors: Map<Field, String>) : Parsed
    }
    enum class Field { INSTRUMENT, QTY, AVG_COST, STOP, TARGET, ENTRY_DATE }

    companion object {
        fun create(id: HoldingId, draft: Draft, today: LocalDate): Parsed = TODO("not implemented")
    }
}
