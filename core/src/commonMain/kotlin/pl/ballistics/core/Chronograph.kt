package pl.ballistics.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Chronographs whose exported files can be read; [OTHER] is any table with a velocity column. */
enum class Chronograph { LABRADAR, GARMIN_XERO, ATHLON_RANGECRAFT, MAGNETOSPEED, OTHER }

/** Speed unit used in a chronograph file. */
enum class SpeedUnit(val label: String, val mps: Double) {
    MPS("m/s", 1.0),
    FPS("fps", Units.FOOT),
}

/** One recorded shot: its number in the series and its muzzle velocity. */
data class Shot(val number: Int, val velocityMps: Double)

/**
 * A series of shots read from a chronograph file.
 * @param title the session name or series number, if the file has one.
 * @param unit the unit the file was written in; [shots] are always in m/s.
 * @param unitGuessed the file does not say its unit, so it was guessed from the size of the numbers.
 * @param date when the series was shot, as written in the file.
 */
data class ShotSeries(
    val device: Chronograph,
    val title: String?,
    val unit: SpeedUnit,
    val shots: List<Shot>,
    val date: String? = null,
    val unitGuessed: Boolean = false,
) {
    /** The same numbers read in [other] unit; for a file whose unit was guessed wrong. */
    fun inUnit(other: SpeedUnit): ShotSeries = if (other == unit) this else
        copy(unit = other, shots = shots.map { it.copy(velocityMps = it.velocityMps / unit.mps * other.mps) })
}

/** A worksheet of a spreadsheet: its name and its cells, row by row, as text. */
data class Sheet(val name: String, val rows: List<List<String>>)

class ChronographFormatException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** Not a chronograph file that could be read. */
        NOT_RECOGNISED,
        /** A known format, but without any shot in it. */
        NO_SHOTS,
        /** A Labradar track file: the path of one shot, not the series; the report is next to it. */
        LABRADAR_TRACK,
    }
}

/**
 * Reads the muzzle velocities of shot series from chronograph files:
 * - Labradar: `SR0001 Report.csv` from the SD card (semicolon separated, NUL padded or UTF-16);
 * - Labradar LX: the series CSV of the device or its app (';' or ',' separated);
 * - Garmin Xero C1: the CSV that the ShotView app exports, in any of its languages, and the
 *   session FIT file copied from the chronograph over USB;
 * - Athlon Rangecraft Velocity Pro: the Excel workbook of the Athlon Ballistics app, one
 *   worksheet per session (unzipped by the caller into [Sheet]s, see [parseSheets]);
 * - MagnetoSpeed V3 and Sporter: the device's CSV and the XFR app's export, several series per file;
 * - anything else: a table with a column named velocity, speed or V0.
 */
object ChronographImport {
    fun parse(bytes: ByteArray): List<ShotSeries> {
        if (FitShots.isFit(bytes)) {
            val shots = FitShots.read(bytes)
            if (shots.isEmpty()) throw ChronographFormatException(ChronographFormatException.Reason.NO_SHOTS)
            return listOf(ShotSeries(Chronograph.GARMIN_XERO, null, SpeedUnit.MPS, shots))
        }
        return parseText(decodeText(bytes))
    }

    /** A zip file: a spreadsheet to unzip into [Sheet]s for [parseSheets]. */
    fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    fun parseText(text: String): List<ShotSeries> {
        val lines = text.replace("﻿", "").replace("\u0000", "").lines()
        val trimmed = lines.map { it.trim() }
        val series = when {
            trimmed.any { it.startsWith("Time (s);") || it.startsWith("Time (s),") } ->
                throw ChronographFormatException(ChronographFormatException.Reason.LABRADAR_TRACK)
            trimmed.any { it.startsWith("Shot ID;") || it.startsWith("Units velocity;") } -> listOf(labradar(lines, ';'))
            trimmed.any { it.startsWith("Shot Number,") } -> listOf(labradar(lines, ','))
            lines.any { csvFields(it).firstOrNull() == "#" } -> listOf(shotView(lines))
            trimmed.any { it.startsWith("Synced on:") || MAGNETO_SERIES.containsMatchIn(it) } -> magnetoSpeed(lines)
            else -> listOf(generic(lines) ?: throw ChronographFormatException(ChronographFormatException.Reason.NOT_RECOGNISED))
        }.filter { it.shots.isNotEmpty() }
        if (series.isEmpty()) throw ChronographFormatException(ChronographFormatException.Reason.NO_SHOTS)
        return series
    }

