package pl.ballistics.app.data

import androidx.annotation.StringRes
import pl.ballistics.app.R
import pl.ballistics.core.Units
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Catalogue bullet data as published by the manufacturers (BC in lb/in²,
 * dimensions in inches). Lengths are approximate: measure your bullets with a
 * caliper for the best stability and spin-drift estimates.
 *
 * @param twistIn fastest recommended twist, inches per turn, when the maker publishes it.
 */
data class Bullet(
    val maker: String,
    val name: String,
    val diameterIn: Double,
    val massGr: Double,
    val g1: Double?,
    val g7: Double?,
    val lengthIn: Double? = null,
    val twistIn: Double? = null,
    val source: String = "",
    /** Stable key in the community database, e.g. `hornady-eld-match-264-147`. */
    val id: String = "",
    val discontinued: Boolean = false,
) {
    val label: String get() = "$maker $name ${caliber(diameterIn)} ${fmt(massGr)} gr"
}

/**
 * One published version of the community bullet database
 * (https://github.com/krzysztofgw-pl/ballistics-bullets, format 1).
 */
class BulletBundle(val version: Long, val bullets: List<Bullet>) {
    /** What the picker offers: discontinued bullets stay only so that lookups by id keep working. */
    val current: List<Bullet> by lazy { bullets.filterNot { it.discontinued } }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null for anything that is not a usable format-1 bundle, so a bad download is ignored. */
        fun parse(text: String): BulletBundle? = runCatching {
            val file = json.decodeFromString(BundleFile.serializer(), text)
            if (file.format != 1) return null
            val bullets = file.bullets.mapNotNull { it.toBullet() }
                .sortedWith(compareBy({ it.diameterIn }, { it.maker }, { it.massGr }, { it.name }))
            bullets.takeIf { it.isNotEmpty() }?.let { BulletBundle(file.version, it) }
        }.getOrNull()
    }
}

@Serializable
private class BundleFile(val format: Int, val version: Long, val bullets: List<BundleBullet>)

@Serializable
private class BundleBullet(
    val maker: String,
    val id: String,
    val name: String,
    @SerialName("diameter_in") val diameterIn: Double,
    @SerialName("weight_gr") val weightGr: Double,
    val bc: Map<String, Double> = emptyMap(),
    @SerialName("length_in") val lengthIn: Double? = null,
    @SerialName("twist_in") val twistIn: Double? = null,
    val source: BundleSource? = null,
    val discontinued: Boolean = false,
) {
    fun toBullet(): Bullet? {
        val g1 = bc["g1"]
        val g7 = bc["g7"]
        if (g1 == null && g7 == null) return null
        return Bullet(maker, name, diameterIn, weightGr, g1, g7, lengthIn, twistIn, source?.ref.orEmpty(), id, discontinued)
    }
}

@Serializable
private class BundleSource(val type: String = "", val ref: String = "")

/** Factory ammunition: a bullet plus the catalogue muzzle velocity (test barrel, usually 24″). */
data class FactoryLoad(
    val maker: String,
    val cartridge: String,
    val bullet: Bullet,
    /** Velocity as published, measured in the manufacturer's test barrel. */
    val muzzleVelocityFps: Double,
    val testBarrelCm: Double,
) {
    val muzzleVelocityMps: Double get() = muzzleVelocityFps * Units.FPS

    val label: String get() = "$maker $cartridge ${bullet.name} ${fmt(bullet.massGr)} gr"
}

private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

/** ".308", ".2645": the usual way calibers are written, so a search for "308" finds them. */
private fun caliber(diameterIn: Double) = fmt(diameterIn).removePrefix("0")

