package pl.ballistics.app.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Stores [AppData] as JSON in SharedPreferences, which Android Auto Backup
 * copies to the user's Google account and restores on a new phone.
 */
class Repository(context: Context) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    fun load(): AppData =
        prefs.getString(KEY_DATA, null)?.let { decode(it) }
            ?: prefs.getString(KEY_LEGACY, null)?.let { migrateLegacy(it) }
            ?: AppData.sample()

    fun save(data: AppData) {
        prefs.edit().putString(KEY_DATA, encode(data)).remove(KEY_LEGACY).apply()
    }

    companion object {
        private const val KEY_DATA = "data"
        private const val KEY_LEGACY = "inputs"

        private val json = Json {
            ignoreUnknownKeys = true
            // An enum value this version doesn't know (from a newer app) reads as the field's default instead of failing the whole copy.
            coerceInputValues = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun encode(data: AppData): String = json.encodeToString(AppData.serializer(), data)

        /** Reads data from this phone, the cloud or an export file, older formats included; null when it can't be read. */
        fun decode(text: String): AppData? = runCatching {
            json.decodeFromJsonElement(AppData.serializer(), DataMigration.migrate(json.parseToJsonElement(text))).copy(version = AppData.CURRENT_VERSION)
        }.getOrNull()

        /** Version 0.1 kept a single flat form; split it into one rifle and one load. */
        private fun migrateLegacy(text: String): AppData? = runCatching {
            val o = json.parseToJsonElement(text) as JsonObject
            fun s(key: String, default: String) = o[key]?.jsonPrimitive?.contentOrNull ?: default
            fun b(key: String, default: Boolean) = o[key]?.jsonPrimitive?.booleanOrNull ?: default
            val ammo = AmmoProfile(
                name = "My load",
                dragType = runCatching { DragType.valueOf(s("dragType", "G7")) }.getOrDefault(DragType.G7),
                bc = s("bc", "0.243"),
                customCurve = s("customCurve", ""),
                massGr = s("massGr", "175"),
                diameterMm = s("diameterMm", "7.82"),
                lengthMm = s("lengthMm", ""),
                muzzleVelocity = s("muzzleVelocity", "790"),
                powderSensitivity = s("powderSensitivity", "").takeIf { it.toDoubleOrNull() != 0.0 }.orEmpty(),
                powderReferenceTemp = s("powderReferenceTemp", "15"),
            )
            val weapon = WeaponProfile(
                name = "My rifle",
                twistIn = s("twistIn", "10"),
                rightHandTwist = b("rightHandTwist", true),
                turretUnit = if (s("angularUnit", "MRAD") == "MOA") TurretUnit.MOA else TurretUnit.MRAD,
                clickValue = s("clickValue", "0.1"),
                sightHeightCm = s("sightHeightCm", "4.5"),
                zeroRangeM = s("zeroRangeM", "100"),
                zeroTemperatureC = s("zeroTemperatureC", "15"),
                zeroPressureHpa = s("zeroPressureHpa", "1013.25"),
                ammoId = ammo.id,
            )
            // Conditions used the same field names, so they decode directly.
            val conditions = json.decodeFromJsonElement(Conditions.serializer(), DataMigration.conditions(o))
            AppData(weapons = listOf(weapon), ammo = listOf(ammo), activeWeaponId = weapon.id, conditions = conditions)
        }.getOrNull()
    }
}
