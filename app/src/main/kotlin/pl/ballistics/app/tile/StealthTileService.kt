package pl.ballistics.app.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import pl.ballistics.app.R
import pl.ballistics.app.data.Stealth

/**
 * The Stealth tile in the quick settings, next to airplane mode and Bluetooth, so all three can be
 * switched in one swipe without opening the app. It flips the app's stealth mode only; the app's
 * bar and dialog show which of the phone's radios are still on.
 */
class StealthTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        Stealth.load(this)
        show()
    }

    override fun onClick() {
        super.onClick()
        Stealth.load(this)
        val s = Stealth.state.value
        Stealth.set(this, s.copy(on = !s.on))
        show()
    }

    private fun show() {
        val tile = qsTile ?: return
        val on = Stealth.state.value.on
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.stealth_short)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stealth)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (on) R.string.stealth_on else R.string.stealth_off)
        }
        tile.updateTile()
    }
}