private fun num(v: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", v)

/** Fills an ammo profile from a bullet, preferring the G7 BC for modern long boat-tails. */
fun AmmoProfile.withBullet(bullet: Bullet): AmmoProfile {
    val useG7 = bullet.g7 != null
    return copy(
        name = bullet.label,
        dragType = if (useG7) DragType.G7 else DragType.G1,
        bc = num(if (useG7) bullet.g7!! else bullet.g1!!, 3),
        bcByModel = listOfNotNull(
            bullet.g1?.let { DragType.G1 to num(it, 3) },
            bullet.g7?.let { DragType.G7 to num(it, 3) },
        ).toMap(),
        massGr = fmt(bullet.massGr),
        diameterMm = num(bullet.diameterIn * Units.INCH * 1000, 2),
        lengthMm = bullet.lengthIn?.let { num(it * Units.INCH * 1000, 1) } ?: "",
    )
}

fun AmmoProfile.withFactoryLoad(load: FactoryLoad): AmmoProfile =
    withBullet(load.bullet).copy(
        name = load.label,
        cartridge = load.cartridge,
        muzzleVelocity = num(load.muzzleVelocityMps, 0),
        testBarrelCm = fmt(Math.round(load.testBarrelCm * 10) / 10.0),
        powderReferenceTemp = "21",
    )

/** A bullet for a handload: the velocity of a factory load it replaces no longer applies. */
fun AmmoProfile.withHandloadBullet(bullet: Bullet): AmmoProfile =
    withBullet(bullet).let { if (testBarrelCm.isNotBlank()) it.copy(muzzleVelocity = "", testBarrelCm = "") else it }

object BulletLibrary {
    /** Where the community database publishes its bundle; see https://github.com/krzysztofgw-pl/ballistics-bullets. */
    const val BUNDLE_URL = "https://krzysztofgw-pl.github.io/ballistics-bullets/v1/bullets.json"
    private const val CACHE = "bullets.json"
    private const val REFRESH_MS = 24 * 3600 * 1000L

    /** The snapshot of the database shipped with this build, for use offline and before the first download. */
    private val bundled: BulletBundle by lazy {
        val stream = checkNotNull(BulletLibrary::class.java.getResourceAsStream("/bullets.json")) { "bullets.json missing" }
        checkNotNull(BulletBundle.parse(stream.bufferedReader().use { it.readText() })) { "bullets.json invalid" }
    }

    private val _state = MutableStateFlow(bundled)
    /** The newest database known: the bundled one, a downloaded one, or a cached download. */
    val state: StateFlow<BulletBundle> = _state.asStateFlow()

    /** All bullets still made, ordered by caliber, maker and weight. */
    val bullets: List<Bullet> get() = _state.value.current

    /**
     * Uses the cached download when it is newer than the bundled snapshot, then
     * downloads the database again at most once a day. Failures leave what we had.
     */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val cache = File(context.filesDir, CACHE)
        runCatching { BulletBundle.parse(cache.readText()) }.getOrNull()?.let(::offer)
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < REFRESH_MS) return@withContext
        runCatching {
            val text = download()
            val bundle = BulletBundle.parse(text) ?: return@runCatching
            if (bundle.version >= _state.value.version) {
                cache.writeText(text)
                offer(bundle)
            } else {
                cache.setLastModified(System.currentTimeMillis())
            }
        }
    }

    private fun offer(bundle: BulletBundle) {
        if (bundle.version > _state.value.version) _state.value = bundle
    }

    private fun download(): String {
        val connection = URL(BUNDLE_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Factory loads point at bullets of the bundled snapshot by id, so a database change cannot break them. */
    private fun bullet(id: String): Bullet = bundled.bullets.first { it.id == id }

    /** Hornady and Federal publish velocities from a 24" test barrel. */
    private const val IN24 = 24 * 2.54

    val factoryLoads: List<FactoryLoad> by lazy {
        listOf(
            FactoryLoad("Hornady Match", ".223 Rem", bullet("hornady-eld-match-224-73"), 2790.0, IN24),
            FactoryLoad("Federal Gold Medal Match", ".223 Rem", bullet("sierra-matchking-hpbt-224-77"), 2720.0, IN24),
            FactoryLoad("Hornady Match", "6mm Creedmoor", bullet("hornady-eld-match-243-108"), 2960.0, IN24),
            FactoryLoad("Hornady Match", "6.5 Creedmoor", bullet("hornady-eld-match-264-140"), 2710.0, IN24),
            FactoryLoad("Hornady Match", "6.5 Creedmoor", bullet("hornady-eld-match-264-147"), 2695.0, IN24),
            FactoryLoad("Hornady Precision Hunter", "6.5 Creedmoor", bullet("hornady-eld-x-264-143"), 2700.0, IN24),
            FactoryLoad("Sellier & Bellot", "6.5 Creedmoor", bullet("sellier-bellot-hpbt-264-142"), 800 / Units.FPS, 60.0),
            FactoryLoad("Hornady Match", "6.5 PRC", bullet("hornady-eld-match-264-147"), 2910.0, IN24),
            FactoryLoad("Federal Gold Medal Match", ".308 Win", bullet("sierra-matchking-hpbt-308-168"), 2650.0, IN24),
            FactoryLoad("Federal Gold Medal Match", ".308 Win", bullet("sierra-matchking-hpbt-308-175"), 2600.0, IN24),
            FactoryLoad("Hornady Match", ".308 Win", bullet("hornady-eld-match-308-178"), 2600.0, IN24),
            FactoryLoad("Hornady Precision Hunter", ".308 Win", bullet("hornady-eld-x-308-178"), 2600.0, IN24),
            FactoryLoad("Hornady Match", ".300 PRC", bullet("hornady-eld-match-308-225"), 2810.0, IN24),
            FactoryLoad("Hornady Match", ".338 Lapua Mag", bullet("hornady-eld-match-338-285"), 2745.0, IN24),
        )
    }
}

/** Typical rail-top height above the bore axis; a starting point, not a measurement. */
enum class RailPlatform(@StringRes val label: Int, val railAboveBoreMm: Double?) {
    BOLT_PICATINNY(R.string.rail_bolt_picatinny, 16.0),
    BOLT_BASES(R.string.rail_bolt_weaver, 13.0),
    AR15(R.string.rail_ar15, 28.0),
    CUSTOM(R.string.rail_custom, null),
}
