package xyz.mmtextkit

import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.isSpecified


internal data class ColumnMetrics(
    val width: Float,
    val inkCenter: Float,
    val layoutCenter: Float
)

internal data class VTextLayoutState(
    val result: TextLayoutResult,
    val metrics: List<ColumnMetrics>
)

internal fun TextLayoutResult.computeColumnMetrics(
    config: VTextInternalConfig,
    density: Density
): List<ColumnMetrics> {
    // 计算最大行高作为列宽参考值
    var maxLineHeight = 0f
    for (i in 0 until lineCount) {
        val height = getLineBottom(i) - getLineTop(i)
        if (height > maxLineHeight) maxLineHeight = height
    }

    return List(lineCount) { i ->
        val layoutTop = getLineTop(i)
        val layoutBottom = getLineBottom(i)
        val layoutCenter = (layoutTop + layoutBottom) / 2f
        val lineHeight = layoutBottom - layoutTop

        var minTop = Float.MAX_VALUE
        var maxBottom = -Float.MAX_VALUE

        var j = getLineStart(i)
        while (j < getLineEnd(i)) {
            // 处理代理对
            if (j > 0 && layoutInput.text[j].isLowSurrogate()) {
                j++
                continue
            }
            val rect = getBoundingBox(j)
            if (rect.height > 0) {
                minTop = minOf(minTop, rect.top)
                maxBottom = maxOf(maxBottom, rect.bottom)
            }
            j++
        }

        // 确定最终列宽
        val finalWidth = if (config.fixedWidth) maxLineHeight else lineHeight


        if (minTop == Float.MAX_VALUE) {
            ColumnMetrics(finalWidth, layoutCenter, layoutCenter)
        } else {
            val baseline = getLineBaseline(i)
            val trimmedTop = minTop + config.ascentTrim * (baseline - minTop)
            val inkCenter = (trimmedTop + maxBottom) / 2f
            ColumnMetrics(finalWidth, inkCenter, layoutCenter)
        }

    }
}


