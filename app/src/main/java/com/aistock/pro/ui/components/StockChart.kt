package com.aistock.pro.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistock.pro.analysis.Fmt
import com.aistock.pro.analysis.IndicatorSet
import com.aistock.pro.data.Bar
import com.aistock.pro.ui.theme.AppColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class SubPanel(val label: String) { VOLUME("거래량"), RSI("RSI"), MACD("MACD"), STOCH("스토캐스틱"), ATR("ATR") }

data class Overlays(
    val ma: Set<Int> = setOf(5, 20, 60, 120),
    val bb: Boolean = false,
    val vwap: Boolean = false,
    val levels: Boolean = true,
    val zones: Boolean = true,
    val trend: Boolean = true,
)

/** 차트에 그릴 분석 결과 (지지/저항, 매수/매도 구간, 손절선) */
data class ChartLevels(
    val supports: List<Double>,
    val resistances: List<Double>,
    val buyHigh: Double?, val buyLow: Double?,
    val sellLow: Double?, val sellHigh: Double?,
    val stop: Double?,
)

val MA_COLORS = mapOf(
    5 to Color(0xFFFACC15), 10 to Color(0xFFF472B6), 20 to Color(0xFFF97316),
    60 to Color(0xFF22C55E), 120 to Color(0xFFA78BFA), 200 to Color(0xFF94A3B8),
)

/**
 * 터치 확대·축소(두 손가락), 좌우 이동(한 손가락 가로), 탭(십자선 정보) 지원 캔들 차트.
 * 세로 방향 드래그는 화면 스크롤로 넘겨 세로모드에서 자연스럽게 스크롤됩니다.
 */
