package pl.ballistics.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ShotView exports and Xero FIT files below are real ones, from the test assets of
 * xeroconv (https://github.com/useyournose/xeroconv, MIT licence, © 2024 useyournose).
 * The Labradar report follows the layout of the files on the device's SD card.
 */
class ChronographTest {
    @Test
    fun shotViewIosEnglishInFps() {
        val s = ChronographImport.parse(SHOTVIEW_IOS_EN.encodeToByteArray()).single()
        assertEquals(Chronograph.GARMIN_XERO, s.device)
        assertEquals("105 hybrid 31.0gt", s.title)
        assertEquals(SpeedUnit.FPS, s.unit)
        assertEquals(19, s.shots.size)
        assertEquals(2808.2 * 0.3048, s.shots.first().velocityMps, 1e-9)
        assertEquals(19, s.shots.last().number)
        assertEquals("April 6, 2024 at 1:36 PM", s.date)
        // The file's own summary: average 2794.3 fps, SD 8.5, spread 31.7.
        val stats = VelocityStats.of(s.shots.map { it.velocityMps / 0.3048 })!!
        assertEquals(2794.3, stats.mean, 0.05)
        assertEquals(8.5, stats.sd, 0.05)
        assertEquals(31.7, stats.es, 0.05)
        assertTrue(VelocityStats.outliers(s.shots.map { it.velocityMps }).isEmpty())
    }

    @Test
    fun shotViewIosGermanInMetres() {
        val s = ChronographImport.parse(SHOTVIEW_IOS_DE.encodeToByteArray()).single()
        assertEquals(SpeedUnit.MPS, s.unit)
        assertEquals("55grs FOX - 23,30grs D073.4", s.title)
        assertEquals(listOf(924.3, 918.7, 916.3, 915.0), s.shots.map { it.velocityMps })
        assertEquals("8. Februar 2025 um 10:08", s.date)
    }

    @Test
    fun shotViewAndroidGermanWithDecimalCommasAndAMisread() {
        val s = ChronographImport.parse(SHOTVIEW_ANDROID_DE.encodeToByteArray()).single()
        assertEquals(SpeedUnit.MPS, s.unit)
        assertEquals("Marlin,.357, 15,3grs W296, 158,0 gr", s.title)
        assertEquals(10, s.shots.size)
        assertEquals(488.3, s.shots[0].velocityMps, 1e-9)
        assertEquals(425.5, s.shots[1].velocityMps, 1e-9)
        val v = s.shots.map { it.velocityMps }
        val stats = VelocityStats.of(v)!!
        assertEquals(474.1, stats.mean, 0.05)
        assertEquals(17.1, stats.sd, 0.05)
        assertEquals(62.8, stats.es, 0.05)
        // Shot 2 is 50 m/s slower than the rest: dropped, and nothing else.
        assertEquals(setOf(1), VelocityStats.outliers(v))
    }

    @Test
    fun xeroFitFiles() {
        val a = ChronographImport.parse(XERO_FIT_2024).single()
        assertEquals(SpeedUnit.MPS, a.unit)
        assertEquals((1..10).toList(), a.shots.map { it.number })
        assertEquals(listOf(265.391, 254.826, 253.934, 262.184, 271.003), a.shots.take(5).map { it.velocityMps })
        assertEquals(289.885, a.shots.last().velocityMps, 1e-9)

        val b = ChronographImport.parse(XERO_FIT_2026).single()
        assertEquals(listOf(980.278, 975.011, 977.98, 980.506, 974.34), b.shots.map { it.velocityMps })
        val stats = VelocityStats.of(b.shots.map { it.velocityMps })!!
        assertEquals(977.623, stats.mean, 0.001)
        assertEquals(2.573, stats.sd, 0.001)
    }

    @Test
    fun brokenFitFile() {
        val cut = XERO_FIT_2026.copyOf(60)
        val e = assertFailsWith<ChronographFormatException> { ChronographImport.parse(cut) }
        assertEquals(ChronographFormatException.Reason.NO_SHOTS, e.reason)
    }

    @Test
    fun labradarReportInUtf16() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16le(LABRADAR)
        val s = ChronographImport.parse(bytes).single()
        assertEquals(Chronograph.LABRADAR, s.device)
        assertEquals("SR0007", s.title)
        assertEquals(SpeedUnit.FPS, s.unit)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), s.shots.map { it.number })
        assertEquals(2770.20 * 0.3048, s.shots[0].velocityMps, 1e-9)
        assertEquals("11-05-2026", s.date)
        assertEquals(setOf(4), VelocityStats.outliers(s.shots.map { it.velocityMps }))
    }

    @Test
    fun labradarReportWithoutByteOrderMarkInMetres() {
        val text = LABRADAR.replace("fps", "m/s")
        val s = ChronographImport.parse(utf16le(text)).single()
        assertEquals(SpeedUnit.MPS, s.unit)
        assertEquals(2770.20, s.shots[0].velocityMps, 1e-9)
        assertEquals(6, ChronographImport.parse(text.encodeToByteArray()).single().shots.size)
    }

    @Test
    fun labradarTrackFileIsNotASeries() {
        val track = "sep=;\nDevice ID;LBR-0051043;;\nSeries No;0013\nShot No;0001\n\nTime (s);Vel (m/s);Dist (m);SNR\n0.000000;767.75;0.00;-\n"
        val e = assertFailsWith<ChronographFormatException> { ChronographImport.parse(track.encodeToByteArray()) }
        assertEquals(ChronographFormatException.Reason.LABRADAR_TRACK, e.reason)
    }

    @Test
    fun otherFilesAreRejected() {
        val e = assertFailsWith<ChronographFormatException> { ChronographImport.parse("name,age\nAnn,4\n".encodeToByteArray()) }
        assertEquals(ChronographFormatException.Reason.NOT_RECOGNISED, e.reason)
        val blank = assertFailsWith<ChronographFormatException> { ChronographImport.parse(ByteArray(0)) }
        assertEquals(ChronographFormatException.Reason.NOT_RECOGNISED, blank.reason)
        val empty = LABRADAR.substringBefore("0001;")
        assertEquals(
            ChronographFormatException.Reason.NO_SHOTS,
            assertFailsWith<ChronographFormatException> { ChronographImport.parse(empty.encodeToByteArray()) }.reason,
        )
    }

    @Test
    fun numbers() {
        assertEquals(488.3, ChronographImport.number("488,3"))
        assertEquals(2808.2, ChronographImport.number(" 2808.2 "))
        assertEquals(2808.2, ChronographImport.number("2,808.2"))
        assertEquals(2808.2, ChronographImport.number("2.808,2"))
        assertNull(ChronographImport.number(""))
        assertEquals(listOf("1", "488,3", "x\"y", ""), ChronographImport.csvFields("1,\"488,3\", \"x\"\"y\" ,"))
    }

    @Test
    fun statistics() {
        val s = VelocityStats.of(listOf(800.0, 802.0, 804.0))!!
        assertEquals(802.0, s.mean)
        assertEquals(1.633, s.sd, 1e-3)
        assertEquals(4.0, s.es)
        assertEquals(0.0, VelocityStats.of(listOf(800.0))!!.sd)
        assertNull(VelocityStats.of(emptyList()))
        // Too few shots to call one bad; near-identical shots keep a tiny but honest difference.
        assertTrue(VelocityStats.outliers(listOf(800.0, 800.0, 700.0, 801.0)).isEmpty())
        assertTrue(VelocityStats.outliers(listOf(800.0, 800.0, 800.0, 801.0, 803.0)).isEmpty())
    }


    @Test
    fun labradarNulPaddedLines() {
        val text = "sep=;\r\nDevice ID;LBR-1;;\u0000   \r\n\u0000Series No;0003;;\u0000  \r\n\u0000Units velocity;fps;;\u0000  \r\n\u0000\r\n" +
            "Shot ID;V0;V1;V2;Ke0\r\n0001;2755;2754;2752;2393;\r\n0002;;2752;2751;;\r\n0003;2754;2753;2751;2391;\r\n"
        val s = ChronographImport.parse(text.encodeToByteArray()).single()
        assertEquals("SR0003", s.title)
        assertEquals(listOf(1, 3), s.shots.map { it.number })
        assertEquals(2754 * 0.3048, s.shots[1].velocityMps, 1e-9)
    }

    // Labradar LX files as published with ChamberSim-Reloaded's tests (github.com/Schildkroet/ChamberSim-Reloaded).
    private val lxVelocities = listOf(761.86, 760.85, 762.25, 760.33, 761.06, 758.59, 759.12, 760.82, 758.11, 761.65, 761.59)

    @Test
    fun labradarLxSemicolonWithUnitRow() {
        val text = "sep=;\r\nDevice ID;Labradar;;\r\n\r\n" +
            "Series No;013 G11 7,5x55 42gr N150 L6=75,8 Hornady HPBT168;;\r\nTotal number of shots;0011;;\r\n\r\n" +
            "Projectile Weight;168.00;grain;\r\nTemperature;15.00;degC;\r\n\r\n" +
            "Stats - Average;760.57;m/s;\r\nStats - Max;762.25;m/s;\r\nStats - Min;758.11;m/s;\r\n\r\n" +
            "Shot ID;V0;V9.00m;Ke0;Ke9.00m;PF9.00m;BC G1;BC G7;Date;Time\r\n;m/s;m/s;j;j;\r\n" +
            lxVelocities.mapIndexed { i, v -> "${(i + 1).toString().padStart(4, '0')};$v;756.58;3159.33;3115.73;417.01;0.507;0.255;2026-10-20;18:07;\r\n" }.joinToString("")
        val s = ChronographImport.parse(text.encodeToByteArray()).single()
        assertEquals(Chronograph.LABRADAR, s.device)
        assertEquals("013 G11 7,5x55 42gr N150 L6=75,8 Hornady HPBT168", s.title)
        assertEquals(SpeedUnit.MPS, s.unit)
        assertEquals(false, s.unitGuessed)
        assertEquals(lxVelocities, s.shots.map { it.velocityMps })
        assertEquals("2026-10-20", s.date)
    }

    @Test
    fun labradarLxComma() {
        val text = "Device ID,LLXA001Y6HF\r\n\r\nSeries Number,13\r\nShot Count,11\r\n\r\nMode,Standard\r\n\r\n" +
            "Caliber,0.308,in\r\nProjectile Weight,168.000,gr\r\nTemperature,15.0,°C\r\nStation Pressure,1020.3,mbar\r\n\r\n" +
            "Stats - Average,760.57,ft/s\r\nStats - Max,762.25,ft/s\r\nStats - Min,758.11,ft/s\r\n\r\n" +
            "Shot Number,V0.00m,V9.00m,KE9.00m,PF9.00m,BC G1,BC G7,Date,Time\r\n" +
            lxVelocities.mapIndexed { i, v -> "${i + 1},$v,756.58,3115.73,417.01,0.507,0.255,2026-10-20,18:07:20\r\n" }.joinToString("")
        val s = ChronographImport.parse(text.encodeToByteArray()).single()
        assertEquals("SR13", s.title)
        // No unit row: the unit of the "Stats - " lines.
        assertEquals(SpeedUnit.FPS, s.unit)
        assertEquals(lxVelocities.map { it * 0.3048 }, s.shots.map { it.velocityMps })
    }

    // The layout read by ChronoPlotter (github.com/mncoppola/ChronoPlotter) for the V3 and the XFR app.
    @Test
    fun magnetoSpeedV3WithTwoSeries() {
        val text = "Series,15, Shots:,3\r\nMin,2721, Max,2770  \r\nAvg,2746 ,S-D,10.1  \r\nES ,  49\r\n\r\n" +
            "Series,Shot,Speed   \r\n15, 1, 2721, ft/s   \r\n15, 2, 2722, ft/s   \r\n15, 3, 2745, ft/s   \r\n" +
            "----,----,----,---- \r\n\r\nSeries,16, Shots:,2\r\nSeries,Shot,Speed\r\n16, 1, 830.5, m/s\r\n16, 2, 0, m/s\r\n"
        val all = ChronographImport.parse(text.encodeToByteArray())
        assertEquals(2, all.size)
        assertEquals(Chronograph.MAGNETOSPEED, all[0].device)
        assertEquals("Series 15", all[0].title)
        assertEquals(SpeedUnit.FPS, all[0].unit)
        assertEquals(2721 * 0.3048, all[0].shots[0].velocityMps, 1e-9)
        assertEquals(listOf(830.5), all[1].shots.map { it.velocityMps })
    }

    @Test
    fun magnetoSpeedXfr() {
        val text = "Synced on:,2024-05-01 10:22\r\nSeries,2024-05-01, Shots:,2\r\nNotes,Varget 41.5\r\n" +
            "Shot,Speed,Unit\r\n1, 2801, ft/s\r\n2, 2795, ft/s\r\n----,----,----\r\n"
        val s = ChronographImport.parse(text.encodeToByteArray()).single()
        assertEquals("Varget 41.5", s.title)
        assertEquals("2024-05-01 10:22", s.date)
        assertEquals(listOf(1, 2), s.shots.map { it.number })
        assertEquals(2795 * 0.3048, s.shots[1].velocityMps, 1e-9)
    }

    // The cells of the first two sheets of a real Athlon Ballistics export (xeroconv's rangecraft_de.xlsx).
    private fun rangecraftSheet(name: String, shots: List<String>) = Sheet(
        name,
        listOf(
            listOf("Cartridge: N/A"), listOf("Zero distance,  "), listOf("Bullet weight, gr: 0"), listOf("Rifle: N/A"),
            listOf("Weather"), listOf("Temperature: , ℉: 0"), listOf("Rifle"),
            listOf("Projectile weight", "178.0 gr"), listOf("Average velocity", "778.6 MPS"), listOf("Standard deviation", "1.6 MPS"),
            listOf("Extreme spread", "3.5 MPS"), listOf("Session notes", ""),
            listOf("SHOT #", "SPEED (MPS)", "DEV/AVG (MPS)", "KE (J)", "POWER FACTOR", "TIME", "CLEAN BORE", "COLD BORE", "SHOT NOTES"),
        ) + shots.mapIndexed { i, v -> listOf("${i + 1}", v, "0.0", "3476.4", "453.4", "9:25 AM", "", "", "") },
    )

    @Test
    fun athlonRangecraftWorkbook() {
        val all = ChronographImport.parseSheets(
            listOf(
                rangecraftSheet("251108092125877", listOf("776.4", "779.9", "779.6")),
                rangecraftSheet("251108092742749", listOf("982.6", "985.4", "983.9")),
            ),
        )
        assertEquals(2, all.size)
        assertEquals(Chronograph.ATHLON_RANGECRAFT, all[0].device)
        assertEquals(SpeedUnit.MPS, all[0].unit)
        assertNull(all[0].title)
        assertEquals("2025-11-08 09:21", all[0].date)
        assertEquals(listOf(776.4, 779.9, 779.6), all[0].shots.map { it.velocityMps })
        assertEquals(1.6, VelocityStats.of(all[0].shots.map { it.velocityMps })!!.sd, 0.05)
    }

    @Test
    fun anyTableWithAVelocityColumn() {
        val s = ChronographImport.parse("Shot;Velocity (fps);Energy\n1;2701;2100\n2;2712;2110\n".encodeToByteArray()).single()
        assertEquals(Chronograph.OTHER, s.device)
        assertEquals(SpeedUnit.FPS, s.unit)
        assertEquals(false, s.unitGuessed)
        assertEquals(2712 * 0.3048, s.shots[1].velocityMps, 1e-9)

        val guessed = ChronographImport.parse("n,speed\n1,812.5\n2,815\n".encodeToByteArray()).single()
        assertEquals(SpeedUnit.MPS, guessed.unit)
        assertTrue(guessed.unitGuessed)
        assertEquals(815 * 0.3048, guessed.inUnit(SpeedUnit.FPS).shots[1].velocityMps, 1e-9)

        val inCells = ChronographImport.parse("Shot,Velocity\n1,2701 fps\n2,2712 fps\n".encodeToByteArray()).single()
        assertEquals(SpeedUnit.FPS, inCells.unit)
        assertEquals(2701 * 0.3048, inCells.shots[0].velocityMps, 1e-9)
    }

    private fun utf16le(s: String) = ByteArray(s.length * 2) { i -> if (i % 2 == 0) (s[i / 2].code and 0xFF).toByte() else (s[i / 2].code shr 8).toByte() }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val LABRADAR = listOf(
        "sep=;",
        "Device ID;LBR-0013797;;",
        "",
        "Series No;0007;;",
        "Total number of shots;0006;;",
        "",
        "Units velocity;fps;;",
        "Units distances;yd;;",
        "Units kinetic energy;ft-lbf;;",
        "Units weight;grain (gr);;",
        "",
        "Stats - Average;2745.37;fps;",
        "Stats - Highest;2788.14;fps;",
        "Stats - Lowest;2611.02;fps;",
        "Stats - Ext. Spread;177.12;fps;",
        "Stats - Std. Dev;66.52;fps;",
        "",
        "Shot ID;V0;V20;V40;V60;V80;V100;Ke0;Ke20;Ke40;Ke60;Ke80;Ke100;PF0;Date;Time",
        "0001;2770.20;2742.11;2714.27;2686.68;2659.34;2632.25;2420;2371;2323;2276;2230;2185;393;11-05-2026;09:12:31;",
        "0002;2788.14;2759.88;2731.87;2704.11;2676.60;2649.34;2452;2402;2353;2306;2259;2213;396;11-05-2026;09:13:02;",
        "0003;2761.53;2733.53;2705.78;2678.28;2651.03;2624.03;2405;2356;2309;2262;2216;2172;392;11-05-2026;09:13:40;",
        "0004;2779.62;2751.45;2723.53;2695.86;2668.44;2641.27;2437;2387;2339;2292;2245;2200;395;11-05-2026;09:14:15;",
        "0005;2611.02;2584.52;2558.28;2532.29;2506.56;2481.08;2150;2107;2064;2023;1982;1942;371;11-05-2026;09:14:51;",
        "0006;2761.71;2733.71;2705.96;2678.46;2651.21;2624.21;2406;2357;2309;2262;2216;2172;392;11-05-2026;09:15:30;",
    ).joinToString("\r\n")

    private val SHOTVIEW_IOS_EN = listOf(
        "\uFEFF\"105 hybrid 31.0gt\"",
        "#,SPEED (FPS),\u0394 AVG (FPS),KE (FT-LB),POWER FACTOR (KGR\u22C5FT/S),TIME,CLEAN BORE,COLD BORE,SHOT NOTES",
        "1, 2808.2, 13.9, , , 1:41:26\u202FPM, , , \"\" ",
        "2, 2790.7, -3.6, , , 1:41:52\u202FPM, , , \"\" ",
        "3, 2806.2, 12.0, , , 1:42:11\u202FPM, , , \"\" ",
        "4, 2800.3, 6.0, , , 1:42:33\u202FPM, , , \"\" ",
        "5, 2789.8, -4.5, , , 1:42:53\u202FPM, , , \"\" ",
        "6, 2790.1, -4.2, , , 1:43:09\u202FPM, , , \"\" ",
        "7, 2805.4, 11.1, , , 1:43:27\u202FPM, , , \"\" ",
        "8, 2778.0, -16.3, , , 1:43:41\u202FPM, , , \"\" ",
        "9, 2790.1, -4.2, , , 1:44:02\u202FPM, , , \"\" ",
        "10, 2789.1, -5.2, , , 1:44:18\u202FPM, , , \"\" ",
        "11, 2804.4, 10.1, , , 1:44:32\u202FPM, , , \"\" ",
        "12, 2795.1, 0.8, , , 1:47:08\u202FPM, , , \"\" ",
        "13, 2776.5, -17.8, , , 1:47:22\u202FPM, , , \"\" ",
        "14, 2797.0, 2.7, , , 1:48:06\u202FPM, , , \"\" ",
        "15, 2795.7, 1.4, , , 1:48:23\u202FPM, , , \"\" ",
        "16, 2786.9, -7.4, , , 1:48:43\u202FPM, , , \"\" ",
        "17, 2797.8, 3.5, , , 1:48:59\u202FPM, , , \"\" ",
        "18, 2793.0, -1.2, , , 1:49:17\u202FPM, , , \"\" ",
        "19, 2797.3, 3.0, , , 1:49:38\u202FPM, , , \"\" ",
        "-,,,,,,",
        "AVERAGE SPEED,2794.3,,,,,,,",
        "STD DEV,8.5,,,,,,,",
        "SPREAD,31.7,,,,,,,",
        "SESSION NOTE,\"\",,,,,,,",
        "-,,,,,,",
        "DATE, \"April 6, 2024 at 1:36\u202FPM\",,,,,",
        "All shots included in the calculations,,,,,,,\uFEFF",
    ).joinToString("\n")

    private val SHOTVIEW_IOS_DE = listOf(
        "\uFEFF\"55grs FOX - 23,30grs D073.4\"",
        "#,TEMPO (MPS),\u0394 DURCHSCHNITT (MPS),KE (J),LEISTUNGSFAKTOR (N\u22C5S),ZEIT,\u00D6LSCHUSS,ERSTSCHUSS,SCHUSSNOTIZEN",
        "1, 924.3, 5.7, 1522.4, 3.3, 10:08:59, , , \"\" ",
        "2, 918.7, 0.1, 1504.2, 3.3, 10:09:14, , , \"\" ",
        "3, 916.3, -2.3, 1496.2, 3.3, 10:09:29, , , \"\" ",
        "4, 915.0, -3.6, 1492.1, 3.3, 10:09:49, , , \"\" ",
        "-,,,,,,",
        "AVERAGE SPEED,918.6,,,,,,,",
        "STANDARDABWEICHUNG,3.5,,,,,,,",
        "STREUUNG,9.2,,,,,,,",
        "AVERAGE POWER FACTOR,3.3,,,,,,,",
        "GEWICHT DES PROJEKTILS(GRAN),55.0,,,,,,,",
        "TRAININGSNOTIZ,\"\",,,,,,,",
        "-,,,,,,",
        "DATUM, \"8. Februar 2025 um 10:08\",,,,,",
        "Alle Sch\u00FCsse sind in den Berechnungen enthalten,,,,,,,\uFEFF",
    ).joinToString("\n")

    private val SHOTVIEW_ANDROID_DE = listOf(
        "Marlin,.357, 15,3grs W296, 158,0 gr",
        "\uFEFF#,Tempo (MPS),\u0394 DURCHSCHNITT (MPS),KE (J),Leistungsfaktor (N\u22C5s),Zeit,\u00D6lschuss,Erstschuss,Schussnotizen",
        "1,\"488,3\",\"14,2\",\"1220,7\",\"5,0\",17:09:22,,,",
        "2,\"425,5\",\"-48,7\",\"926,7\",\"4,4\",17:09:27,,,",
        "3,\"477,0\",\"2,9\",\"1165,0\",\"4,9\",17:09:30,,,",
        "4,\"485,1\",\"11,0\",\"1204,9\",\"5,0\",17:09:34,,,",
        "5,\"476,8\",\"2,7\",\"1164,0\",\"4,9\",17:09:37,,,",
        "6,\"474,4\",\"0,3\",\"1152,1\",\"4,9\",17:10:04,,,",
        "7,\"470,7\",\"-3,5\",\"1134,0\",\"4,8\",17:10:08,,,",
        "8,\"474,8\",\"0,7\",\"1154,1\",\"4,9\",17:10:10,,,",
        "9,\"482,7\",\"8,5\",\"1192,7\",\"4,9\",17:10:13,,,",
        "10,\"486,0\",\"11,9\",\"1209,3\",\"5,0\",17:10:17,,,",
        "-,,,,,,",
        "AVERAGE SPEED,\"474,1\",,,,,",
        "AVERAGE POWER FACTOR,\"4,9\",,,,,",
        "STANDARDABWEICHUNG,\"17,1\",,,,,",
        "STREUUNG,\"62,8\",,,,,",
        "Gewicht des Projektils (GRAN),\"158.0\",,,,,",
        "Trainingsnotiz,,,,,,",
        "-,,,,,,",
        "Datum,\"JULI 02, 2024 17:08\",,,,,",
        "Alle Sch\u00FCsse sind in den Berechnungen enthalten,,,,,,",
        "",
    ).joinToString("\n")

    private val XERO_FIT_2024 = hex(
        "0e107a522a0100002e464954364a40000000000703048c04048607048601028402028405028400010000d88463cfe043" +
        "d440ffffffff0100d50fffff364100003100030214070002840101020100000000000000000000000000000000000000" +
        "004301ff420000170005fd048603048c02028404028405028402df43d440d88463cf0100d50f4301430000830107fd04" +
        "8600048601048602048605048603028404010003df43d440eedf03005d6c040059220400080700000a00024400008401" +
        "03fd0486000486010284041144d440af0c04000100041744d4406ae303000200041e44d440eedf03000300042244d440" +
        "280004000400043644d4409b2204000500043a44d4409d3804000600043d44d440294204000700044f44d440302d0400" +
        "0800045144d4405f5004000900045444d4405d6c04000a009890" +
            "",
    )

    private val XERO_FIT_2026 = hex(
        "0e109b52fa0000002e4649547e4540000000000703048c04048607048601028402028405028400010000ce6fd5cf6f43" +
        "dc44ffffffff0100d50fffff364100003100030214070002840101020100000000000000000000000000000000000000" +
        "005401ff420000170005fd048603048c020284040284050284026f43dc44ce6fd5cf0100d50f5401430000830108fd04" +
        "86000486010486020486050486060486030284040100036f43dc4404de0e001af60e00d7ea0e00000000000c0a000005" +
        "0001440000840103fd0486000486010284046744dc4436f50e000100047344dc44a3e00e000200048744dc443cec0e00" +
        "0300049544dc441af60e00040004a444dc4404de0e000500198d" +
            "",
    )

}