    /** Sessions of an Athlon Rangecraft workbook, or of any spreadsheet with a velocity column. */
    fun parseSheets(sheets: List<Sheet>): List<ShotSeries> {
        val series = sheets.mapNotNull { sheet ->
            val rows = sheet.rows.map { r -> r.map { it.trim() } }
            val headerAt = rows.indexOfFirst { r -> r.firstOrNull()?.uppercase()?.startsWith("SHOT") == true && r.getOrNull(1)?.let(::speedUnit) != null }
            if (headerAt >= 0) {
                val unit = speedUnit(rows[headerAt][1])!!
                val shots = tableShots(rows, headerAt, 1, unit)
                ShotSeries(Chronograph.ATHLON_RANGECRAFT, rangecraftTitle(rows), unit, shots, sheetDate(sheet.name))
            } else {
                genericRows(rows)?.copy(title = sheet.name)
            }
        }.filter { it.shots.isNotEmpty() }
        if (series.isEmpty()) throw ChronographFormatException(ChronographFormatException.Reason.NOT_RECOGNISED)
        return series
    }

    private val MAGNETO_SERIES = Regex("""^Series\s*,\s*[^,]*,\s*Shots:""", RegexOption.IGNORE_CASE)

    /**
     * The Labradar report and the LX's file: a preamble ("Series No", "Units velocity", "Stats - Average")
     * and a table whose V0 column is the muzzle velocity. The LX gives the unit in a row under the
     * table header (";m/s;m/s;j;...") and may have no "Units velocity" line.
     */
    private fun labradar(lines: List<String>, separator: Char): ShotSeries {
        val rows = lines.map { line -> line.split(separator).map { it.trim() } }
        var units: SpeedUnit? = null
        var statsUnit: SpeedUnit? = null
        var rowUnit: SpeedUnit? = null
        var name: String? = null
        var v0 = -1
        var dateAt = -1
        var date: String? = null
        val raw = mutableListOf<Pair<Int, Double>>()
        for (f in rows) {
            val first = f.firstOrNull() ?: continue
            if (v0 < 0) {
                when {
                    first == "Series No" || first == "Series Number" -> name = f.getOrNull(1)?.takeIf { it.isNotEmpty() }
                    first == "Units velocity" -> units = speedUnit(f.getOrNull(1).orEmpty())
                    first.startsWith("Stats - ") && statsUnit == null -> statsUnit = f.drop(2).lastOrNull { it.isNotEmpty() }?.let(::speedUnit)
                    first == "Shot ID" || first == "Shot Number" -> {
                        v0 = f.indexOfFirst { it == "V0" || it.startsWith("V0.") }.takeIf { it > 0 } ?: 1
                        dateAt = f.indexOf("Date")
                    }
                }
                continue
            }
            val number = first.toIntOrNull()
            if (number == null) {
                if (first.isEmpty() && rowUnit == null) rowUnit = f.getOrNull(v0)?.let(::speedUnit)
                continue
            }
            val v = f.getOrNull(v0)?.let(::number)?.takeIf { it > 0 } ?: continue
            raw += number to v
            if (date == null && dateAt > 0) date = f.getOrNull(dateAt)?.takeIf { it.isNotEmpty() }
        }
        val known = units ?: rowUnit ?: statsUnit
        val unit = known ?: guessUnit(raw.map { it.second })
        val title = name?.let { if (it.all(Char::isDigit)) "SR$it" else it }
        return ShotSeries(Chronograph.LABRADAR, title, unit, raw.map { Shot(it.first, it.second * unit.mps) }, date, known == null)
    }