@Composable
fun StockChart(
    bars: List<Bar>,
    intraday: Boolean,
    zone: ZoneId,
    kr: Boolean,
    overlays: Overlays,
    sub: SubPanel,
    levels: ChartLevels?,
    modifier: Modifier = Modifier,
    height: Dp = 380.dp,
) {
    if (bars.isEmpty()) return
    val ind = remember(bars) { IndicatorSet(bars, intraday, zone) }
    val n = bars.size
    var visible by remember(bars) { mutableFloatStateOf(min(if (intraday) 120 else 100, n).toFloat()) }
    var offset by remember(bars) { mutableFloatStateOf(0f) }
    var selected by remember(bars) { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val axisWpx = with(density) { 66.dp.toPx() }
    val textPx = with(density) { 10.sp.toPx() }
    val paint = remember(textPx) { Paint().apply { isAntiAlias = true; textSize = textPx; color = AppColors.Sub.toArgb() } }
    val boldPaint = remember(textPx) { Paint().apply { isAntiAlias = true; textSize = textPx; color = Color.White.toArgb(); isFakeBoldText = true } }
    val dateFmt = remember(intraday, zone) { DateTimeFormatter.ofPattern(if (intraday) "MM/dd HH:mm" else "yy/MM/dd").withZone(zone) }
    val minVisible = min(15, n).toFloat()

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(n) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var decided = false
                    var horizontal = false
                    var acc = Offset.Zero
                    val slop = viewConfiguration.touchSlop
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        val bw = (size.width - axisWpx) / visible
                        if (pressed.size >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f) visible = (visible / zoom).coerceIn(minVisible, n.toFloat())
                            offset = (offset + pan.x / bw).coerceIn(0f, max(0f, n - visible))
                            event.changes.forEach { it.consume() }
                            decided = true; horizontal = true
                        } else if (pressed.size == 1) {
                            val ch = pressed[0]
                            val d = ch.positionChange()
                            if (!decided) {
                                acc += d
                                if (abs(acc.x) > slop || abs(acc.y) > slop) { decided = true; horizontal = abs(acc.x) > abs(acc.y) }
                            }
                            if (decided && horizontal) {
                                offset = (offset + d.x / bw).coerceIn(0f, max(0f, n - visible))
                                ch.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(n) {
                detectTapGestures { pos ->
                    val count = visible.roundToInt().coerceIn(1, n)
                    val end = (n - offset.roundToInt()).coerceIn(count, n)
                    val start = end - count
                    val bw = (size.width - axisWpx) / count
                    val idx = start + (pos.x / bw).toInt()
                    selected = if (pos.x > size.width - axisWpx || idx == selected) -1 else idx.coerceIn(start, end - 1)
                }
            }
    ) {
        val plotW = size.width - axisWpx
        val dateH = 16.dp.toPx()
        val mainH = size.height * 0.68f
        val subTop = mainH + dateH + 4.dp.toPx()
        val subH = size.height - subTop
        val count = visible.roundToInt().coerceIn(1, n)
        val end = (n - offset.roundToInt()).coerceIn(count, n)
        val start = end - count
        val bw = plotW / count

        // ── 가격 범위 ──
        var hi = Double.NEGATIVE_INFINITY; var lo = Double.POSITIVE_INFINITY
        for (i in start until end) { hi = max(hi, bars[i].h); lo = min(lo, bars[i].l) }
        fun incl(v: Double) { if (!v.isNaN()) { hi = max(hi, v); lo = min(lo, v) } }
        for (i in start until end) {
            overlays.ma.forEach { incl(ind.ma[it]?.get(i) ?: Double.NaN) }
            if (overlays.bb) { incl(ind.bb.upper[i]); incl(ind.bb.lower[i]) }
        }
        val pad = (hi - lo) * 0.06 + 1e-9
        hi += pad; lo -= pad
        fun x(i: Int) = ((i - start) + 0.5f) * bw
        fun y(p: Double) = ((hi - p) / (hi - lo) * mainH).toFloat()

        // ── 격자 + 가격축 ──
        for (k in 0..4) {
            val p = lo + (hi - lo) * k / 4
            val yy = y(p)
            drawLine(AppColors.Line, Offset(0f, yy), Offset(plotW, yy), 1f)
            drawContext.canvas.nativeCanvas.drawText(Fmt.num(p, kr), plotW + 6f, yy + textPx / 3, paint)
        }

        clipRect(0f, 0f, plotW, mainH) {
            // ── 매수/매도 구간 ──
            if (overlays.zones && levels != null) {
                if (levels.buyHigh != null && levels.buyLow != null)
                    drawRect(AppColors.Up.copy(alpha = 0.10f), Offset(0f, y(levels.buyHigh)), Size(plotW, y(levels.buyLow) - y(levels.buyHigh)))
                if (levels.sellLow != null && levels.sellHigh != null)
                    drawRect(AppColors.Down.copy(alpha = 0.10f), Offset(0f, y(levels.sellHigh)), Size(plotW, y(levels.sellLow) - y(levels.sellHigh)))
            }
            // ── 지지/저항/손절선 ──
            if (overlays.levels && levels != null) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                levels.supports.forEach { drawLine(AppColors.Teal, Offset(0f, y(it)), Offset(plotW, y(it)), 2f, pathEffect = dash) }
                levels.resistances.forEach { drawLine(AppColors.Orange, Offset(0f, y(it)), Offset(plotW, y(it)), 2f, pathEffect = dash) }
                levels.stop?.let { drawLine(Color(0xFFE11D48), Offset(0f, y(it)), Offset(plotW, y(it)), 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))) }
            }
            // ── 볼린저밴드 ──
            if (overlays.bb) {
                series(ind.bb.upper, start, end, ::x, ::y, AppColors.Sub.copy(alpha = 0.8f))
                series(ind.bb.mid, start, end, ::x, ::y, AppColors.Sub.copy(alpha = 0.5f))
                series(ind.bb.lower, start, end, ::x, ::y, AppColors.Sub.copy(alpha = 0.8f))
            }
            // ── 캔들 ──
            val bodyW = max(1f, bw * 0.68f)
            for (i in start until end) {
                val b = bars[i]
                val up = b.c >= b.o
                val col = if (up) AppColors.Up else AppColors.Down
                val cx = x(i)
                drawLine(col, Offset(cx, y(b.h)), Offset(cx, y(b.l)), max(1f, bw * 0.12f))
                val top = y(max(b.o, b.c)); val bot = y(min(b.o, b.c))
                drawRect(col, Offset(cx - bodyW / 2, top), Size(bodyW, max(1.5f, bot - top)))
            }
            // ── 이동평균 ──
            overlays.ma.sorted().forEach { m -> ind.ma[m]?.let { series(it, start, end, ::x, ::y, MA_COLORS[m] ?: Color.White) } }
            if (overlays.vwap) series(ind.vwap, start, end, ::x, ::y, Color(0xFF38BDF8), 2.5f)
            // ── 추세선 ──
            if (overlays.trend) ind.trendLines.forEach { tl ->
                val a = max(tl.i1, start); val bIdx = end - 1
                if (bIdx > a) drawLine(if (tl.up) AppColors.Green else AppColors.Purple,
                    Offset(x(a), y(tl.valueAt(a))), Offset(x(bIdx), y(tl.valueAt(bIdx))), 2.5f)
            }
        }
        // ── 현재가 표시 ──
        val last = bars[end - 1].c
        val ly = y(last).coerceIn(0f, mainH)
        drawRect(if (bars[end - 1].c >= bars[end - 1].o) AppColors.Up else AppColors.Down, Offset(plotW + 2f, ly - textPx * 0.8f), Size(axisWpx - 2f, textPx * 1.6f))
        drawContext.canvas.nativeCanvas.drawText(Fmt.num(last, kr), plotW + 6f, ly + textPx / 3, boldPaint)

        // ── 날짜 ──
        listOf(start, (start + end - 1) / 2, end - 1).distinct().forEach { i ->
            val txt = dateFmt.format(Instant.ofEpochSecond(bars[i].t))
            val w = paint.measureText(txt)
            val xx = (x(i) - w / 2).coerceIn(0f, plotW - w)
            drawContext.canvas.nativeCanvas.drawText(txt, xx, mainH + dateH - 3f, paint)
        }

        // ── 보조지표 ──
        drawLine(AppColors.Line, Offset(0f, subTop), Offset(plotW, subTop), 1f)
        drawSub(sub, ind, start, end, ::x, subTop, subH, plotW, paint, textPx, kr)

        // ── 십자선 ──
        if (selected in start until end) {
            val b = bars[selected]
            val cx = x(selected)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, size.height), 1f)
            val info = "${dateFmt.format(Instant.ofEpochSecond(b.t))}  시 ${Fmt.num(b.o, kr)} 고 ${Fmt.num(b.h, kr)} 저 ${Fmt.num(b.l, kr)} 종 ${Fmt.num(b.c, kr)}"
            drawRect(Color(0xCC0B0F17), Offset(0f, 0f), Size(plotW, textPx * 1.9f))
            drawContext.canvas.nativeCanvas.drawText(info, 6f, textPx * 1.3f, boldPaint)
        }
    }
}