@Composable
internal fun VerticalText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    config: VTextInternalConfig,
    externalMetrics: List<ColumnMetrics>? = null,
    scrollOffset: Float = 0f,
    onTextLayout: (TextLayoutResult) -> Unit = {}
) {
    val density = LocalDensity.current
    val columnSpacingPx = with(density) { config.columnSpacing.toPx() }


    val textMeasurer = rememberTextMeasurer()

    val finalStyle = if (config.baselineShift != 0f) {
        style.copy(baselineShift = BaselineShift(config.baselineShift))
    } else style

    val normalizedStyle = finalStyle.withNormalizedLineHeight()


    val annotatedText = remember(text, config.verticalFontFamily) {
        buildAnnotatedString {
            var currentIndex = 0
            while (currentIndex < text.length) {
                val start = currentIndex
                val firstCP = codePointAt(text, start)
                if (firstCP == 0) { // low surrogate
                    currentIndex++
                    continue
                }
                val isVertical = isVerticalFontSystem(firstCP)

                // 合并同类语种区间以支持连写
                while (currentIndex < text.length) {
                    val cp = codePointAt(text, currentIndex)
                    if (cp == 0) {
                        currentIndex++
                        continue
                    }
                    if (isVerticalFontSystem(cp) != isVertical) break
                    currentIndex += if (cp > 0xFFFF) 2 else 1
                }

                val segment = text.substring(start, currentIndex)
                if (isVertical) {
                    withStyle(SpanStyle(fontFamily = config.verticalFontFamily)) {
                        append(segment)
                    }
                } else {
                    append(segment)
                }
            }
        }
    }

    // 文本布局测量
    var layoutState by remember { mutableStateOf<VTextLayoutState?>(null) }

    Spacer(
        modifier = modifier
            .layout { measurable, constraints ->
                val result = textMeasurer.measure(
                    text = annotatedText,
                    style = normalizedStyle,
                    constraints = Constraints(maxWidth = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity),
                    softWrap = true
                )


                onTextLayout(result)

                val metrics = externalMetrics ?: result.computeColumnMetrics(config, density)



                layoutState = VTextLayoutState(result, metrics)

                val spacingTotal = ((result.lineCount - 1) * columnSpacingPx).coerceAtLeast(0f)
                val verticalWidth = (metrics.sumOf { it.width.toDouble() }.toFloat() + spacingTotal).toInt()
                val verticalHeight = result.size.width

                val finalWidth = verticalWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
                val finalHeight = verticalHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

                val placeable = measurable.measure(Constraints.fixed(finalWidth, finalHeight))

                layout(finalWidth, finalHeight) {
                    placeable.place(0, 0)
                }
            }
            .drawBehind {
                val state = layoutState ?: return@drawBehind
                val layoutResult = state.result
                val metricsList = state.metrics

                // 处理横向滚动偏移
                translate(left = -scrollOffset, top = 0f) {
                    val verticalOffset = 0f
                    var currentX = 0f

                    val viewportWidth = size.width

                    for (i in 0 until layoutResult.lineCount) {
                        val metrics = metricsList.getOrNull(i) ?: continue
                        if (metrics.width <= 0f) {
                            // 忽略空列
                            continue
                        }

                        val halfW = metrics.width / 2f
                        val targetCenterX = currentX + halfW + (i * columnSpacingPx)

                        // 视口裁剪
                        // 如果这一列完全在可视区域外，直接跳过
                        val screenLeft = targetCenterX - halfW - scrollOffset
                        val screenRight = targetCenterX + halfW - scrollOffset
                        if (screenRight < 0 || screenLeft > viewportWidth) {
                            currentX += metrics.width
                            continue
                        }

                        val lineStart = layoutResult.getLineStart(i)
                        val lineEnd = layoutResult.getLineEnd(i)
                        val inkCenter = metrics.inkCenter

                        // 使用布局边界进行裁剪
                        val clipTop = layoutResult.getLineTop(i)
                        val clipBottom = layoutResult.getLineBottom(i)

                        translate(left = targetCenterX + inkCenter, top = verticalOffset) {
                            rotate(degrees = 90f, pivot = Offset.Zero) {
                                clipRect(
                                    left = 0f,
                                    top = clipTop,
                                    right = layoutResult.size.width.toFloat(),
                                    bottom = clipBottom,
                                    clipOp = ClipOp.Intersect
                                ) {
                                    // 识别直立字符
                                    var hasUpright = false
                                    var j = lineStart
                                    while (j < lineEnd) {
                                        if (shouldBeUpright(text, j)) {
                                            hasUpright = true
                                            break
                                        }
                                        // 跳过 surrogate pair 的 low surrogate
                                        if (text[j].isHighSurrogate() && j + 1 < lineEnd) {
                                            j += 2
                                        } else {
                                            j++
                                        }
                                    }

                                    if (!hasUpright) {
                                        // 纯横排字符行整体绘制
                                        drawText(layoutResult)
                                    } else {
                                        // 混合排版处理
                                        j = lineStart
                                        while (j < lineEnd) {
                                            if (text[j].isLowSurrogate()) {
                                                j++
                                                continue
                                            }

                                            val codePoint = codePointAt(text, j)
                                            if (codePoint == 0xFE0F || codePoint == 0x200D) {
                                                j++
                                                continue
                                            }

                                            if (shouldBeUprightCodePoint(codePoint)) {
                                                // 直立字符：单独旋转绘制
                                                val rect = layoutResult.getBoundingBox(j)
                                                if (rect.width > 0 && rect.height > 0) {
                                                    rotate(degrees = -90f, pivot = rect.center) {
                                                        clipRect(rect.left, rect.top, rect.right, rect.bottom) {
                                                            drawText(layoutResult)
                                                        }
                                                    }
                                                }
                                                j += if (codePoint > 0xFFFF) 2 else 1
                                            } else {
                                                // 非直立字符（满文/蒙古文/拉丁文）：合并连续区间一次性绘制，确保连写不被切割
                                                var minLeft = Float.MAX_VALUE
                                                var minTop = Float.MAX_VALUE
                                                var maxRight = -Float.MAX_VALUE
                                                var maxBottom = -Float.MAX_VALUE
                                                var hasValidRect = false

                                                while (j < lineEnd) {
                                                    val cp = codePointAt(text, j)
                                                    if (cp == 0) {
                                                        j++
                                                        continue
                                                    }
                                                    if (cp == 0xFE0F || cp == 0x200D) {
                                                        j++
                                                        continue
                                                    }
                                                    if (shouldBeUprightCodePoint(cp)) break

                                                    val rect = layoutResult.getBoundingBox(j)
                                                    if (rect.width > 0 && rect.height > 0) {
                                                        minLeft = minOf(minLeft, rect.left)
                                                        minTop = minOf(minTop, rect.top)
                                                        maxRight = maxOf(maxRight, rect.right)
                                                        maxBottom = maxOf(maxBottom, rect.bottom)
                                                        hasValidRect = true
                                                    }
                                                    j += if (cp > 0xFFFF) 2 else 1
                                                }

                                                if (hasValidRect) {
                                                    clipRect(minLeft, minTop, maxRight, maxBottom) {
                                                        drawText(layoutResult)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        currentX += metrics.width
                    }
                }
            }
    )
}

private fun TextStyle.withNormalizedLineHeight(): TextStyle {
    return if (this.lineHeight.isSpecified) {
        this
    } else {
        this.copy(
            lineHeight = this.fontSize * 1.6f
        )
    }
}

/**
 * Unicode 码点解码，支持代理对
 */
private fun codePointAt(text: String, index: Int): Int {
    val char = text[index]
    return if (char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
        (char.code - 0xD800 shl 10) + (text[index + 1].code - 0xDC00) + 0x10000
    } else if (char.isLowSurrogate()) {
        0
    } else {
        char.code
    }
}

/**
 * 判断特定索引处的字符是否需要直立显示
 */
private fun shouldBeUpright(text: String, index: Int): Boolean {
    val codePoint = codePointAt(text, index)
    if (codePoint == 0) return false // low surrogate 或无效字符
    return shouldBeUprightCodePoint(codePoint)
}

/**
 * 判断码点是否需要直立显示
 */
private fun shouldBeUprightCodePoint(codePoint: Int): Boolean {
    return when {
        // CJK 统一汉字及扩展
        codePoint in 0x4E00..0x9FFF -> true   // CJK 统一汉字
        codePoint in 0x3400..0x4DBF -> true   // CJK 统一汉字扩展 A
        codePoint in 0x20000..0x3134F -> true  // CJK 统一汉字扩展 B-F
        codePoint in 0xF900..0xFAFF -> true   // CJK 兼容汉字

        // CJK 部首与符号
        codePoint in 0x2E80..0x2FDF -> true   // CJK 部首
        codePoint in 0x31C0..0x31EF -> true   // CJK 笔画
        codePoint in 0x3190..0x319F -> true   // 竖排文字标号
        codePoint in 0xFE30..0xFE4F -> true   // CJK 兼容形式

        // 韩文
        codePoint in 0xAC00..0xD7AF -> true   // 韩文音节
        codePoint in 0x1100..0x11FF -> true   // 韩文字母
        codePoint in 0xA960..0xA97F -> true   // 韩文字母扩展 A
        codePoint in 0xD7B0..0xD7FF -> true   // 韩文字母扩展 B

        // 日文假名
        codePoint in 0x3040..0x309F -> true   // 平假名
        codePoint in 0x30A0..0x30FF -> true   // 片假名
        codePoint in 0x31F0..0x31FF -> true   // 片假名语音扩展
        codePoint in 0x3100..0x312F -> true   // 注音符号

        // CJK 标点与符号
        codePoint in 0x3000..0x303F -> true   // CJK 标点符号
        codePoint in 0xFF00..0xFFEF -> true   // 全角 ASCII / 全角标点
        codePoint in 0x3200..0x33FF -> true   // CJK 兼容字符

        // 蒙古文体系/八思巴文是原生竖排系统，在 Unicode 中侧卧存储，
        // 在竖排时应当跟随行旋转 (90°)，而不应进行直立补偿 (-90°)，故返回 false。
        codePoint in 0x1800..0x18AF -> false
        codePoint in 0x11660..0x1167F -> false
        codePoint in 0xA840..0xA87F -> false

        // Emoji 与符号
        codePoint in 0x1F000..0x1FBFF -> true  // 大部分现代 Emoji (表情、物体、符号、标志等)
        codePoint in 0x2300..0x2BFF -> true    // 常用符号区 (含心脏、箭头、星号、技术符号等)
        codePoint in 0x1F1E6..0x1F1FF -> true  // 国旗区域指示符号

        else -> false
    }
}
