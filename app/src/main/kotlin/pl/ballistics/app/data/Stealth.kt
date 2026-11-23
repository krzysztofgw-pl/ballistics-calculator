package pl.ballistics.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * Stealth mode: the app stops its own radio use. No network request of any kind (forecast,
 * terrain height, sync, bullet database, update checks), no link to the Garmin watch unless
 * allowed, and location from the satellites only, since the network and fused providers send
 * nearby Wi-Fi and cell towers to a server. An app cannot turn the phone's radios off, so the
 * stealth dialog points to the system settings for that.
 *
 * Kept on this phone only: it is about this device's emissions, and it must not arrive or
 * leave through sync.
 */
object Stealth {
    data class Settings(val on: Boolean = false, val allowWatch: Boolean = false)

    private const val PREFS = "stealth"
    private const val KEY_ON = "on"
    private const val KEY_WATCH = "allow_watch"
    private const val KEY_HIDE_NOTICE = "hide_notice"
    private const val KEY_TILE = "tile_added"

    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()
    private var loaded = false

    /** Reads the saved setting once per process; the activity, the view model and the tile all call it. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = Settings(prefs.getBoolean(KEY_ON, false), prefs.getBoolean(KEY_WATCH, false))
    }

    fun set(context: Context, settings: Settings) {
        loaded = true
        _state.value = settings
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ON, settings.on).putBoolean(KEY_WATCH, settings.allowWatch).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether to explain, on turning stealth on, that the phone's networks must be switched by hand. */
    fun showNotice(context: Context) = !prefs(context).getBoolean(KEY_HIDE_NOTICE, false)

    fun hideNotice(context: Context) = prefs(context).edit().putBoolean(KEY_HIDE_NOTICE, true).apply()

    /** Whether the Stealth quick settings tile is in the user's panel, as the tile itself last reported. */
    fun tileAdded(context: Context) = prefs(context).getBoolean(KEY_TILE, false)

    fun setTileAdded(context: Context, added: Boolean) = prefs(context).edit().putBoolean(KEY_TILE, added).apply()

    val blocksNetwork: Boolean get() = _state.value.on
    val blocksWatch: Boolean get() = _state.value.on && !_state.value.allowWatch
    /** Location from GPS_PROVIDER only, without network assistance. */
    val satellitesOnly: Boolean get() = _state.value.on

    /** Every network request goes through this first. */
    fun checkNetwork() {
        if (blocksNetwork) throw BlockedException()
    }

    class BlockedException : IOException("Stealth mode: network blocked")
}
