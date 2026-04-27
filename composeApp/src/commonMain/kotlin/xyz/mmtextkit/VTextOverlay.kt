package xyz.mmtextkit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.selection.TextSelectionColors

/**
 * 交互覆盖层状态 - 包含各平台覆盖层所需的共享数据
 */
internal class VTextFieldOverlayState(
    val value: State<TextFieldValue>,
    val onValueChange: State<(TextFieldValue) -> Unit>,
    val textLayoutResult: State<TextLayoutResult?>,
    val columnMetrics: State<List<ColumnMetrics>>,
    val columnSpacingPx: State<Float>,
    val scrollOffset: State<Float>,
    val onScrollOffsetChange: (Float) -> Unit,
    val maxScrollOffset: State<Float>,
    val selectable: State<Boolean>,
    val readOnly: State<Boolean>,
    val isFocused: State<Boolean>,
    val focusRequester: FocusRequester,
    val selectionAnchor: MutableState<Int>,
    val cursorAlpha: State<Float>,
    val style: State<TextStyle>,
    val selectionColors: TextSelectionColors,
    val visualToCharIdx: (Offset) -> Int,
    val handlePosition: (Int, Boolean) -> Offset,
    /** 长按/右键请求上下文菜单时触发，提供菜单触发位置。
     *  Desktop 端库自带默认菜单，此回调无效；
     *  移动端/Web 端由开发者自行实现菜单 UI */
    val onContextMenuRequest: State<((Offset) -> Unit)?>,
)

/**
 * 平台特有交互覆盖层
 * 各平台实现：手势处理 + 光标/选区绘制 + 平台特有UI（菜单/滚动条等）
 */
@Composable
internal expect fun VTextFieldOverlay(
    state: VTextFieldOverlayState,
    modifier: Modifier = Modifier,
)
