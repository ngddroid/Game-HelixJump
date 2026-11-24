package com.ngstudio.spindledrop.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.ngstudio.spindledrop.AppServices
import com.ngstudio.spindledrop.R

object GC {
    val Night = Color(0xFF140530)
    val Deep = Color(0xFF241056)
    val Violet = Color(0xFF4B23A8)
    val Indigo = Color(0xFF34188A)
    val Ink = Color(0xFF1A0B3D)
    val Pink = Color(0xFFFF3D8B)
    val Yellow = Color(0xFFFFC93C)
    val Mint = Color(0xFF2FD58E)
    val Sky = Color(0xFF39B8FF)
    val Orange = Color(0xFFFF8A3D)
    val Purple = Color(0xFF8B5CFF)
    val Red = Color(0xFFFF4D5E)
    val Gold = Color(0xFFFFC53D)
    val Gem = Color(0xFF4FC3FF)
    val Card = Color(0xFF3A1D86)
    val CardDark = Color(0xFF26105E)
    val Muted = Color(0xFFB9A8E8)
    val White = Color(0xFFFFFFFF)
}

val Lilita = FontFamily(Font(R.font.lilita_one))

@OptIn(ExperimentalTextApi::class)
val Fredoka = FontFamily(
    Font(R.font.fredoka, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.fredoka, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.fredoka, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.fredoka, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

object GT {
    fun display(size: TextUnit = 28.sp, color: Color = GC.White) = TextStyle(fontFamily = Lilita, fontSize = size, color = color, letterSpacing = 0.5.sp)
    fun body(size: TextUnit = 16.sp, color: Color = GC.White, weight: FontWeight = FontWeight.Medium) =
        TextStyle(fontFamily = Fredoka, fontSize = size, color = color, fontWeight = weight)
}

val LocalServices = staticCompositionLocalOf<AppServices> { error("No services") }

fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)
fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)
fun Long.toColor() = Color(this)
fun Int.toComposeColor() = Color(this)
