package pl.ballistics.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import pl.ballistics.app.BuildConfig
import pl.ballistics.app.R

private const val PLAY_PACKAGE = "com.kgwlabs.ballistics"

/** The app's Google Play page, in the Play Store app when there is one, else in the browser. */
fun Activity.openPlayListing() {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PLAY_PACKAGE"))
        .setPackage("com.android.vending")
    runCatching { startActivity(market) }.onFailure {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PLAY_PACKAGE")))
        }
    }
}

/** The system share sheet with a short line about the app and where to get it. */
fun Context.shareApp() {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_app_subject))
        putExtra(Intent.EXTRA_TEXT, getString(R.string.share_app_text, BuildConfig.SHARE_URL))
    }
    runCatching { startActivity(Intent.createChooser(send, getString(R.string.action_share_app))) }
}

/** The activity behind a Compose [Context], which may be wrapped. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
