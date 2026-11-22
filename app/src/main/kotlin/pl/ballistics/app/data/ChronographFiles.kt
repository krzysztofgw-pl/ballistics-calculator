package pl.ballistics.app.data

import android.content.Context
import android.net.Uri
import org.w3c.dom.Element
import pl.ballistics.core.ChronographFormatException
import pl.ballistics.core.ChronographImport
import pl.ballistics.core.Sheet
import pl.ballistics.core.ShotSeries
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Chronograph files opened from the file picker or shared by another app (ShotView, Athlon, a file manager). */
object ChronographFiles {
    /** A series is a few kilobytes; a FIT activity or a workbook with many sessions is under a megabyte. */
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun load(context: Context, uri: Uri): List<ShotSeries> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimited() }
            ?: throw ChronographFormatException(ChronographFormatException.Reason.NOT_RECOGNISED)
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): List<ShotSeries> =
        if (ChronographImport.isZip(bytes)) ChronographImport.parseSheets(Xlsx.sheets(bytes)) else ChronographImport.parse(bytes)

    private fun InputStream.readLimited(): ByteArray {
        val bytes = readUpTo(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) throw ChronographFormatException(ChronographFormatException.Reason.NOT_RECOGNISED)
        return bytes
    }
}

/** At most [limit] bytes (InputStream.readNBytes needs API 33). */
internal fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** The cells of an Excel workbook (.xlsx) as text, sheet by sheet in the workbook's order. */
internal object Xlsx {
    private const val MAX_ENTRY = 16 * 1024 * 1024

    fun sheets(bytes: ByteArray): List<Sheet> {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.removePrefix("/")
                if (name.startsWith("xl/") && name.endsWith(".xml") || name.endsWith(".rels")) {
                    val data = zip.readUpTo(MAX_ENTRY + 1)
                    if (data.size <= MAX_ENTRY) files[name] = data
                }
            }
        }
        val workbook = files["xl/workbook.xml"]?.let(::xml)
            ?: throw ChronographFormatException(ChronographFormatException.Reason.NOT_RECOGNISED)
        val targets = files["xl/_rels/workbook.xml.rels"]?.let(::xml)?.elements("Relationship")
            ?.associate { it.getAttribute("Id") to it.getAttribute("Target") }.orEmpty()
        val strings = files["xl/sharedStrings.xml"]?.let(::xml)?.elements("si")?.map { it.textContent }.orEmpty()
        return workbook.elements("sheet").mapNotNull { s ->
            val id = s.getAttributeNS(REL_NS, "id").ifEmpty { s.getAttribute("r:id") }
            val target = targets[id]?.removePrefix("/")?.let { if (it.startsWith("xl/")) it else "xl/$it" } ?: return@mapNotNull null
            val sheet = files[target]?.let(::xml) ?: return@mapNotNull null
            Sheet(s.getAttribute("name"), rows(sheet, strings))
        }
    }

    private fun rows(sheet: Element, strings: List<String>): List<List<String>> = sheet.elements("row").map { row ->
        val cells = sortedMapOf<Int, String>()
        row.elements("c").forEachIndexed { i, c ->
            val column = column(c.getAttribute("r")) ?: i
            val value = c.elements("v").firstOrNull()?.textContent.orEmpty()
            cells[column] = when (c.getAttribute("t")) {
                "s" -> value.toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
                "inlineStr" -> c.elements("is").firstOrNull()?.textContent.orEmpty()
                else -> value
            }
        }
        val width = (cells.keys.maxOrNull() ?: -1) + 1
        List(width) { cells[it].orEmpty() }
    }

    /** "B29" is column 1. */
    private fun column(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return null
        return letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
    }

    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun xml(bytes: ByteArray): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // A workbook from anywhere: no external entities or DTDs.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
    }

    private fun Element.elements(localName: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", localName)
        return List(nodes.length) { nodes.item(it) as Element }
    }
}