    /**
     * ShotView's export: the session name, a header row starting with "#" whose speed column
     * names the unit ("SPEED (FPS)", "TEMPO (MPS)"), one row per shot, then "-" and the summary,
     * another "-" and the date. Labels are translated, so only the layout is relied on.
     */
    private fun shotView(lines: List<String>): ShotSeries {
        val rows = lines.map(::csvFields)
        val headerAt = rows.indexOfFirst { it.firstOrNull() == "#" }
        val known = rows[headerAt].getOrNull(1)?.let(::speedUnit)
        val title = lines.take(headerAt).map { it.trim().removeSurrounding("\"").trim() }.firstOrNull { it.isNotEmpty() }
        val unit = known ?: guessUnit(tableShots(rows, headerAt, 1, SpeedUnit.MPS).map { it.velocityMps })
        val shots = tableShots(rows, headerAt, 1, unit)
        val afterShots = rows.drop(headerAt + 1)
        val secondDash = afterShots.withIndex().filter { it.value.firstOrNull() == "-" }.getOrNull(1)?.index
        val date = secondDash?.let { afterShots.getOrNull(it + 1)?.getOrNull(1) }?.takeIf { it.isNotEmpty() }
        return ShotSeries(Chronograph.GARMIN_XERO, title, unit, shots, date, known == null)
    }

    /** Rows after [headerAt] with a whole number first and a speed in column [col], until the table ends. */
    private fun tableShots(rows: List<List<String>>, headerAt: Int, col: Int, unit: SpeedUnit): List<Shot> {
        val shots = mutableListOf<Shot>()
        for (row in rows.drop(headerAt + 1)) {
            val first = row.firstOrNull() ?: continue
            if (first.isEmpty() && row.all { it.isEmpty() }) continue
            val number = first.toIntOrNull() ?: break
            val v = row.getOrNull(col)?.let(::number)?.takeIf { it > 0 } ?: continue
            shots += Shot(number, v * unit.mps)
        }
        return shots
    }

