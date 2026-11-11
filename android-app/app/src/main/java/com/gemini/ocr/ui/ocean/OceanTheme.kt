package com.gemini.ocr.ui.ocean

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared visual tokens. They never change application settings or overlay colours. */
object OceanColors {
    val Base = Color(0xFF121C33)
    val Card = Color(0xFF182542)
    val Elevated = Color(0xFF203054)
    val Blue = Color(0xFF3B82F6)
    val Cyan = Color(0xFF38BDF8)
    val Mint = Color(0xFF2DD4BF)
    val Text = Color(0xFFF2F6FF)
    val Muted = Color(0xFFB8C8E4)
    val Outline = Color(0xFF49628C)
    val Warning = Color(0xFFFFD78A)
    val Error = Color(0xFFFFB4AB)
}

private val OceanTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 39.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 35.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 31.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 25.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 21.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 17.sp),
)

@Composable
fun OceanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = OceanColors.Cyan, onPrimary = OceanColors.Base,
            primaryContainer = OceanColors.Elevated, onPrimaryContainer = OceanColors.Text,
            secondary = OceanColors.Mint, onSecondary = OceanColors.Base,
            secondaryContainer = OceanColors.Elevated, onSecondaryContainer = OceanColors.Mint,
            tertiary = OceanColors.Blue, onTertiary = OceanColors.Text,
            background = OceanColors.Base, onBackground = OceanColors.Text,
            surface = OceanColors.Card, onSurface = OceanColors.Text,
            surfaceVariant = OceanColors.Elevated, onSurfaceVariant = OceanColors.Muted,
            surfaceContainerLowest = OceanColors.Base, surfaceContainerLow = OceanColors.Card,
            surfaceContainer = OceanColors.Card, surfaceContainerHigh = OceanColors.Elevated,
            surfaceContainerHighest = OceanColors.Elevated,
            outline = OceanColors.Outline, outlineVariant = OceanColors.Outline.copy(alpha = .45f),
            error = OceanColors.Error, errorContainer = Color(0xFF522E3B), onErrorContainer = OceanColors.Text,
        ),
        typography = OceanTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content,
    )
}
