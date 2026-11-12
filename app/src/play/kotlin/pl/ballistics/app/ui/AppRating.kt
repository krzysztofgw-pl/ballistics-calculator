package pl.ballistics.app.ui

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.delay

/**
 * Asks Google Play for its in-app rating sheet. Play decides whether the sheet appears (it is
 * rationed per user), so when the request itself fails the store page opens instead.
 */
fun Activity.rateApp() = requestReview(fallbackToListing = true)

private fun Activity.requestReview(fallbackToListing: Boolean) {
    val manager = ReviewManagerFactory.create(this)
    manager.requestReviewFlow().addOnCompleteListener { request ->
        if (request.isSuccessful) {
            manager.launchReviewFlow(this, request.result)
        } else if (fallbackToListing) {
            openPlayListing()
        }
    }
}

private const val PREFS = "app_rating"
private const val KEY_LAUNCHES = "launches"
private const val KEY_LAST_ASKED = "last_asked"
private const val MIN_LAUNCHES = 5
private const val MIN_DAYS_INSTALLED = 3L
private const val DAYS_BETWEEN_ASKS = 120L
private const val USE_BEFORE_ASKING_MS = 3 * 60_000L
private const val DAY_MS = 24 * 60 * 60_000L

/**
 * Offers Play's rating sheet on its own once the app has been in use for a while: from the
 * fifth launch, at least three days after install, after three minutes in the app, and then
 * at most every 120 days. Play rations the sheet further and may show nothing; nothing opens
 * when it does not.
 */
@Composable
fun AutoReviewPrompt() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val launches = prefs.getInt(KEY_LAUNCHES, 0) + 1
        prefs.edit().putInt(KEY_LAUNCHES, launches).apply()
        val now = System.currentTimeMillis()
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        }.getOrDefault(now)
        val lastAsked = prefs.getLong(KEY_LAST_ASKED, 0L)
        val due = launches >= MIN_LAUNCHES &&
            now - installed >= MIN_DAYS_INSTALLED * DAY_MS &&
            now - lastAsked >= DAYS_BETWEEN_ASKS * DAY_MS
        if (!due) return@LaunchedEffect
        // Cancelled with the composition, so leaving the app before then asks nothing.
        delay(USE_BEFORE_ASKING_MS)
        val activity = context.findActivity() ?: return@LaunchedEffect
        prefs.edit().putLong(KEY_LAST_ASKED, System.currentTimeMillis()).apply()
        activity.requestReview(fallbackToListing = false)
    }
}
