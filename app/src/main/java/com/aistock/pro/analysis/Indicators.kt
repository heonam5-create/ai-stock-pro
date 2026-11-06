package com.aistock.pro.analysis

import com.aistock.pro.data.Bar
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 기술적 지표 계산 (값이 부족한 앞부분은 NaN) */
object Indicators {

    fun sma(v: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        if (n <= 0 || v.size < n) return out
        var sum = 0.0
        for (i in v.indices) {
            sum += v[i]
            if (i >= n) sum -= v[i - n]
            if (i >= n - 1) out[i] = sum / n
        }
        return out
    }

    fun ema(v: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        val start = v.indexOfFirst { !it.isNaN() }
        if (start < 0 || v.size - start < n) return out
        val k = 2.0 / (n + 1)
        var prev = (start until start + n).sumOf { v[it] } / n
        out[start + n - 1] = prev
        for (i in start + n until v.size) {
            prev = v[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** Wilder RSI */
    fun rsi(c: DoubleArray, n: Int = 14): DoubleArray {
        val out = DoubleArray(c.size) { Double.NaN }
        if (c.size <= n) return out
        var gain = 0.0; var loss = 0.0
        for (i in 1..n) { val d = c[i] - c[i - 1]; if (d > 0) gain += d else loss -= d }
        gain /= n; loss /= n
        out[n] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        for (i in n + 1 until c.size) {
            val d = c[i] - c[i - 1]
            gain = (gain * (n - 1) + max(d, 0.0)) / n
            loss = (loss * (n - 1) + max(-d, 0.0)) / n
            out[i] = if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
        }
        return out
    }

    data class Macd(val macd: DoubleArray, val signal: DoubleArray, val hist: DoubleArray)

    fun macd(c: DoubleArray, fast: Int = 12, slow: Int = 26, sig: Int = 9): Macd {
        val f = ema(c, fast); val s = ema(c, slow)
        val m = DoubleArray(c.size) { if (f[it].isNaN() || s[it].isNaN()) Double.NaN else f[it] - s[it] }
        val signal = ema(m, sig)
        val hist = DoubleArray(c.size) { if (m[it].isNaN() || signal[it].isNaN()) Double.NaN else m[it] - signal[it] }
        return Macd(m, signal, hist)
    }

    data class Bands(val mid: DoubleArray, val upper: DoubleArray, val lower: DoubleArray)

    fun bollinger(c: DoubleArray, n: Int = 20, k: Double = 2.0): Bands {
        val mid = sma(c, n)
        val up = DoubleArray(c.size) { Double.NaN }
        val lo = DoubleArray(c.size) { Double.NaN }
        for (i in n - 1 until c.size) {
            if (mid[i].isNaN()) continue
            var ss = 0.0
            for (j in i - n + 1..i) ss += (c[j] - mid[i]) * (c[j] - mid[i])
            val sd = sqrt(ss / n)
            up[i] = mid[i] + k * sd; lo[i] = mid[i] - k * sd
        }
        return Bands(mid, up, lo)
    }

    data class Stoch(val k: DoubleArray, val d: DoubleArray)

    /** 슬로우 스토캐스틱 (14,3,3) */
    fun stochastic(h: DoubleArray, l: DoubleArray, c: DoubleArray, n: Int = 14, smoothK: Int = 3, smoothD: Int = 3): Stoch {
        val raw = DoubleArray(c.size) { Double.NaN }
        for (i in n - 1 until c.size) {
            var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
            for (j in i - n + 1..i) { hh = max(hh, h[j]); ll = min(ll, l[j]) }
            raw[i] = if (hh - ll == 0.0) 50.0 else (c[i] - ll) / (hh - ll) * 100
        }
        val k = smaNan(raw, smoothK)
        val d = smaNan(k, smoothD)
        return Stoch(k, d)
    }

    /** 앞부분 NaN 을 건너뛰는 이동평균 */
    fun smaNan(v: DoubleArray, n: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        for (i in v.indices) {
            if (i < n - 1) continue
            var s = 0.0; var ok = true
            for (j in i - n + 1..i) { if (v[j].isNaN()) { ok = false; break }; s += v[j] }
            if (ok) out[i] = s / n
        }
        return out
    }

    fun atr(h: DoubleArray, l: DoubleArray, c: DoubleArray, n: Int = 14): DoubleArray {
        val out = DoubleArray(c.size) { Double.NaN }
        if (c.size <= n) return out
        val tr = DoubleArray(c.size) { i ->
            if (i == 0) h[0] - l[0] else max(h[i] - l[i], max(abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1])))
        }
        var a = (1..n).sumOf { tr[it] } / n
        out[n] = a
        for (i in n + 1 until c.size) { a = (a * (n - 1) + tr[i]) / n; out[i] = a }
        return out
    }

    /** 분봉: 하루 단위로 초기화되는 VWAP / 일봉 이상: 최근 n개 이동 VWAP */
    fun vwap(bars: List<Bar>, intraday: Boolean, zone: ZoneId = ZoneId.of("UTC"), rollingN: Int = 20): DoubleArray {
        val out = DoubleArray(bars.size) { Double.NaN }
        if (intraday) {
            var pv = 0.0; var vol = 0.0; var day = -1L
            for (i in bars.indices) {
                val b = bars[i]
                val d = Instant.ofEpochSecond(b.t).atZone(zone).toLocalDate().toEpochDay()
                if (d != day) { pv = 0.0; vol = 0.0; day = d }
                val tp = (b.h + b.l + b.c) / 3
                pv += tp * b.v; vol += b.v
                out[i] = if (vol > 0) pv / vol else tp
            }
        } else {
            for (i in rollingN - 1 until bars.size) {
                var pv = 0.0; var vol = 0.0
                for (j in i - rollingN + 1..i) { val b = bars[j]; pv += (b.h + b.l + b.c) / 3 * b.v; vol += b.v }
                out[i] = if (vol > 0) pv / vol else bars[i].c
            }
        }
        return out
    }

    /** 고점/저점(프랙탈) 인덱스 */
    fun pivots(h: DoubleArray, l: DoubleArray, left: Int = 3, right: Int = 3): Pair<List<Int>, List<Int>> {
        val highs = ArrayList<Int>(); val lows = ArrayList<Int>()
        for (i in left until h.size - right) {
            var isH = true; var isL = true
            for (j in i - left..i + right) {
                if (j == i) continue
                if (h[j] > h[i]) isH = false
                if (l[j] < l[i]) isL = false
            }
            if (isH) highs.add(i)
            if (isL) lows.add(i)
        }
        return highs to lows
    }

    data class Level(val price: Double, val touches: Int, val kind: String)

    /** 고점/저점을 ATR 폭으로 묶어 지지·저항 가격대 산출 */
    fun srLevels(bars: List<Bar>, atr: Double, lookback: Int = 160): List<Level> {
        if (bars.size < 20 || atr.isNaN() || atr <= 0) return emptyList()
        val sub = bars.takeLast(lookback)
        val h = DoubleArray(sub.size) { sub[it].h }; val l = DoubleArray(sub.size) { sub[it].l }
        val (ph, pl) = pivots(h, l, 3, 3)
        val pts = ph.map { h[it] } + pl.map { l[it] }
        if (pts.isEmpty()) return emptyList()
        val sorted = pts.sorted()
        val clusters = ArrayList<MutableList<Double>>()
        for (p in sorted) {
            val last = clusters.lastOrNull()
            if (last != null && p - last.average() <= atr * 0.6) last.add(p) else clusters.add(mutableListOf(p))
        }
        return clusters.map { Level(it.average(), it.size, "pivot") }
    }

    data class VolNode(val low: Double, val high: Double, val volume: Double) { val mid get() = (low + high) / 2 }

    /** 매물대 (가격대별 거래량) */
    fun volumeProfile(bars: List<Bar>, bins: Int = 24, lookback: Int = 120): List<VolNode> {
        val sub = bars.takeLast(lookback)
        if (sub.size < 10) return emptyList()
        val lo = sub.minOf { it.l }; val hi = sub.maxOf { it.h }
        if (hi <= lo) return emptyList()
        val step = (hi - lo) / bins
        val vol = DoubleArray(bins)
        for (b in sub) {
            val tp = (b.h + b.l + b.c) / 3
            val idx = ((tp - lo) / step).toInt().coerceIn(0, bins - 1)
            vol[idx] += b.v
        }
        return (0 until bins).map { VolNode(lo + it * step, lo + (it + 1) * step, vol[it]) }
    }

    /** 단순 선형회귀 기울기 (값/봉) */
    fun slope(v: List<Double>): Double {
        val n = v.size
        if (n < 2) return 0.0
        val xm = (n - 1) / 2.0; val ym = v.average()
        var num = 0.0; var den = 0.0
        for (i in 0 until n) { num += (i - xm) * (v[i] - ym); den += (i - xm) * (i - xm) }
        return if (den == 0.0) 0.0 else num / den
    }

    data class TrendLine(val i1: Int, val p1: Double, val i2: Int, val p2: Double, val up: Boolean) {
        fun valueAt(i: Int): Double = p1 + (p2 - p1) * (i - i1).toDouble() / (i2 - i1).toDouble()
    }

    /** 최근 저점 2개(저점 상승)로 상승추세선, 고점 2개(고점 하락)로 하락추세선 */
    fun trendLines(bars: List<Bar>, lookback: Int = 120): List<TrendLine> {
        if (bars.size < 20) return emptyList()
        val offset = max(0, bars.size - lookback)
        val sub = bars.subList(offset, bars.size)
        val h = DoubleArray(sub.size) { sub[it].h }; val l = DoubleArray(sub.size) { sub[it].l }
        val (ph, pl) = pivots(h, l, 4, 4)
        val out = ArrayList<TrendLine>()
        if (pl.size >= 2) {
            val a = pl[pl.size - 2]; val b = pl[pl.size - 1]
            if (l[b] > l[a]) out.add(TrendLine(a + offset, l[a], b + offset, l[b], true))
        }
        if (ph.size >= 2) {
            val a = ph[ph.size - 2]; val b = ph[ph.size - 1]
            if (h[b] < h[a]) out.add(TrendLine(a + offset, h[a], b + offset, h[b], false))
        }
        return out
    }

    /** 한국 주식 호가 단위 (2023.1 개편 기준) */
    fun krTick(price: Double): Double = when {
        price < 2_000 -> 1.0
        price < 5_000 -> 5.0
        price < 20_000 -> 10.0
        price < 50_000 -> 50.0
        price < 200_000 -> 100.0
        price < 500_000 -> 500.0
        else -> 1_000.0
    }

    fun roundPrice(p: Double, kr: Boolean): Double {
        if (p.isNaN() || p <= 0) return p
        return if (kr) { val t = krTick(p); Math.round(p / t) * t } else Math.round(p * 100) / 100.0
    }
}

/** 차트·분석에서 공통으로 쓰는 지표 묶음 */
class IndicatorSet(val bars: List<Bar>, intraday: Boolean, zone: ZoneId) {
    val c = DoubleArray(bars.size) { bars[it].c }
    val h = DoubleArray(bars.size) { bars[it].h }
    val l = DoubleArray(bars.size) { bars[it].l }
    val v = DoubleArray(bars.size) { bars[it].v }
    val ma = mapOf(5 to Indicators.sma(c, 5), 10 to Indicators.sma(c, 10), 20 to Indicators.sma(c, 20),
        60 to Indicators.sma(c, 60), 120 to Indicators.sma(c, 120), 200 to Indicators.sma(c, 200))
    val volMa20 = Indicators.sma(v, 20)
    val rsi = Indicators.rsi(c, 14)
    val macd = Indicators.macd(c)
    val bb = Indicators.bollinger(c)
    val stoch = Indicators.stochastic(h, l, c)
    val atr = Indicators.atr(h, l, c, 14)
    val vwap = Indicators.vwap(bars, intraday, zone)
    val trendLines = Indicators.trendLines(bars)
}