private fun DrawScope.series(
    v: DoubleArray, start: Int, end: Int, x: (Int) -> Float, y: (Double) -> Float, color: Color, width: Float = 2f,
) {
    val path = Path()
    var moved = false
    for (i in start until end) {
        val p = v[i]
        if (p.isNaN()) { moved = false; continue }
        if (!moved) { path.moveTo(x(i), y(p)); moved = true } else path.lineTo(x(i), y(p))
    }
    drawPath(path, color, style = Stroke(width))
}

private fun DrawScope.drawSub(
    sub: SubPanel, ind: IndicatorSet, start: Int, end: Int, x: (Int) -> Float,
    top: Float, h: Float, plotW: Float, paint: Paint, textPx: Float, kr: Boolean,
) {
    fun rangeOf(vararg arrs: DoubleArray): Pair<Double, Double> {
        var hi = Double.NEGATIVE_INFINITY; var lo = Double.POSITIVE_INFINITY
        arrs.forEach { a -> for (i in start until end) if (!a[i].isNaN()) { hi = max(hi, a[i]); lo = min(lo, a[i]) } }
        if (hi == Double.NEGATIVE_INFINITY) return 0.0 to 1.0
        if (hi == lo) { hi += 1; lo -= 1 }
        return lo to hi
    }
    val label: String
    when (sub) {
        SubPanel.VOLUME -> {
            val (_, hi) = rangeOf(ind.v)
            val yv = { v: Double -> (top + h - v / hi * h).toFloat() }
            val bw = plotW / (end - start)
            for (i in start until end) {
                val up = ind.c[i] >= ind.bars[i].o
                drawRect((if (up) AppColors.Up else AppColors.Down).copy(alpha = 0.7f), Offset(x(i) - bw * 0.34f, yv(ind.v[i])), Size(max(1f, bw * 0.68f), top + h - yv(ind.v[i])))
            }
            series(ind.volMa20, start, end, x, yv, AppColors.Gold, 1.8f)
            label = "거래량 ${Fmt.volume(ind.v[end - 1], kr)} · 20일 평균선(노랑)"
        }
        SubPanel.RSI -> {
            val yv = { v: Double -> (top + h - v / 100 * h).toFloat() }
            listOf(30.0, 70.0).forEach { lv -> drawLine(AppColors.Line, Offset(0f, yv(lv)), Offset(plotW, yv(lv)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
            series(ind.rsi, start, end, x, yv, AppColors.Purple)
            label = "RSI(14) ${"%.1f".format(ind.rsi[end - 1])} · 70 과매수 / 30 과매도"
        }
        SubPanel.MACD -> {
            val (lo0, hi0) = rangeOf(ind.macd.macd, ind.macd.signal, ind.macd.hist)
            val m = max(abs(lo0), abs(hi0))
            val yv = { v: Double -> (top + h / 2 - v / m * h / 2).toFloat() }
            val bw = plotW / (end - start)
            for (i in start until end) {
                val hv = ind.macd.hist[i]; if (hv.isNaN()) continue
                drawRect((if (hv >= 0) AppColors.Up else AppColors.Down).copy(alpha = 0.6f),
                    Offset(x(i) - bw * 0.3f, min(yv(hv), yv(0.0))), Size(max(1f, bw * 0.6f), abs(yv(hv) - yv(0.0))))
            }
            series(ind.macd.macd, start, end, x, yv, AppColors.Gold)
            series(ind.macd.signal, start, end, x, yv, AppColors.Teal)
            label = "MACD(12,26,9) 노랑 MACD · 청록 시그널"
        }
        SubPanel.STOCH -> {
            val yv = { v: Double -> (top + h - v / 100 * h).toFloat() }
            listOf(20.0, 80.0).forEach { lv -> drawLine(AppColors.Line, Offset(0f, yv(lv)), Offset(plotW, yv(lv)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
            series(ind.stoch.k, start, end, x, yv, AppColors.Gold)
            series(ind.stoch.d, start, end, x, yv, AppColors.Teal)
            label = "스토캐스틱(14,3,3) %K ${"%.1f".format(ind.stoch.k[end - 1])} / %D ${"%.1f".format(ind.stoch.d[end - 1])}"
        }
        SubPanel.ATR -> {
            val (lo, hi) = rangeOf(ind.atr)
            val yv = { v: Double -> (top + h - (v - lo) / (hi - lo) * h).toFloat() }
            series(ind.atr, start, end, x, yv, AppColors.Orange)
            label = "ATR(14) ${Fmt.num(ind.atr[end - 1], kr)} · 하루 평균 변동폭"
        }
    }
    drawContext.canvas.nativeCanvas.drawText(label, 6f, top + textPx * 1.2f, paint)
}
