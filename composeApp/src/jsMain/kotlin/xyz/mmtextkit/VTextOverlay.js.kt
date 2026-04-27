package xyz.mmtextkit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private const val LONG_PRESS_TIMEOUT_MS = 500L

@Composable
internal actual fun VTextFieldOverlay(
    state: VTextFieldOverlayState,
    modifier: Modifier,
) {
    val density = LocalDensity.current

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

    // 鼠标+触摸混合手势处理
    Box(
        modifier
            // ─── 点击 / 双击 / 拖拽选取 ───
            .pointerInput(state.selectable.value) {
                var lastTapTime = 0L
                var lastTapPosition = Offset.Zero

                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    state.focusRequester.requestFocus()

                    val now = down.uptimeMillis
                    val isDoubleTap = (now - lastTapTime < 350L) &&
                        (down.position - lastTapPosition).getDistance() < 40f
                    lastTapTime = now
                    lastTapPosition = down.position

                    if (isDoubleTap && state.selectable.value) {
                        val v = state.value.value
                        state.onValueChange.value(v.copy(selection = TextRange(0, v.text.length)))
                        state.selectionAnchor.value = 0
                        return@awaitEachGesture
                    }

                    val downTime = down.uptimeMillis
                    val startPos = down.position
                    val startIdx = state.visualToCharIdx(startPos)
                    state.selectionAnchor.value = startIdx
                    state.onValueChange.value(state.value.value.copy(selection = TextRange(startIdx)))
        
                    var dragging = false
                    var longPressTriggered = false
                    var lastX = startPos.x
        
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        val delta = change.position - startPos
        
                        if (!dragging && (abs(delta.x) > 4f || abs(delta.y) > 4f)) {
                            dragging = true
                        }
        
                        // 长按检测：未拖拽且超过阈值时间
                        if (!dragging && !longPressTriggered &&
                            change.uptimeMillis - downTime >= LONG_PRESS_TIMEOUT_MS
                        ) {
                            longPressTriggered = true
                            state.onContextMenuRequest.value?.invoke(startPos)
                        }
        
                        if (!change.pressed) break
        
                        if (dragging && state.selectable.value) {
                            val curIdx = state.visualToCharIdx(change.position)
                            state.onValueChange.value(state.value.value.copy(
                                selection = TextRange(minOf(startIdx, curIdx), maxOf(startIdx, curIdx))
                            ))
                            change.consume()
                        } else {
                            val dx = lastX - change.position.x
                            state.onScrollOffsetChange((state.scrollOffset.value + dx).coerceIn(0f, state.maxScrollOffset.value))
                            change.consume()
                        }
                        lastX = change.position.x
                    }
                }
            }
            // ─── 滚轮处理 ───
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
    ) {
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
            }
        }
    }
}
