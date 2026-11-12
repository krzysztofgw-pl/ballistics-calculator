package pl.ballistics.app.ui

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Asks Google Play for its in-app rating sheet. Play decides whether the sheet appears (it is
 * rationed per user), so when the request itself fails the store page opens instead.
 */
fun Activity.rateApp() {
    val manager = ReviewManagerFactory.create(this)
    manager.requestReviewFlow().addOnCompleteListener { request ->
        if (request.isSuccessful) {
            manager.launchReviewFlow(this, request.result)
        } else {
            openPlayListing()
        }
    }
}
