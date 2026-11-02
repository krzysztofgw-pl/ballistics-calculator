package pl.ballistics.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Google Play updates the app itself, so the store build has no updater of its own. */
class AppUpdater {
    fun checkOnStart() = Unit
    fun check(@Suppress("UNUSED_PARAMETER") asked: Boolean) = Unit
}

@Composable
fun rememberAppUpdater(): AppUpdater = remember { AppUpdater() }

@Composable
fun AppUpdateDialogs(@Suppress("UNUSED_PARAMETER") updater: AppUpdater) = Unit
