package pl.ballistics.app.ui

import android.app.Activity
import androidx.compose.runtime.Composable

/** The GitHub build cannot use Play's in-app rating sheet, so it opens the Play listing. */
fun Activity.rateApp() = openPlayListing()

/** Only the Play build asks for a rating on its own. */
@Composable
fun AutoReviewPrompt() = Unit
