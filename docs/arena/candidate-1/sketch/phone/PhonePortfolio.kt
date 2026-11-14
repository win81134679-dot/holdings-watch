package holdings.phone

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import holdings.core.DraftResult
import holdings.core.Holding
import holdings.core.HoldingDraft
import holdings.core.HoldingId
import holdings.core.RefreshState
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

/*
 * 手機上的兩個存檔，各自只有一個寫入者（principles〈Separate Before Serializing Shared State〉）：
 *
 *   holdings.json      寫入者：編輯畫面（PhonePortfolio.save/delete）     使用者的正本
 *   refresh-state.json 寫入者：Refresher（WorkManager 唯一工作）          報價快取＋提醒狀態
 *
 * 兩個寫入者從不碰同一個檔，所以不需要鎖；讀的時候（board、Refresher 開頭）才把兩者合併。
 * DataStore 寫入是「寫暫存檔再改名」，中途當機只會留下舊版或新版，不會半份。
 */
internal class PhonePortfolio(
    private val context: Context,
    private val holdings: DataStore<List<Holding>>,
    private val refreshState: DataStore<RefreshState>,
) : Portfolio {

    override val board: Flow<Board> =
        TODO("not implemented: combine(holdings.data, refreshState.data) { h, s -> Board(h, valuate(PortfolioSnapshot(h, s.market.quotes()), now), s.market.entries) }")

    override suspend fun save(id: HoldingId?, draft: HoldingDraft): DraftResult =
        TODO(
            "not implemented: draft.parse(id ?: 新 UUID, todayTaipei()) → Ok 時 holdings.updateData { 以 id 取代或附加 } " +
                "→ RefreshScheduler.runSoon(context, RefreshMode.FOREGROUND)",
        )

    override suspend fun delete(id: HoldingId): Unit =
        TODO("not implemented: holdings.updateData { 去掉 id } → RefreshScheduler.runSoon(...)")

    override fun refresh(foreground: Boolean): Unit =
        TODO("not implemented: RefreshScheduler.runSoon(context, if (foreground) FOREGROUND else MANUAL)")
}

/** DataStore 序列化：直接委派給 core 的 PortfolioCodec；壞檔回空清單並保留壞檔副本（不默默覆蓋使用者資料）。 */
internal object HoldingsSerializer : Serializer<List<Holding>> {
    override val defaultValue: List<Holding> = emptyList()
    override suspend fun readFrom(input: InputStream): List<Holding> =
        TODO("not implemented: PortfolioCodec.decodeHoldings；Corrupt → 丟 CorruptionException，交給 ReplaceFileCorruptionHandler 先備份再回空")
    override suspend fun writeTo(t: List<Holding>, output: OutputStream): Unit = TODO("not implemented")
}

/** 這個檔壞了可以直接丟：報價會重抓，提醒狀態重來最多多震一次。 */
internal object RefreshStateSerializer : Serializer<RefreshState> {
    override val defaultValue: RefreshState = RefreshState.EMPTY
    override suspend fun readFrom(input: InputStream): RefreshState = TODO("not implemented")
    override suspend fun writeTo(t: RefreshState, output: OutputStream): Unit = TODO("not implemented")
}
