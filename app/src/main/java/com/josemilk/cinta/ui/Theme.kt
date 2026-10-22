package com.josemilk.cinta.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Paper = Color(0xFFF4F1E8)
val Ink = Color(0xFF14241C)
val Sea = Color(0xFF0E9F86)
val SeaDeep = Color(0xFF087463)
val Coral = Color(0xFFFF6B57)
val CardWhite = Color(0xFFFFFCF6)
val Muted = Color(0xFF5C6F66)
val Mist = Color(0xFFE5F3EE)

private val LightColors = lightColorScheme(
    primary = Sea,
    onPrimary = Color.White,
    secondary = Coral,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = CardWhite,
    onSurface = Ink,
    surfaceVariant = Mist,
    onSurfaceVariant = Muted,
    outline = Color(0x3314241C),
    error = Coral,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3DDBC0),
    onPrimary = Color(0xFF04241E),
    secondary = Color(0xFFFF8D7C),
    onSecondary = Color(0xFF2A0E0A),
    background = Color(0xFF0E1A16),
    onBackground = Color(0xFFE7F4EE),
    surface = Color(0xFF173028),
    onSurface = Color(0xFFE7F4EE),
    surfaceVariant = Color(0xFF21443A),
    onSurfaceVariant = Color(0xFFB7D0C6),
    outline = Color(0x33E7F4EE),
    error = Color(0xFFFF8D7C),
)

private val CintaType = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontStyle = FontStyle.Italic,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 44.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
)

@Composable
fun CintaTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = CintaType,
        content = content,
    )
}

@Composable
fun CintaCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(18.dp),
        content = content,
    )
}
