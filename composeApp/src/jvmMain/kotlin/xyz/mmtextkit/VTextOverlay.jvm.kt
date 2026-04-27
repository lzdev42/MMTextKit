package xyz.mmtextkit

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
internal actual fun VTextFieldOverlay(
    state: VTextFieldOverlayState,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val clipboardManager = LocalClipboardManager.current

    // 菜单状态（Desktop 特有）
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(Offset.Zero) }

    // 滚动条与悬停状态（Desktop 特有）
    val scrollbarAlpha = remember { Animatable(0f) }
    val isHoveringBottom = remember { mutableStateOf(false) }

    val scrollOffset = state.scrollOffset
    val maxScrollOffset = state.maxScrollOffset

    LaunchedEffect(scrollOffset.value, isHoveringBottom.value) {
        if (maxScrollOffset.value > 0f) {
            if (isHoveringBottom.value) {
                scrollbarAlpha.animateTo(0.8f, tween(200))
            } else {
                scrollbarAlpha.snapTo(0.8f)
                delay(300)
                scrollbarAlpha.animateTo(0f, tween(200))
            }
        }
    }

    // 缓存选区矩形
    val selectionRectData = remember(state.value.value.selection, state.textLayoutResult.value, state.columnMetrics.value) {
        val layout = state.textLayoutResult.value ?: return@remember emptyList<Triple<Rect, Int, Float>>()
        val sel = state.value.value.selection
        if (sel.collapsed) return@remember emptyList()

        val maxLen = layout.layoutInput.text.length
        val safeStart = sel.start.coerceIn(0, maxLen)
        val safeEnd = sel.end.coerceIn(0, maxLen)

        (safeStart until safeEnd).map { charIdx ->
            val lineIdx = layout.getLineForOffset(charIdx)
            val metrics = state.columnMetrics.value.getOrNull(lineIdx) ?: ColumnMetrics(0f, 0f, 0f)
            Triple(layout.getBoundingBox(charIdx), lineIdx, metrics.width)
        }
    }

    // 缓存列中心坐标
    val columnCenterXs = remember(state.columnMetrics.value) {
        val metrics = state.columnMetrics.value
        val spacingPx = state.columnSpacingPx.value
        val centers = FloatArray(metrics.size)
        var runningX = 0f
        for (i in metrics.indices) {
            val w = metrics[i].width
            centers[i] = runningX + w / 2f + (i * spacingPx)
            runningX += w
        }
        centers
    }

    // 手势处理
    Box(
        modifier
            .pointerHoverIcon(PointerIcon.Default)
            // ─── 鼠标点击 / 双击 / 拖拽选取 ───
            .pointerInput(state.selectable.value) {
                var lastTapTime = 0L
                var lastTapPosition = Offset.Zero

                awaitEachGesture {
                    val down = awaitFirstDown().also { it.consume() }
                    val now = down.uptimeMillis

                    // 双击判定
                    val isDoubleTap = (now - lastTapTime < 350L) &&
                        (down.position - lastTapPosition).getDistance() < 40f
                    lastTapTime = now
                    lastTapPosition = down.position

                    state.focusRequester.requestFocus()

                    if (isDoubleTap && state.selectable.value) {
                        val v = state.value.value
                        state.onValueChange.value(v.copy(selection = TextRange(0, v.text.length)))
                        state.selectionAnchor.value = 0
                        return@awaitEachGesture
                    }

                    // 单击 / 拖拽开始
                    val startIdx = state.visualToCharIdx(down.position)
                    state.selectionAnchor.value = startIdx
                    state.onValueChange.value(state.value.value.copy(selection = TextRange(startIdx)))

                    var dragging = false
                    var lastX = down.position.x

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        val delta = change.position - down.position

                        if (!dragging && (abs(delta.x) > 4f || abs(delta.y) > 4f)) {
                            dragging = true
                        }

                        if (!change.pressed) break

                        // 鼠标拖拽选取
                        if (dragging && state.selectable.value) {
                            val curIdx = state.visualToCharIdx(change.position)
                            state.onValueChange.value(state.value.value.copy(
                                selection = TextRange(minOf(startIdx, curIdx), maxOf(startIdx, curIdx))
                            ))
                            change.consume()
                        } else {
                            // 非选取状态下的普通拖拽（横向滚动）
                            val dx = lastX - change.position.x
                            state.onScrollOffsetChange((state.scrollOffset.value + dx).coerceIn(0f, state.maxScrollOffset.value))
                            change.consume()
                        }
                        lastX = change.position.x
                    }
                }
            }
            // ─── 滚轮处理 (Mac 双指滑动 / Win & Linux 滚轮) ───
            .pointerInput(state.maxScrollOffset.value) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val delta = event.changes.first().scrollDelta
                            val totalDelta = delta.y + delta.x
                            val newOffset = (state.scrollOffset.value + totalDelta * 20f).coerceIn(0f, state.maxScrollOffset.value)
                            if (newOffset != state.scrollOffset.value) {
                                state.onScrollOffsetChange(newOffset)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }
            // ─── 底部悬停检测（用于显示滚动条）───
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move) {
                            val pos = event.changes.first().position
                            isHoveringBottom.value = pos.y > size.height - 24.dp.toPx()
                        } else if (event.type == PointerEventType.Exit) {
                            isHoveringBottom.value = false
                        }
                    }
                }
            }
            // ─── 右键菜单 ───
            .pointerInput(Unit) {
                awaitEachGesture {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        val pos = event.changes.firstOrNull()?.position ?: return@awaitEachGesture
                        contextMenuOffset = pos
                        showContextMenu = true
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        // 上下文菜单（Desktop 标准交互）
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = DpOffset(
                x = with(density) { contextMenuOffset.x.toDp() },
                y = with(density) { contextMenuOffset.y.toDp() }
            )
        ) {
            val currentValue = state.value.value
            if (!currentValue.selection.collapsed && state.selectable.value) {
                DropdownMenuItem(
                    text = { Text("复制") },
                    onClick = {
                        clipboardManager.setText(AnnotatedString(
                            currentValue.text.substring(currentValue.selection.start, currentValue.selection.end)
                        ))
                        showContextMenu = false
                    }
                )
                if (!state.readOnly.value) {
                    DropdownMenuItem(
                        text = { Text("剪切") },
                        onClick = {
                            val selected = currentValue.text.substring(currentValue.selection.start, currentValue.selection.end)
                            clipboardManager.setText(AnnotatedString(selected))
                            val newText = currentValue.text.removeRange(currentValue.selection.start, currentValue.selection.end)
                            state.onValueChange.value(TextFieldValue(newText, TextRange(currentValue.selection.start)))
                            showContextMenu = false
                        }
                    )
                }
            }
            if (!state.readOnly.value) {
                DropdownMenuItem(
                    text = { Text("粘贴") },
                    onClick = {
                        val clip = clipboardManager.getText()?.text ?: ""
                        if (clip.isNotEmpty()) {
                            val s = currentValue.selection.start; val e = currentValue.selection.end
                            val newText = currentValue.text.substring(0, s) + clip + currentValue.text.substring(e)
                            state.onValueChange.value(TextFieldValue(newText, TextRange(s + clip.length)))
                        }
                        showContextMenu = false
                    }
                )
            }
            if (state.selectable.value) {
                DropdownMenuItem(
                    text = { Text("全选") },
                    onClick = {
                        state.onValueChange.value(currentValue.copy(selection = TextRange(0, currentValue.text.length)))
                        showContextMenu = false
                    }
                )
            }
        }

        // 选区与光标绘制
        val layout = state.textLayoutResult.value
        if (state.isFocused.value && layout != null) {
            val maxLen = layout.layoutInput.text.length
            val safeStart = state.value.value.selection.start.coerceIn(0, maxLen)
            val safeEnd = state.value.value.selection.end.coerceIn(0, maxLen)
            val columnMetrics = state.columnMetrics.value
            val columnSpacingPx = state.columnSpacingPx.value
            val scrollOff = state.scrollOffset.value

            Canvas(Modifier.fillMaxSize()) {
                if (!state.value.value.selection.collapsed && selectionRectData.isNotEmpty()) {
                    // 选区高亮
                    val highlightColor = state.selectionColors.backgroundColor

                    selectionRectData.forEach { (hBBox, lineIdx, colWidth) ->
                        val w = hBBox.right - hBBox.left
                        if (w > 0f) {
                            val centerX = if (lineIdx in columnCenterXs.indices) columnCenterXs[lineIdx] else 0f
                            val colCenterX = centerX - scrollOff

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
                    if (state.cursorAlpha.value > 0.5f) {
                        var currentX = 0f
                        for (k in 0 until lineIdx) {
                            currentX += columnMetrics.getOrNull(k)?.width ?: 0f
                        }
                        val metrics = columnMetrics.getOrNull(lineIdx) ?: ColumnMetrics(0f, 0f, 0f)
                        val colHalfW = metrics.width / 2f
                        val colCenterX = currentX + colHalfW + (lineIdx * columnSpacingPx) - scrollOff
                        val verticalOffset = 0f

                        translate(left = colCenterX, top = hRect.right + verticalOffset) {
                            drawLine(
                                color = state.style.value.color,
                                start = Offset(-colHalfW, 0f),
                                end = Offset(colHalfW, 0f),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                    }
                }

                // 绘制横向滑动条
                if (scrollbarAlpha.value > 0f && maxScrollOffset.value > 0f) {
                    val viewWidth = size.width
                    val contentWidth = viewWidth + maxScrollOffset.value
                    val barWidth = ((viewWidth / contentWidth) * viewWidth).coerceAtLeast(32.dp.toPx())
                    val barX = (scrollOff / maxScrollOffset.value) * (viewWidth - barWidth)
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
