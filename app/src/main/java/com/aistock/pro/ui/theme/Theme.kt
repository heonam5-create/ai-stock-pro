package com.aistock.pro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.aistock.pro.analysis.Verdict

object AppColors {
    val Bg = Color(0xFF0B0F17)
    val Card = Color(0xFF131A27)
    val Card2 = Color(0xFF1A2333)
    val Line = Color(0xFF263247)
    val Text = Color(0xFFE8ECF4)
    val Sub = Color(0xFF8E99AD)
    val Up = Color(0xFFF04452)      // 상승 = 빨강 (국내 증권사 관례)
    val Down = Color(0xFF3B82F6)    // 하락 = 파랑
    val Accent = Color(0xFF5B8CFF)
    val Gold = Color(0xFFFACC15)
    val Green = Color(0xFF22C55E)
    val Orange = Color(0xFFF59E0B)
    val Teal = Color(0xFF14B8A6)
    val Purple = Color(0xFFA78BFA)
}

fun changeColor(v: Double?): Color = when {
    v == null || v.isNaN() || v == 0.0 -> AppColors.Sub
    v > 0 -> AppColors.Up
    else -> AppColors.Down
}

fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.STRONG_BUY -> AppColors.Up
    Verdict.SPLIT_BUY -> AppColors.Orange
    Verdict.HOLD -> AppColors.Sub
    Verdict.SPLIT_SELL -> AppColors.Down
    Verdict.AVOID -> Color(0xFF9333EA)
    Verdict.NA -> AppColors.Sub
}

fun scoreColor(s: Int): Color = when {
    s >= 2 -> AppColors.Up
    s == 1 -> AppColors.Orange
    s == 0 -> AppColors.Sub
    s == -1 -> AppColors.Teal
    else -> AppColors.Down
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AppColors.Accent,
            onPrimary = Color.White,
            secondary = AppColors.Gold,
            background = AppColors.Bg,
            onBackground = AppColors.Text,
            surface = AppColors.Card,
            onSurface = AppColors.Text,
            surfaceVariant = AppColors.Card2,
            onSurfaceVariant = AppColors.Sub,
            outline = AppColors.Line,
            error = AppColors.Up,
        ),
        content = content,
    )
}
