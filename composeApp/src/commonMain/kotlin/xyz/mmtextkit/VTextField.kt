package xyz.mmtextkit

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.font.FontFamily
import kotlin.math.abs

/**
 * 垂直排版编辑器
 *
 * @param readOnly 是否只读
 * @param selectable 是否允许选取
 * @param onContextMenuRequest 长按/右键请求上下文菜单时触发，提供菜单触发位置。
 *   Desktop 端库自带默认菜单，此回调无效；
 *   移动端/Web 端由开发者自行实现菜单 UI
 */
@Composable
fun VTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    verticalFontFamily: FontFamily = FontFamily.Default,
    style: TextStyle = TextStyle.Default,
    readOnly: Boolean = false,
    selectable: Boolean = true,
    /** 削减 Ascent 比例 */
    ascentTrim: Float = DEFAULT_ASCENT_TRIM,
    /** 基线偏移 */
    baselineShift: Float = DEFAULT_BASELINE_SHIFT,
    /** 是否强制等宽列 */
    fixedWidth: Boolean = true,
    /** 长按/右键请求上下文菜单时触发 */
    onContextMenuRequest: ((Offset) -> Unit)? = null,
) {
    val internalConfig = remember(verticalFontFamily, ascentTrim, baselineShift, fixedWidth) {
        VTextInternalConfig(verticalFontFamily, ascentTrim, baselineShift, fixedWidth)
    }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var isFocused by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val columnSpacingPx = with(density) { internalConfig.columnSpacing.toPx() }

        val focusRequester = remember { FocusRequester() }
        var selectionAnchor by remember { mutableStateOf(0) }

        val selectionColors = LocalTextSelectionColors.current

        // 缓存列指标
        val columnMetrics = remember(textLayoutResult, internalConfig) {
            textLayoutResult?.computeColumnMetrics(internalConfig, density) ?: emptyList()
        }

        // 状态引用包装
        val currentColumnMetrics = rememberUpdatedState(columnMetrics)
        val currentColumnSpacingPx = rememberUpdatedState(columnSpacingPx)
        val currentValue = rememberUpdatedState(value)
        val currentLayout = rememberUpdatedState(textLayoutResult)

        // 滚动状态
        var scrollOffset by remember { mutableStateOf(0f) }
        val currentScrollOffset = rememberUpdatedState(scrollOffset)

        val maxScrollOffset by remember(columnMetrics) {
            derivedStateOf {
                val totalContentWidth = columnMetrics.indices.sumOf { i ->
                    (columnMetrics.getOrNull(i)?.width?.toDouble() ?: 0.0) +
                    if (i < columnMetrics.size - 1) columnSpacingPx.toDouble() else 0.0
                }.toFloat()
                (totalContentWidth - constraints.maxWidth.toFloat()).coerceAtLeast(0f)
            }
        }
        val currentMaxScrollOffset = rememberUpdatedState(maxScrollOffset)

        val currentSelectable = rememberUpdatedState(selectable)
        val currentOnValueChange = rememberUpdatedState(onValueChange)
        val currentReadOnly = rememberUpdatedState(readOnly)
        val currentIsFocused = rememberUpdatedState(isFocused)
        val currentStyle = rememberUpdatedState(style)
        val currentOnContextMenuRequest = rememberUpdatedState(onContextMenuRequest)

        // 光标闪烁（有选区时不闪烁）
        val cursorAlpha = remember { Animatable(1f) }

        LaunchedEffect(value.selection, isFocused) {
            if (isFocused && value.selection.collapsed) {
                cursorAlpha.snapTo(1f)
                cursorAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = 1000
                            1f at 0; 1f at 499; 0f at 500; 0f at 999
                        }
                    )
                )
            } else {
                cursorAlpha.snapTo(0f)
            }
        }

        // 自动滚动同步
        LaunchedEffect(value.selection, columnMetrics) {
            val layout = textLayoutResult ?: return@LaunchedEffect
            if (columnMetrics.isEmpty()) return@LaunchedEffect

            val lineIdx = layout.getLineForOffset(
                value.selection.start.coerceIn(0, layout.layoutInput.text.length)
            )

            var colLeft = 0f
            for (k in 0 until lineIdx) {
                colLeft += (columnMetrics.getOrNull(k)?.width ?: 0f) + columnSpacingPx
            }
            val colRight = colLeft + (columnMetrics.getOrNull(lineIdx)?.width ?: 0f)

            val viewWidth = constraints.maxWidth.toFloat()

            scrollOffset = when {
                colRight - scrollOffset > viewWidth ->
                    (colRight - viewWidth).coerceIn(0f, maxScrollOffset)
                colLeft - scrollOffset < 0f ->
                    colLeft.coerceIn(0f, maxScrollOffset)
                else -> scrollOffset
            }
        }

        // 坐标转换逻辑
        fun visualToCharIdx(offset: Offset): Int {
            val layout = currentLayout.value ?: return 0
            val metrics = currentColumnMetrics.value
            val spacingPx = currentColumnSpacingPx.value
            val scroll = currentScrollOffset.value
            if (metrics.isEmpty()) return 0

            val verticalOffset = 0f
            val localY = offset.y - verticalOffset

            var bestLineIdx = -1
            var minDistance = Float.MAX_VALUE
            var bestScreenCenterX = 0f
            var bestInkCenter = 0f

            var runningX = 0f
            for (i in 0 until layout.lineCount) {
                val m = metrics.getOrNull(i) ?: continue
                val halfW = m.width / 2f
                val centerX = runningX + halfW + (i * spacingPx)
                val screenCenterX = centerX - scroll

                val dist = abs(offset.x - screenCenterX)
                if (dist < minDistance) {
                    minDistance = dist
                    bestLineIdx = i
                    bestScreenCenterX = screenCenterX
                    bestInkCenter = m.inkCenter
                }
                runningX += m.width
            }

            if (bestLineIdx == -1) return layout.layoutInput.text.length

            val py = bestInkCenter + bestScreenCenterX - offset.x
            return layout.getOffsetForPosition(Offset(localY, py))
        }

        fun handlePosition(charIdx: Int, isStart: Boolean): Offset {
            val layout = currentLayout.value ?: return Offset.Zero
            val metrics = currentColumnMetrics.value
            val spacingPx = currentColumnSpacingPx.value
            val scroll = currentScrollOffset.value
            val verticalOffset = 0f

            val safeIdx = charIdx.coerceIn(0, layout.layoutInput.text.length)
            val lineIdx = layout.getLineForOffset(safeIdx)

            var currentX = 0f
            for (k in 0 until lineIdx) {
                currentX += metrics.getOrNull(k)?.width ?: 0f
            }
            val m = metrics.getOrNull(lineIdx) ?: ColumnMetrics(0f, 0f, 0f)
            val midX = currentX + m.width / 2f + (lineIdx * spacingPx) - scroll

            val y = if (safeIdx < layout.layoutInput.text.length) {
                val box = layout.getBoundingBox(safeIdx)
                if (isStart) box.left else box.right
            } else {
                layout.getCursorRect(safeIdx).bottom
            }
            return Offset(midX, y + verticalOffset)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // 渲染层
            VerticalText(
                text = value.text,
                style = style,
                config = internalConfig,
                externalMetrics = columnMetrics,
                scrollOffset = scrollOffset,
                modifier = Modifier.fillMaxSize(),
                onTextLayout = { textLayoutResult = it }
            )

            // 系统底层输入支持
            CompositionLocalProvider(
                LocalTextSelectionColors provides TextSelectionColors(
                    handleColor = Color.Transparent,
                    backgroundColor = Color.Transparent
                )
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    readOnly = readOnly,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .onFocusChanged { isFocused = it.isFocused }
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val layout = textLayoutResult ?: return@onPreviewKeyEvent false
                            val shift = keyEvent.isShiftPressed
                            if (!selectable && shift) return@onPreviewKeyEvent false

                            val focus = when {
                                value.selection.collapsed -> value.selection.start
                                selectionAnchor == value.selection.start -> value.selection.end
                                else -> value.selection.start
                            }

                            fun applyMove(newFocus: Int): Boolean {
                                if (shift) {
                                    onValueChange(value.copy(selection = TextRange(
                                        minOf(selectionAnchor, newFocus),
                                        maxOf(selectionAnchor, newFocus)
                                    )))
                                } else {
                                    selectionAnchor = newFocus
                                    onValueChange(value.copy(selection = TextRange(newFocus)))
                                }
                                return true
                            }

                            when (keyEvent.key) {
                                Key.DirectionUp -> applyMove(if (focus > 0) focus - 1 else focus)
                                Key.DirectionDown -> applyMove(if (focus < value.text.length) focus + 1 else focus)
                                Key.DirectionLeft -> {
                                    val line = layout.getLineForOffset(focus)
                                    applyMove(if (line > 0) {
                                        val pos = focus - layout.getLineStart(line)
                                        (layout.getLineStart(line - 1) + pos).coerceAtMost(layout.getLineEnd(line - 1))
                                    } else focus)
                                }
                                Key.DirectionRight -> {
                                    val line = layout.getLineForOffset(focus)
                                    applyMove(if (line < layout.lineCount - 1) {
                                        val pos = focus - layout.getLineStart(line)
                                        (layout.getLineStart(line + 1) + pos).coerceAtMost(layout.getLineEnd(line + 1))
                                    } else focus)
                                }
                                else -> false
                            }
                        },
                    textStyle = style.copy(color = Color.Transparent),
                    cursorBrush = SolidColor(Color.Transparent)
                )
            } // CompositionLocalProvider

            // 交互覆盖层（平台特有实现：手势 + 光标/选区绘制 + 菜单/滚动条等）
            val selectionAnchorState = remember { mutableStateOf(selectionAnchor) }
            selectionAnchorState.value = selectionAnchor

            val cursorAlphaState = remember { derivedStateOf { cursorAlpha.value } }

            val overlayState = remember(
                focusRequester, selectionAnchorState
            ) {
                VTextFieldOverlayState(
                    value = currentValue,
                    onValueChange = currentOnValueChange,
                    textLayoutResult = currentLayout,
                    columnMetrics = currentColumnMetrics,
                    columnSpacingPx = currentColumnSpacingPx,
                    scrollOffset = currentScrollOffset,
                    onScrollOffsetChange = { scrollOffset = it },
                    maxScrollOffset = currentMaxScrollOffset,
                    selectable = currentSelectable,
                    readOnly = currentReadOnly,
                    isFocused = currentIsFocused,
                    focusRequester = focusRequester,
                    selectionAnchor = selectionAnchorState,
                    cursorAlpha = cursorAlphaState,
                    style = currentStyle,
                    selectionColors = selectionColors,
                    visualToCharIdx = ::visualToCharIdx,
                    handlePosition = ::handlePosition,
                    onContextMenuRequest = currentOnContextMenuRequest,
                )
            }

            LaunchedEffect(selectionAnchorState.value) { selectionAnchor = selectionAnchorState.value }

            VTextFieldOverlay(
                state = overlayState,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
