package tw.personal.holdings.phone

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tw.personal.holdings.core.DraftResult
import tw.personal.holdings.core.HoldingDraft
import tw.personal.holdings.core.HoldingId
import tw.personal.holdings.core.InstrumentChoice
import tw.personal.holdings.core.parse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class HoldingsSerializerTest {
    @get:Rule val dir = TemporaryFolder()

    @Test
    fun `an unreadable holdings file is copied aside before DataStore replaces it`() = runBlocking {
        val garbage = "{\"version\":1,\"body\":[{\"id\":\"x\"".toByteArray()
        val serializer = HoldingsSerializer(dir.root)
        try {
            serializer.readFrom(ByteArrayInputStream(garbage))
            fail("expected CorruptionException")
        } catch (e: CorruptionException) {
            // DataStore's ReplaceFileCorruptionHandler takes over from here.
        }
        val backups = dir.root.listFiles { f -> f.name.startsWith("holdings.corrupt-") }!!.toList()
        assertEquals(1, backups.size)
        assertArrayEquals(garbage, backups.single().readBytes())
    }

    @Test
    fun `holdings written are read back unchanged`() = runBlocking {
        val today = LocalDate.of(2026, 11, 14)
        val h = (HoldingDraft(InstrumentChoice.UsStock("AAPL"), "1.5", averageCostText = "200", entryDate = today)
            .parse(HoldingId("a"), today) as DraftResult.Ok).holding
        val serializer = HoldingsSerializer(dir.root)
        val out = ByteArrayOutputStream()
        serializer.writeTo(listOf(h), out)
        assertEquals(listOf(h), serializer.readFrom(ByteArrayInputStream(out.toByteArray())))
    }
}
