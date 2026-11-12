package pl.ballistics.app.ui

import android.app.Activity

/** The GitHub build cannot use Play's in-app rating sheet, so it opens the Play listing. */
fun Activity.rateApp() = openPlayListing()
