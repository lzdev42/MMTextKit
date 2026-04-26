package xyz.columnscript.columnscript.components

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
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


// 状态对象
internal class VTextFieldGestureState(
    val value: State<TextFieldValue>,
    val onValueChange: State<(TextFieldValue) -> Unit>,
    val textLayoutResult: State<TextLayoutResult?>,
    val columnMetrics: State<List<ColumnMetrics>>,
    val scrollOffset: State<Float>,
    val onScrollOffsetChange: (Float) -> Unit,
    val maxScrollOffset: State<Float>,
    val selectable: State<Boolean>,
    val focusRequester: FocusRequester,
    val onShowContextMenu: (Offset) -> Unit,
    val isHoveringBottom: MutableState<Boolean>,
    val selectionAnchor: MutableState<Int>,
    val visualToCharIdx: (Offset) -> Int,
    val handlePosition: (Int, Boolean) -> Offset
)

// 跨平台交互入口
internal expect fun Modifier.vTextPlatformGestures(state: VTextFieldGestureState): Modifier

/**
 * 判断码点是否属于强制竖排文本系统
 */
fun isVerticalFontSystem(codePoint: Int): Boolean {
    return when {
        codePoint in 0x1800..0x18AF -> true   // 蒙古文/满文/锡伯文/托忒文等
        codePoint in 0x11660..0x1167F -> true // 蒙古文补充
        codePoint in 0xA840..0xA87F -> true   // 八思巴文
        // 若有其他需要竖排处理的字符区间，可在此扩展
        else -> false
    }
}
