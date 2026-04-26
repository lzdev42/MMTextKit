package xyz.columnscript.columnscript.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private const val LONG_PRESS_TIMEOUT_MS = 500L
private const val HANDLE_RADIUS_PX = 28f

/**
 * 垂直排版编辑器
 *
 * @param readOnly 是否只读
 * @param selectable 是否允许选取
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
) {
    val internalConfig = remember(verticalFontFamily, ascentTrim, baselineShift, fixedWidth) {
        VTextInternalConfig(verticalFontFamily, ascentTrim, baselineShift, fixedWidth)
    }




    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var isFocused by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier) {
        val totalHeight = with(LocalDensity.current) { maxHeight.toPx() }
        
        val density = LocalDensity.current
        val columnSpacingPx = with(density) { internalConfig.columnSpacing.toPx() }


        val focusRequester = remember { FocusRequester() }
        var selectionAnchor by remember { mutableStateOf(0) }

        // 菜单状态
        var showContextMenu by remember { mutableStateOf(false) }
        var contextMenuOffset by remember { mutableStateOf(Offset.Zero) }

        val selectionColors = LocalTextSelectionColors.current
        val clipboardManager = LocalClipboardManager.current

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
        // 用 State 包装 scrollOffset，确保 pointerInput 闭包能读到最新值
        val currentScrollOffset = rememberUpdatedState(scrollOffset)
    
        // 调试输出：光标所在列的信息
        LaunchedEffect(value.selection, textLayoutResult, columnMetrics) {
            val layout = textLayoutResult ?: return@LaunchedEffect
            if (columnMetrics.isEmpty()) return@LaunchedEffect
            val selection = value.selection
            if (selection.collapsed) {
                val lineIndex = layout.getLineForOffset(selection.start)
                val metrics = columnMetrics.getOrNull(lineIndex)
                if (metrics != null) {
                    println("[Debug] 激活列: 第 ${lineIndex + 1} 列 | 光标所在列宽: ${metrics.width}px | 总列数: ${columnMetrics.size}")
                }
            }
        }
    
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

        // 光标闪烁（有选区时不闪烁）
        val cursorAlpha = remember { Animatable(1f) }
        
        // 滚动条与悬停状态
        val scrollbarAlpha = remember { Animatable(0f) }
        val isHoveringBottom = remember { mutableStateOf(false) }
        
        LaunchedEffect(scrollOffset, isHoveringBottom.value) {
            if (maxScrollOffset > 0f) {
                if (isHoveringBottom.value) {
                    scrollbarAlpha.animateTo(0.8f, androidx.compose.animation.core.tween(200))
                } else {
                    scrollbarAlpha.snapTo(0.8f)
                    kotlinx.coroutines.delay(300)
                    scrollbarAlpha.animateTo(0f, androidx.compose.animation.core.tween(200))
                }
            }
        }
        
        // 缓存选区矩形
        val selectionRectData = remember(value.selection, textLayoutResult, columnMetrics) {
            val layout = textLayoutResult ?: return@remember emptyList<Triple<androidx.compose.ui.geometry.Rect, Int, Float>>()
            val sel = value.selection
            if (sel.collapsed) return@remember emptyList()
            
            val maxLen = layout.layoutInput.text.length
            val safeStart = sel.start.coerceIn(0, maxLen)
            val safeEnd = sel.end.coerceIn(0, maxLen)
            
            (safeStart until safeEnd).map { charIdx ->
                val lineIdx = layout.getLineForOffset(charIdx)
                val metrics = columnMetrics.getOrNull(lineIdx) ?: ColumnMetrics(0f, 0f, 0f)
                Triple(layout.getBoundingBox(charIdx), lineIdx, metrics.width)
            }
        }

        // 缓存列中心坐标
        val columnCenterXs = remember(columnMetrics) {
            val centers = FloatArray(columnMetrics.size)
            var runningX = 0f
            for (i in columnMetrics.indices) {
                val w = columnMetrics[i].width
                centers[i] = runningX + w / 2f + (i * columnSpacingPx)
                runningX += w
            }
            centers
        }
        
        // 坐标转换逻辑
        // 注意：通过 currentXxx.value 读取最新值，确保 pointerInput 闭包中不会读到旧值
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
    
            // 映射关系：x = centerX + metrics.inkCenter - y
            // 此处不再对 py 进行范围限制，允许 getOffsetForPosition 判定点击的具体侧边
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
    
            // 获取目标列坐标
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

            // 计算光标所在列的左边缘 and 右边缘（内容坐标，未减 scrollOffset）
            var colLeft = 0f
            for (k in 0 until lineIdx) {
                colLeft += (columnMetrics.getOrNull(k)?.width ?: 0f) + columnSpacingPx
            }
            val colRight = colLeft + (columnMetrics.getOrNull(lineIdx)?.width ?: 0f)

            val viewWidth = constraints.maxWidth.toFloat()

            scrollOffset = when {
                // 光标列右边缘超出视口右边
                colRight - scrollOffset > viewWidth ->
                    (colRight - viewWidth).coerceIn(0f, maxScrollOffset)
                // 光标列左边缘超出视口左边
                colLeft - scrollOffset < 0f ->
                    colLeft.coerceIn(0f, maxScrollOffset)
                // 在视口内，不动
                else -> scrollOffset
            }
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

            // 交互覆盖层
            val selectionAnchorState = remember { mutableStateOf(selectionAnchor) }
            selectionAnchorState.value = selectionAnchor
            
            // 使用 rememberUpdatedState 确保手势闭包能读到最新值，不因重组而重启
            val currentValueState = rememberUpdatedState(value)
            val currentOnValueChangeState = rememberUpdatedState(onValueChange)
            val currentLayoutState = rememberUpdatedState(textLayoutResult)
            val currentMetricsState = rememberUpdatedState(columnMetrics)
            val currentScrollOffsetState = rememberUpdatedState(scrollOffset)
            val currentMaxScrollOffsetState = rememberUpdatedState(maxScrollOffset)
            val currentSelectableState = rememberUpdatedState(selectable)

            
            val gestureState = remember(focusRequester, isHoveringBottom, selectionAnchorState) {
                VTextFieldGestureState(
                    value = currentValueState,
                    onValueChange = currentOnValueChangeState,
                    textLayoutResult = currentLayoutState,
                    columnMetrics = currentMetricsState,
                    scrollOffset = currentScrollOffsetState,

                    onScrollOffsetChange = { scrollOffset = it },
                    maxScrollOffset = currentMaxScrollOffsetState,
                    selectable = currentSelectableState,
                    focusRequester = focusRequester,
                    onShowContextMenu = { offset -> contextMenuOffset = offset; showContextMenu = true },
                    isHoveringBottom = isHoveringBottom,
                    selectionAnchor = selectionAnchorState,
                    visualToCharIdx = ::visualToCharIdx,
                    handlePosition = ::handlePosition
                )
            }
            
            LaunchedEffect(selectionAnchorState.value) { selectionAnchor = selectionAnchorState.value }

            Box(
                Modifier
                    .fillMaxSize()
                    .vTextPlatformGestures(gestureState)
            )

            // 上下文菜单（Desktop = DropdownMenu, Mobile = DropdownMenu positioned near selection）
            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false },
                offset = DpOffset(
                    x = with(density) { contextMenuOffset.x.toDp() },
                    y = with(density) { contextMenuOffset.y.toDp() }
                )
            ) {
                if (!value.selection.collapsed && selectable) {
                    DropdownMenuItem(
                        text = { Text("复制") },
                        onClick = {
                            clipboardManager.setText(AnnotatedString(
                                value.text.substring(value.selection.start, value.selection.end)
                            ))
                            showContextMenu = false
                        }
                    )
                    if (!readOnly) {
                        DropdownMenuItem(
                            text = { Text("剪切") },
                            onClick = {
                                val selected = value.text.substring(value.selection.start, value.selection.end)
                                clipboardManager.setText(AnnotatedString(selected))
                                val newText = value.text.removeRange(value.selection.start, value.selection.end)
                                onValueChange(TextFieldValue(newText, TextRange(value.selection.start)))
                                showContextMenu = false
                            }
                        )
                    }
                }
                if (!readOnly) {
                    DropdownMenuItem(
                        text = { Text("粘贴") },
                        onClick = {
                            val clip = clipboardManager.getText()?.text ?: ""
                            if (clip.isNotEmpty()) {
                                val s = value.selection.start; val e = value.selection.end
                                val newText = value.text.substring(0, s) + clip + value.text.substring(e)
                                onValueChange(TextFieldValue(newText, TextRange(s + clip.length)))
                            }
                            showContextMenu = false
                        }
                    )
                }
                if (selectable) {
                    DropdownMenuItem(
                        text = { Text("全选") },
                        onClick = {
                            onValueChange(value.copy(selection = TextRange(0, value.text.length)))
                            showContextMenu = false
                        }
                    )
                }
            }

            // 选区与光标绘制
            val layout = textLayoutResult
            if (isFocused && layout != null) {
                val maxLen = layout.layoutInput.text.length
                val safeStart = value.selection.start.coerceIn(0, maxLen)
                val safeEnd = value.selection.end.coerceIn(0, maxLen)

                Canvas(Modifier.fillMaxSize()) {
                    if (!value.selection.collapsed && selectionRectData.isNotEmpty()) {
                        // 选区高亮
                        val highlightColor = selectionColors.backgroundColor
                        
                        selectionRectData.forEach { (hBBox, lineIdx, colWidth) ->
                            val w = hBBox.right - hBBox.left
                            if (w > 0f) {
                                val centerX = if (lineIdx in columnCenterXs.indices) columnCenterXs[lineIdx] else 0f
                                val colCenterX = centerX - scrollOffset
                                
                                drawRect(
                                    color = highlightColor,
                                    topLeft = Offset(colCenterX - colWidth / 2f, hBBox.left),
                                    size = Size(colWidth, w)
                                )
                            }
                        }


                    } else if (safeStart <= maxLen) {
                        // 光标
                        val hRect = layout.getCursorRect(safeStart)
                        val lineIdx = layout.getLineForOffset(safeStart)
                        if (cursorAlpha.value > 0.5f) {
                            // 计算屏幕中心
                            var currentX = 0f
                            for (k in 0 until lineIdx) {
                                currentX += columnMetrics.getOrNull(k)?.width ?: 0f
                            }
                            val metrics = columnMetrics.getOrNull(lineIdx) ?: ColumnMetrics(0f, 0f, 0f)
                            val colHalfW = metrics.width / 2f
                            val colCenterX = currentX + colHalfW + (lineIdx * columnSpacingPx) - scrollOffset
                            val verticalOffset = 0f

                            translate(left = colCenterX, top = hRect.right + verticalOffset) {
                                drawLine(
                                    color = style.color,
                                    start = Offset(-colHalfW, 0f),
                                    end = Offset(colHalfW, 0f),
                                    strokeWidth = 2.dp.toPx()
                                )
                            }
                        }
                    }

                    // 绘制横向滑动条
                    if (scrollbarAlpha.value > 0f && maxScrollOffset > 0f) {
                        val viewWidth = size.width
                        val contentWidth = viewWidth + maxScrollOffset
                        val barWidth = ((viewWidth / contentWidth) * viewWidth).coerceAtLeast(32.dp.toPx())
                        val barX = (scrollOffset / maxScrollOffset) * (viewWidth - barWidth)
                        val barThickness = 4.dp.toPx()
                        val barBottomPadding = 2.dp.toPx()

                        drawRoundRect(
                            color = Color.Gray.copy(alpha = scrollbarAlpha.value),
                            topLeft = Offset(barX, size.height - barThickness - barBottomPadding),
                            size = Size(barWidth, barThickness),
                            cornerRadius = CornerRadius(barThickness / 2f)
                        )
                    }
                }
            }
        }
    }
}