    /** The "Cartridge: ..." line of a Rangecraft sheet, unless it is "N/A". */
    private fun rangecraftTitle(rows: List<List<String>>): String? = rows.firstNotNullOfOrNull { r ->
        r.firstOrNull()?.takeIf { it.startsWith("Cartridge:") }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() && it != "N/A" }
    }

    /** Rangecraft names a session's sheet by when it started: "251108092125877" is 2025-11-08 09:21. */
    private fun sheetDate(name: String): String? {
        if (name.length < 10 || !name.all(Char::isDigit)) return null
        return "20${name.substring(0, 2)}-${name.substring(2, 4)}-${name.substring(4, 6)} ${name.substring(6, 8)}:${name.substring(8, 10)}"
    }

    /**
     * MagnetoSpeed V3: "Series,15, Shots:,3", statistics, then "15, 1, 2721, ft/s" per shot (series,
     * shot, speed, unit) and "----" at the end of the series. The XFR app's export writes "Synced on:"
     * and "Notes" lines and shots as "1, 2721, ft/s". A speed of 0 is a missed shot.
     */
    private fun magnetoSpeed(lines: List<String>): List<ShotSeries> {
        val all = mutableListOf<ShotSeries>()
        var name: String? = null
        var notes: String? = null
        var date: String? = null
        var unit: SpeedUnit? = null
        var shots = mutableListOf<Shot>()
        fun close() {
            if (shots.isNotEmpty()) {
                all += ShotSeries(Chronograph.MAGNETOSPEED, notes ?: name, unit ?: SpeedUnit.FPS, shots, date)
            }
            name = null; notes = null; date = null; unit = null; shots = mutableListOf()
        }
        for (line in lines) {
            val f = line.split(',').map { it.trim() }
            val first = f.firstOrNull() ?: continue
            when {
                first.startsWith("----") -> close()
                first.equals("Series", true) && f.getOrNull(2).equals("Shots:", true) -> {
                    if (shots.isNotEmpty()) close()
                    name = f.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { if (it.all(Char::isDigit)) "Series $it" else it }
                }
                first == "Synced on:" -> date = f.getOrNull(1)?.takeIf { it.isNotEmpty() }
                first == "Notes" -> notes = f.getOrNull(1)?.takeIf { it.isNotEmpty() }
                first.toIntOrNull() != null -> {
                    val v3 = f.size >= 4 && f[1].toIntOrNull() != null && speedUnit(f[3]) != null
                    val (shotAt, speedAt, unitAt) = if (v3) Triple(1, 2, 3) else Triple(0, 1, 2)
                    val u = f.getOrNull(unitAt)?.let(::speedUnit) ?: continue
                    val v = f.getOrNull(speedAt)?.let(::number)?.takeIf { it > 0 } ?: continue
                    unit = unit ?: u
                    shots += Shot(f[shotAt].toInt(), v * u.mps)
                }
            }
        }
        close()
        return all
    }

    /** Any CSV with a header naming a velocity column. */
    private fun generic(lines: List<String>): ShotSeries? {
        val nonEmpty = lines.filter { it.isNotBlank() }
        val semicolons = nonEmpty.sumOf { l -> l.count { it == ';' } }
        val commas = nonEmpty.sumOf { l -> l.count { it == ',' } }
        val tabs = nonEmpty.sumOf { l -> l.count { it == '\t' } }
        val rows = when {
            tabs > semicolons && tabs > commas -> lines.map { l -> l.split('\t').map { it.trim().removeSurrounding("\"") } }
            semicolons > commas -> lines.map { l -> l.split(';').map { it.trim().removeSurrounding("\"") } }
            else -> lines.map(::csvFields)
        }
        return genericRows(rows)
    }

    private val VELOCITY_HEADER = Regex("""velocity|speed|\bvel\b|^v0\b|^v\s*\(|^mv\b""", RegexOption.IGNORE_CASE)

    private fun genericRows(rows: List<List<String>>): ShotSeries? {
        val headerAt = rows.indexOfFirst { r -> r.any { VELOCITY_HEADER.containsMatchIn(it) } && r.none { number(it) != null } }
        if (headerAt < 0) return null
        val header = rows[headerAt]
        val col = header.indexOfFirst { VELOCITY_HEADER.containsMatchIn(it) }
        val unitCol = header.indexOfFirst { it.equals("unit", true) || it.equals("units", true) }
        val raw = mutableListOf<Pair<Int, Double>>()
        var unitInCells: SpeedUnit? = null
        for ((i, row) in rows.drop(headerAt + 1).withIndex()) {
            val cell = row.getOrNull(col) ?: continue
            // "2721 fps" in one cell, or the unit in a column of its own.
            val unitHere = speedUnit(cell.filter { !it.isDigit() && it != '.' && it != ',' }.trim()).takeIf { cell.any(Char::isLetter) }
                ?: row.getOrNull(unitCol)?.let(::speedUnit)
            val v = number(cell.takeWhile { it.isDigit() || it == '.' || it == ',' || it == ' ' }) ?: continue
            if (v <= 0) continue
            unitInCells = unitInCells ?: unitHere
            val n = row.firstOrNull()?.toIntOrNull()?.takeIf { col != 0 } ?: (raw.size + 1)
            raw += n to v
        }
        if (raw.isEmpty()) return null
        val known = speedUnit(header[col]) ?: unitInCells
        val unit = known ?: guessUnit(raw.map { it.second })
        return ShotSeries(Chronograph.OTHER, null, unit, raw.map { Shot(it.first, it.second * unit.mps) }, null, known == null)
    }

    /** Muzzle velocities over 1300 can only be fps (that is Mach 3.8 in m/s); below, m/s is more likely for rifles. */
    private fun guessUnit(values: List<Double>): SpeedUnit {
        val median = values.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        return if (median > 1300) SpeedUnit.FPS else SpeedUnit.MPS
    }

    private fun speedUnit(text: String): SpeedUnit? {
        val t = text.uppercase()
        return when {
            "FPS" in t || "FT/S" in t -> SpeedUnit.FPS
            "MPS" in t || "M/S" in t -> SpeedUnit.MPS
            else -> null
        }
    }

    /** A number written with a decimal point or comma, possibly with the other one grouping thousands. */
    internal fun number(text: String): Double? {
        val t = text.trim().replace(" ", "")
        val dot = t.lastIndexOf('.')
        val comma = t.lastIndexOf(',')
        val normal = when {
            dot >= 0 && comma >= 0 && comma > dot -> t.replace(".", "").replace(',', '.')
            dot >= 0 && comma >= 0 -> t.replace(",", "")
            else -> t.replace(',', '.')
        }
        return normal.toDoubleOrNull()
    }

    /** Fields of one CSV line, trimmed, with quotes removed ("488,3" is one field). */
    internal fun csvFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { fields += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        fields += sb.toString().trim()
        return if (fields.size == 1 && fields[0].isEmpty()) emptyList() else fields
    }

    /**
     * UTF-8, or UTF-16 (with or without a byte order mark). Labradar pads its lines with NUL bytes,
     * which fall on both odd and even positions; UTF-16 text has its zeros on one side only.
     */
    internal fun decodeText(b: ByteArray): String {
        fun utf16(from: Int, little: Boolean): String {
            val chars = CharArray((b.size - from) / 2) { i ->
                val lo = b[from + 2 * i].toInt() and 0xFF
                val hi = b[from + 2 * i + 1].toInt() and 0xFF
                (if (little) lo or (hi shl 8) else (lo shl 8) or hi).toChar()
            }
            return chars.concatToString()
        }
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return utf16(2, true)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return utf16(2, false)
        val head = b.take(400)
        val half = head.size / 2
        val zerosOdd = head.filterIndexed { i, _ -> i % 2 == 1 }.count { it == 0.toByte() }
        val zerosEven = head.filterIndexed { i, _ -> i % 2 == 0 }.count { it == 0.toByte() }
        return when {
            head.size >= 8 && zerosOdd > half * 0.6 && zerosEven < half * 0.1 -> utf16(0, true)
            head.size >= 8 && zerosEven > half * 0.6 && zerosOdd < half * 0.1 -> utf16(0, false)
            else -> b.decodeToString()
        }
    }
}

