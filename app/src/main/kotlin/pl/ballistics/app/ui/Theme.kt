package pl.ballistics.app.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import pl.ballistics.app.data.CardShade
import pl.ballistics.app.data.ThemeMode
import pl.ballistics.app.data.UiSettings

/** The background of the section cards, set from the card shade chosen for the current theme. */
val LocalSectionColor = staticCompositionLocalOf { Color.Unspecified }

@Composable
fun BallisticsTheme(ui: UiSettings = UiSettings(), content: @Composable () -> Unit) {
    val dark = isDarkTheme(ui.theme)
    val colors = colorScheme(LocalContext.current, dark)
    val shade = if (dark) ui.cardShadeDark else ui.cardShadeLight
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalSectionColor provides shade.color(colors), content = content)
    }
}

/** The app's colours: the wallpaper's on Android 12 and later, Material's defaults before. */
fun colorScheme(context: Context, dark: Boolean): ColorScheme = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    dark -> darkColorScheme()
    else -> lightColorScheme()
}

fun CardShade.color(colors: ColorScheme): Color = when (this) {
    CardShade.STRONG -> colors.surfaceContainerHighest
    CardShade.MEDIUM -> colors.surfaceContainerHigh
    CardShade.SOFT -> colors.surfaceContainer
    CardShade.SUBTLE -> colors.surfaceContainerLow
    CardShade.INVERSE -> colors.surfaceContainerLowest
}

/** Card colours for a section card; every top-level card on the screens uses them. */
@Composable
fun sectionCardColors(): CardColors {
    val color = LocalSectionColor.current
    return if (color == Color.Unspecified) CardDefaults.cardColors() else CardDefaults.cardColors(containerColor = color)
}

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
