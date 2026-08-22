package com.ethran.notable.ui.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable

/**
 * Notable has one palette, and it is [Kaleido].
 *
 * There used to be a second: `isSystemInDarkTheme()` swapped in Material's stock
 * `darkColors(Purple200, Purple700, Teal200)` — the untouched Android Studio template. Because
 * only *some* screens read MaterialTheme (Settings, Welcome, the log and sync views) while the
 * library and its chrome are painted from hard-coded Kaleido tokens, turning on the system dark
 * theme did not give the app a dark mode. It gave it half of one: a cream library and a
 * near-black settings screen, in colours nobody chose.
 *
 * Half of that is worse than either whole. On an EPD the dark half is also a full-page black
 * flood, which is slow to lay down and ghosts afterwards — the panel is reflective, so there is
 * no eye-strain argument for it the way there is on a backlit screen.
 *
 * So the Material colours are now derived from the same tokens the rest of the app draws with,
 * and the activity is pinned to the light configuration in MainActivity. If an inverted "night
 * paper" mode is ever wanted, it belongs to the whole app as an explicit Notable setting — the
 * way Saber's editorAutoInvert threads one `invert` flag through ink, images, PDFs and chrome
 * alike — not to an OS toggle that only half the screens can see.
 */
private val KaleidoColors = lightColors(
    primary = Kaleido.Ink,
    primaryVariant = Kaleido.Muted,
    secondary = Kaleido.Red,
    secondaryVariant = Kaleido.Coral,
    background = Kaleido.Paper,
    surface = Kaleido.Paper,
    error = Kaleido.Red,
    onPrimary = Kaleido.Paper,
    onSecondary = Kaleido.Paper,
    onBackground = Kaleido.Ink,
    onSurface = Kaleido.Ink,
    onError = Kaleido.Paper,
)

@Composable
fun InkaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = KaleidoColors,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
