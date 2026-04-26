package xyz.columnscript.columnscript.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal actual fun Modifier.vTextPlatformGestures(state: VTextFieldGestureState): Modifier = this
    .pointerHoverIcon(PointerIcon.Default)
    // ─── 鼠标点击 / 双击 / 拖拽选取 ───
    .pointerInput(state.selectable.value) {
        var lastTapTime = 0L
        var lastTapPosition = Offset.Zero

        awaitEachGesture {
            val down = awaitFirstDown().also { it.consume() }
            val now = System.currentTimeMillis()
            
            // 双击判定
            val isDoubleTap = (now - lastTapTime < 350) &&
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
                    // 汇总纵向和横向滚动量（JVM 上滚轮通常映射为横向滚动偏移）
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
                    state.isHoveringBottom.value = pos.y > size.height - 24.dp.toPx()
                } else if (event.type == PointerEventType.Exit) {
                    state.isHoveringBottom.value = false
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
                state.onShowContextMenu(pos)
                event.changes.forEach { it.consume() }
            }
        }
    }
