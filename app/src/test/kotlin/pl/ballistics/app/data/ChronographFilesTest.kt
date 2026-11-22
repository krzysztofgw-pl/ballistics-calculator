package pl.ballistics.app.data

import pl.ballistics.core.Chronograph
import pl.ballistics.core.SpeedUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class ChronographFilesTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("chronograph/$name")!!.use { it.readBytes() }

    @Test
    fun athlonWorkbookGivesOneSeriesPerSheet() {
        val all = ChronographFiles.parse(resource("athlon-rangecraft.xlsx"))
        assertEquals(7, all.size)
        val first = all.first()
        assertEquals(Chronograph.ATHLON_RANGECRAFT, first.device)
        assertEquals(SpeedUnit.MPS, first.unit)
        assertEquals("2025-11-08 09:21", first.date)
        assertEquals(listOf(776.4, 779.9, 779.6), first.shots.map { it.velocityMps })
        assertEquals(listOf(982.6, 985.4, 983.9), all[1].shots.map { it.velocityMps })
    }

    @Test
    fun plainFilesGoStraightToTheParser() {
        val csv = "Shot,Velocity (m/s)\n1,800.5\n2,801.5\n".encodeToByteArray()
        assertEquals(listOf(800.5, 801.5), ChronographFiles.parse(csv).single().shots.map { it.velocityMps })
    }
}
