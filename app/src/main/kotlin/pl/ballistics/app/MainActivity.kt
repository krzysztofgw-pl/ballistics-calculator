package pl.ballistics.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.ballistics.app.ui.App
import pl.ballistics.app.ui.BallisticsTheme
import pl.ballistics.app.ui.isDarkTheme

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars on every API level, as Android 15+ does by default.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Recreated (rotation), the file shared at launch has been read already.
        if (savedInstanceState == null) openShared(intent)
        setContent {
            val vm: MainViewModel = viewModel()
            val data by vm.data.collectAsStateWithLifecycle()
            val dark = isDarkTheme(data.ui.theme)
            // Bar icons follow the app's theme (it can differ from the system one).
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            BallisticsTheme(data.ui) {
                App(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openShared(intent)
    }

    /** A chronograph file shared from another app (ShotView, Athlon, a file manager) or opened with this one. */
    private fun openShared(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let(model::openChronograph)
            // A long press on the Stealth tile.
            TileService.ACTION_QS_TILE_PREFERENCES -> model.stealthDialog.value = true
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> model.openChronograph(stream)
                    !text.isNullOrBlank() -> model.openChronographText(text)
                }
            }
        }
    }

    private companion object {
        // androidx.activity's default scrims for 3-button navigation.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
