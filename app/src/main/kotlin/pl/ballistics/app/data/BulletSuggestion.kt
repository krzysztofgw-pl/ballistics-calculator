package pl.ballistics.app.data

import java.util.Locale

/**
 * A bullet proposed for the community database. It goes to Firestore
 * `submissions`, and a daily job in krzysztofgw-pl/ballistics-bullets turns it
 * into a pull request that a maintainer reviews. Field limits match
 * `firestore.rules` and the database's validator.
 */
data class BulletSuggestion(
    val maker: String,
    val name: String,
    val diameterIn: Double,
    val weightGr: Double,
    val g1: Double?,
    val g7: Double?,
    val lengthIn: Double?,
    val twistIn: Double?,
    val sourceType: SourceType,
    val sourceRef: String,
    val note: String,
    /** Id of the database bullet this corrects; null for a new bullet. */
    val replaces: String?,
) {
    enum class SourceType(val key: String) { WEBSITE("website"), CATALOGUE("catalogue"), BOX("box"), DOPPLER("doppler") }

    /** Reasons this cannot be sent, as keys of [Problem]; empty when it can. */
    fun problems(): List<Problem> = buildList {
        if (maker.trim().length !in 1..MAX_MAKER) add(Problem.MAKER)
        if (name.trim().length !in 1..MAX_NAME) add(Problem.NAME)
        if (diameterIn !in 0.1..0.75) add(Problem.DIAMETER)
        if (weightGr !in 10.0..1000.0) add(Problem.WEIGHT)
        if (g1 == null && g7 == null) add(Problem.BC)
        if (listOfNotNull(g1, g7).any { it !in 0.02..1.3 }) add(Problem.BC)
        if (lengthIn != null && lengthIn !in 2 * diameterIn..7 * diameterIn) add(Problem.LENGTH)
        if (twistIn != null && twistIn !in 5.0..20.0) add(Problem.TWIST)
        if (sourceRef.trim().length !in MIN_REF..MAX_REF) add(Problem.SOURCE)
        if (note.length > MAX_NOTE) add(Problem.NOTE)
    }.distinct()

    enum class Problem { MAKER, NAME, DIAMETER, WEIGHT, BC, LENGTH, TWIST, SOURCE, NOTE }

    /** The Firestore document, without the fields the app adds when sending (uid, time, status). */
    fun toMap(): Map<String, Any> = buildMap {
        put("maker", maker.trim())
        put("name", name.trim())
        put("diameter_in", round(diameterIn, 4))
        put("weight_gr", round(weightGr, 1))
        put("bc", buildMap { g1?.let { put("g1", round(it, 3)) }; g7?.let { put("g7", round(it, 3)) } })
        lengthIn?.let { put("length_in", round(it, 3)) }
        twistIn?.let { put("twist_in", round(it, 2)) }
        put("source", mapOf("type" to sourceType.key, "ref" to sourceRef.trim()))
        if (note.isNotBlank()) put("note", note.trim())
        replaces?.let { put("replaces", it) }
    }

    companion object {
        const val MAX_MAKER = 40
        const val MAX_NAME = 60
        const val MIN_REF = 3
        const val MAX_REF = 300
        const val MAX_NOTE = 500

        private fun round(v: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v).toDouble()

        /**
         * Starts a suggestion from a load. A load picked from the library keeps the
         * bullet's maker and name and becomes a correction of it.
         */
        fun from(ammo: AmmoProfile, library: List<Bullet>): BulletSuggestion {
            fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
            val diameterIn = (num(ammo.diameterMm) ?: 0.0) / 25.4
            val weight = num(ammo.massGr) ?: 0.0
            val match = library.firstOrNull { ammo.name.contains(it.label) }
                ?: library.firstOrNull { ammo.name.endsWith("${it.name} ${fmt(it.massGr)} gr") && Math.abs(it.diameterIn - diameterIn) < 0.002 }
            val bcs = ammo.bcByModel + (ammo.dragType to ammo.bc)
            return BulletSuggestion(
                maker = match?.maker.orEmpty(),
                name = match?.name ?: ammo.name,
                diameterIn = match?.diameterIn ?: round(diameterIn, 3),
                weightGr = weight,
                g1 = bcs[DragType.G1]?.let(::num),
                g7 = bcs[DragType.G7]?.let(::num),
                lengthIn = num(ammo.lengthMm)?.div(25.4)?.let { round(it, 3) },
                twistIn = match?.twistIn,
                sourceType = SourceType.WEBSITE,
                sourceRef = "",
                note = "",
                replaces = match?.id?.takeIf { it.isNotEmpty() },
            )
        }

        private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
    }
}