/**
 * The few bits of the FIT format needed for the shots of a Garmin Xero session:
 * message 388 (chrono_shot_data) holds the shot speed in mm/s (field 0) and the shot number (field 1).
 */
internal object FitShots {
    private const val CHRONO_SHOT_DATA = 388

    private class Field(val number: Int, val size: Int)
    private class Definition(val global: Int, val bigEndian: Boolean, val fields: List<Field>, val developerBytes: Int)

    fun isFit(b: ByteArray) = b.size >= 12 && b[8] == '.'.code.toByte() && b[9] == 'F'.code.toByte() &&
        b[10] == 'I'.code.toByte() && b[11] == 'T'.code.toByte()

    private class Truncated : Exception()

    /** The shots of the file; one cut short gives the shots before the cut. */
    fun read(b: ByteArray): List<Shot> {
        val shots = mutableListOf<Shot>()
        try {
            decode(b, shots)
        } catch (e: Truncated) {
            // Keep what was read.
        }
        return shots
    }

    private fun decode(b: ByteArray, shots: MutableList<Shot>) {
        // Checked by hand: Kotlin/JS does not check array bounds.
        fun u8(p: Int) = if (p in b.indices) b[p].toInt() and 0xFF else throw Truncated()
        fun uint(p: Int, size: Int, big: Boolean): Long {
            var v = 0L
            for (i in 0 until size) {
                val byte = u8(if (big) p + i else p + size - 1 - i).toLong()
                v = (v shl 8) or byte
            }
            return v
        }
        val headerSize = u8(0)
        val dataSize = uint(4, 4, false).toInt()
        val end = minOf(headerSize + dataSize, b.size)
        val definitions = arrayOfNulls<Definition>(16)
        var p = headerSize
        while (p < end) {
            val h = u8(p++)
            val local = when {
                h and 0x80 != 0 -> (h shr 5) and 0x03 // compressed timestamp header
                h and 0x40 != 0 -> {
                    val big = u8(p + 1) == 1
                    val global = uint(p + 2, 2, big).toInt()
                    val count = u8(p + 4)
                    p += 5
                    val fields = List(count) { i -> Field(u8(p + 3 * i), u8(p + 3 * i + 1)) }
                    p += 3 * count
                    var developer = 0
                    if (h and 0x20 != 0) {
                        val n = u8(p++)
                        repeat(n) { i -> developer += u8(p + 3 * i + 1) }
                        p += 3 * n
                    }
                    definitions[h and 0x0F] = Definition(global, big, fields, developer)
                    continue
                }
                else -> h and 0x0F
            }
            val d = definitions[local] ?: throw ChronographFormatException(ChronographFormatException.Reason.NOT_RECOGNISED)
            var speed: Long? = null
            var number: Long? = null
            for (f in d.fields) {
                if (d.global == CHRONO_SHOT_DATA && f.size in 1..4) {
                    val v = uint(p, f.size, d.bigEndian)
                    val invalid = v == (1L shl (8 * f.size)) - 1
                    if (!invalid && f.number == 0) speed = v
                    if (!invalid && f.number == 1) number = v
                }
                p += f.size
            }
            p += d.developerBytes
            if (p > end) throw Truncated()
            if (speed != null) shots += Shot(number?.toInt() ?: (shots.size + 1), speed / 1000.0)
        }
    }
}

