package xyz.mmtextkit

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal const val DEFAULT_ASCENT_TRIM = 0.15f
internal const val DEFAULT_BASELINE_SHIFT = 0f
internal val INTERNAL_COLUMN_SPACING = 0.dp

internal data class VTextInternalConfig(
    val verticalFontFamily: FontFamily,
    val ascentTrim: Float = DEFAULT_ASCENT_TRIM,
    val baselineShift: Float = DEFAULT_BASELINE_SHIFT,
    val fixedWidth: Boolean = true,
    val columnSpacing: Dp = INTERNAL_COLUMN_SPACING
)

/**
 * 判断码点是否属于强制竖排文本系统
 */
fun isVerticalFontSystem(codePoint: Int): Boolean {
    return when {
        codePoint in 0x1800..0x18AF -> true // 蒙古文/满文/锡伯文/托忒文等
        codePoint in 0x11660..0x1167F -> true // 蒙古文补充
        codePoint in 0xA840..0xA87F -> true // 八思巴文
        // 若有其他需要竖排处理的字符区间，可在此扩展
        else -> false
    }
}
