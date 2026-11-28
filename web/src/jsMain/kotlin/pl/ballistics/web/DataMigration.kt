package pl.ballistics.web

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The same rewrite as the Android app (app/.../data/DataMigration.kt), for data saved in this browser or imported: it rewrites older formats
 * into the current fields. Each step looks at which keys are there, not at `version`, because an older app that
 * read a newer copy writes its own fields back under the newer version number.
 *
 * Version 2 replaced fields added side by side so older apps could still read them:
 * - rifle `angularUnit` + `inchMoa` → `turretUnit` (MRAD, MOA, SMOA);
 * - conditions `windClock` → `windFromDeg` (degrees, never missing);
 * - settings `garminWatch` + `phoneBarometer` → `pressureSource`; `locationSetsAltitude` dropped;
 *   `altitudeSource` MANUAL (typed in) or missing → TERRAIN;
 * - load `powderSensitivity` 0 meant "the standard value" and is now 0 m/s per °C, so a 0 saved before version 2
 *   becomes blank (the standard). This one step goes by `version`, as no key tells the two meanings apart.
 */
object DataMigration {
    fun migrate(root: JsonElement): JsonElement {
        if (root !is JsonObject) return root
        val out = root.toMutableMap()
        (root["weapons"] as? JsonArray)?.let { list -> out["weapons"] = JsonArray(list.map { (it as? JsonObject)?.let(::weapon) ?: it }) }
        val version = (root["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 1
        if (version < 2) (root["ammo"] as? JsonArray)?.let { list -> out["ammo"] = JsonArray(list.map { (it as? JsonObject)?.let(::ammoV1) ?: it }) }
        (root["conditions"] as? JsonObject)?.let { out["conditions"] = conditions(it) }
        (root["ui"] as? JsonObject)?.let { out["ui"] = ui(it) }
        return JsonObject(out)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    internal fun weapon(o: JsonObject): JsonObject {
        if ("angularUnit" !in o && "inchMoa" !in o) return o
        val out = o.toMutableMap()
        out.remove("angularUnit")
        out.remove("inchMoa")
        if ("turretUnit" !in o) {
            val unit = when {
                o.string("angularUnit") != "MOA" -> TurretUnit.MRAD
                o.bool("inchMoa") == true -> TurretUnit.SMOA
                else -> TurretUnit.MOA
            }
            out["turretUnit"] = JsonPrimitive(unit.name)
        }
        return JsonObject(out)
    }

    internal fun ammoV1(o: JsonObject): JsonObject {
        val zero = o.string("powderSensitivity")?.replace(',', '.')?.trim()?.toDoubleOrNull() == 0.0
        return if (zero) JsonObject(o + ("powderSensitivity" to JsonPrimitive(""))) else o
    }

    internal fun conditions(o: JsonObject): JsonObject {
        if ("windClock" !in o && o.string("windFromDeg") != null) return o
        val out = o.toMutableMap()
        out.remove("windClock")
        if (o.string("windFromDeg") == null) {
            val clock = o.string("windClock") ?: "3"
            val deg = clock.replace(',', '.').trim().toDoubleOrNull()?.let { it * 30 }
            out["windFromDeg"] = JsonPrimitive(deg?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "90")
        }
        return JsonObject(out)
    }

    internal fun ui(o: JsonObject): JsonObject {
        val legacy = listOf("garminWatch", "phoneBarometer", "locationSetsAltitude").any { it in o }
        val altitude = o.string("altitudeSource")
        if (!legacy && altitude != null && altitude != "MANUAL") return o
        val out = o.toMutableMap()
        if ("pressureSource" !in o) {
            val source = when {
                o.bool("garminWatch") == true -> PressureSource.GARMIN
                o.bool("phoneBarometer") == true -> PressureSource.PHONE
                else -> PressureSource.NONE
            }
            out["pressureSource"] = JsonPrimitive(source.name)
        }
        out.remove("garminWatch")
        out.remove("phoneBarometer")
        out.remove("locationSetsAltitude")
        if (altitude == null || altitude == "MANUAL") out["altitudeSource"] = JsonPrimitive("TERRAIN")
        return JsonObject(out)
    }
}
