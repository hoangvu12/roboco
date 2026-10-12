package sh.roboco.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.roboco.android.R
import sh.roboco.android.core.CoreStatus

/**
 * The scaffold placeholder (android-app#13): proves the core, the fonts and
 * the theme wiring end to end. Ticket 19 replaces it with the real shell.
 */
@Composable
fun PlaceholderScreen(core: CoreStatus) {
    val assets = LocalContext.current.assets
    val semiBold = FontFamily(
        Font("fonts/Geist-SemiBold.ttf", assets, weight = FontWeight.SemiBold),
    )
    val mono = FontFamily(Font("fonts/GeistMono.ttf", assets))
    val ink = if (isSystemInDarkTheme()) Color(0xFFFFFFFF) else Color(0xFF000000)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.launch_background)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText(
                text = "Roboco",
                style = TextStyle(
                    fontFamily = semiBold,
                    fontSize = 22.sp,
                    color = ink,
                ),
            )
            val detail = when (core) {
                is CoreStatus.Loaded -> "core ${core.version}"
                is CoreStatus.Failed -> "Couldn't load the Roboco core: ${core.reason}"
            }
            BasicText(
                text = detail,
                style = TextStyle(
                    fontFamily = mono,
                    fontSize = 13.sp,
                    color = ink.copy(alpha = 0.6f),
                ),
            )
        }
    }
}