/**
 * Average, standard deviation and extreme spread of muzzle velocities. The SD is the
 * population one (divided by n), as the Garmin Xero computes it, so the numbers match
 * what the chronograph showed at the range.
 */
data class VelocityStats(val count: Int, val mean: Double, val sd: Double, val es: Double, val min: Double, val max: Double) {
    companion object {
        fun of(velocities: List<Double>): VelocityStats? {
            if (velocities.isEmpty()) return null
            val n = velocities.size
            val mean = velocities.sum() / n
            val sd = sqrt(velocities.sumOf { (it - mean) * (it - mean) } / n)
            val min = velocities.min()
            val max = velocities.max()
            return VelocityStats(n, mean, sd, max - min, min, max)
        }

        /**
         * Shots that are most likely misreads: further than [k] robust standard deviations
         * (1.4826 × the median absolute deviation) from the median. A few bad readings move
         * neither the median nor the MAD, so they cannot hide each other as they would behind
         * the mean and SD. The spread is taken as at least 0.25 % of the median, so that a
         * series of near-identical shots does not throw out a perfectly good one. Fewer than
         * [minShots] shots are too few to tell.
         */
        fun outliers(velocities: List<Double>, k: Double = 3.0, minShots: Int = 5): Set<Int> {
            if (velocities.size < minShots) return emptySet()
            val median = median(velocities)
            val mad = median(velocities.map { abs(it - median) })
            val sigma = max(1.4826 * mad, 0.0025 * median)
            return velocities.indices.filter { abs(velocities[it] - median) > k * sigma }.toSet()
        }

        private fun median(v: List<Double>): Double {
            val s = v.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }
    }
}
