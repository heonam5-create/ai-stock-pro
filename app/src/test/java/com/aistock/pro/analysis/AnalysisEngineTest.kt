package com.aistock.pro.analysis

import com.aistock.pro.data.Bar
import com.aistock.pro.data.EventItem
import com.aistock.pro.data.NewsItem
import com.aistock.pro.data.Sentiment
import com.aistock.pro.data.StockDirectory
import com.aistock.pro.data.StockRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class AnalysisEngineTest {

    companion object {
        private const val DAY = 86400L
        private val NOW = 1_793_000_000L   // 2026-10 경

        /** drift: 하루 평균 수익률, vol: 일 변동성 */
        fun series(n: Int, start: Double, drift: Double, vol: Double, seed: Int = 7, volBase: Double = 1_000_000.0): List<Bar> {
            val rnd = Random(seed)
            var p = start
            return (0 until n).map { i ->
                val o = p
                val r = drift + vol * (rnd.nextDouble() * 2 - 1) + 0.002 * sin(i / 6.0)
                val c = o * (1 + r)
                val h = maxOf(o, c) * (1 + vol * 0.5 * rnd.nextDouble())
                val l = minOf(o, c) * (1 - vol * 0.5 * rnd.nextDouble())
                p = c
                Bar(NOW - (n - i) * DAY, o, h, l, c, volBase * (0.6 + rnd.nextDouble()))
            }
        }

        fun run(bars: List<Bar>, kr: Boolean = true, news: List<NewsItem>? = emptyList(), events: List<EventItem>? = emptyList()) =
            AnalysisEngine.analyze(AnalysisInput(bars, bars.last().c, kr, news, events, NOW))
    }

    // ───────── 지표 ─────────
    @Test fun sma_basic() {
        val r = Indicators.sma(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)
        assertTrue(r[0].isNaN() && r[1].isNaN())
        assertEquals(2.0, r[2], 1e-9); assertEquals(4.0, r[4], 1e-9)
    }

    @Test fun rsi_monotonic_up_is_100_and_down_is_0() {
        val up = DoubleArray(40) { 100.0 + it }
        val dn = DoubleArray(40) { 100.0 - it }
        assertEquals(100.0, Indicators.rsi(up).last(), 1e-9)
        assertEquals(0.0, Indicators.rsi(dn).last(), 1e-9)
    }

    @Test fun rsi_known_value_wilder() {
        // Wilder 원전 예제 근사: 상승/하락 혼합 → 0~100 사이, NaN 아님
        val c = doubleArrayOf(44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28, 46.00)
        val r = Indicators.rsi(c, 14)
        assertEquals(70.53, r[14], 0.5)   // 공개 예제 값 70.53
    }

    @Test fun bollinger_and_atr_valid() {
        val b = series(200, 100.0, 0.001, 0.02)
        val set = IndicatorSet(b, false, java.time.ZoneId.of("UTC"))
        val i = b.size - 1
        assertTrue(set.bb.upper[i] > set.bb.mid[i] && set.bb.mid[i] > set.bb.lower[i])
        assertTrue(set.atr[i] > 0)
        assertTrue(set.stoch.k[i] in 0.0..100.0)
        assertTrue(!set.macd.hist[i].isNaN())
    }

    @Test fun kr_tick_rounding() {
        assertEquals(71_200.0, Indicators.roundPrice(71_234.0, true), 1e-9)   // 100원 단위
        assertEquals(10_010.0, Indicators.roundPrice(10_012.0, true), 1e-9)   // 10원 단위
        assertEquals(181.23, Indicators.roundPrice(181.2341, false), 1e-9)
    }

    // ───────── 분석 엔진 ─────────
    @Test fun not_enough_data_returns_NA() {
        val r = run(series(80, 100.0, 0.001, 0.02))
        assertEquals(Verdict.NA, r.verdict)
        assertTrue(r.strategy == null)
    }

    @Test fun strong_uptrend_is_buy_side_and_downtrend_is_avoid() {
        val up = run(series(300, 50_000.0, 0.004, 0.012, seed = 3))
        val down = run(series(300, 50_000.0, -0.004, 0.012, seed = 3))
        assertTrue("상승추세 판정=${up.verdict} 점수=${up.score}", up.verdict == Verdict.STRONG_BUY || up.verdict == Verdict.SPLIT_BUY)
        assertTrue("하락추세 판정=${down.verdict} 점수=${down.score}", down.verdict == Verdict.AVOID || down.verdict == Verdict.SPLIT_SELL)
        assertTrue(up.score > down.score)
    }

    @Test fun ten_factors_each_with_reason() {
        val r = run(series(300, 180.0, 0.001, 0.02, seed = 11), kr = false)
        assertEquals(10, r.factors.size)
        r.factors.forEach { assertTrue("${it.title} 근거 없음", it.detail.length > 10) }
        assertEquals((1..10).toList(), r.factors.map { it.no })
    }

    @Test fun strategy_prices_are_ordered_and_reachable() {
        for (seed in 1..40) {
            for ((drift, vol) in listOf(0.003 to 0.015, 0.0 to 0.02, -0.002 to 0.025, 0.001 to 0.04)) {
                val bars = series(260, 30_000.0 + seed * 1000, drift, vol, seed)
                val r = run(bars)
                val s = r.strategy ?: continue
                val (b1, b2, b3) = s.buys.map { it.price }
                val (t1, t2, t3) = s.targets.map { it.price }
                val price = r.price
                assertTrue("seed=$seed 매수가 순서 $b1 $b2 $b3", b1 > b2 && b2 > b3)
                assertTrue("seed=$seed 손절<3차매수", s.stop.price < b3)
                assertTrue("seed=$seed 목표가 순서", t1 < t2 && t2 < t3 && t1 > price)
                assertTrue("seed=$seed 1차 매수가는 현재가 이하", b1 <= price)
                // 1차 매수가가 지나치게 낮지 않음: 현재가 - ATR 1.2배 (호가 반올림 여유 포함) 이상
                assertTrue("seed=$seed 1차 매수가 과도하게 낮음", b1 >= price - 1.2 * r.atr - Indicators.krTick(price))
                // 한국 호가 단위 정합성
                s.buys.forEach { assertEquals(0.0, it.price % Indicators.krTick(it.price), 1e-6) }
            }
        }
    }

    @Test fun news_and_events_influence() {
        val bars = series(260, 100.0, 0.001, 0.015, seed = 5)
        fun n(t: String, s: Sentiment) = NewsItem(t, "src", "https://x", NOW - 3600, "X", "X", s)
        val good = run(bars, false, List(5) { n("record revenue beats", Sentiment.POSITIVE) })
        val bad = run(bars, false, List(5) { n("lawsuit plunge", Sentiment.NEGATIVE) })
        assertTrue(good.score > bad.score)
        val ev = run(bars, false, emptyList(), listOf(EventItem("2026-11-08", "X 실적 발표", "", 2)))
        assertEquals("일정 리스크 있음", ev.factors[9].status)
        val noNews = run(bars, false, null)
        assertTrue(!noNews.factors[8].available)
    }

    @Test fun split_like_jump_is_warned() {
        val b = series(260, 100.0, 0.001, 0.01).toMutableList()
        for (k in 200 until b.size) { val x = b[k]; b[k] = Bar(x.t, x.o / 4, x.h / 4, x.l / 4, x.c / 4, x.v * 4) }
        val r = run(b, false)
        assertTrue(r.warnings.any { it.contains("분할") })
    }

    @Test fun recommender_ranks_and_excludes_avoid() {
        val recs = (1..12).mapNotNull { s ->
            val drift = if (s % 3 == 0) -0.004 else 0.002 + s * 0.0002
            val r = run(series(260, 100.0, drift, 0.015, seed = s), false)
            Recommender.score(StockRef("T$s", "T$s"), r, NOW)
        }
        recs.forEach { assertTrue(it.result.verdict != Verdict.AVOID) }
        val top = Recommender.rank(recs)
        assertTrue(top.size <= 5)
        for (k in 1 until top.size) assertTrue(top[k - 1].total >= top[k].total)
        top.forEach { assertEquals(9, it.parts.size) }
    }

    // ───────── 검색 사전 ─────────
    @Test fun korean_and_english_search_examples_from_spec() {
        val expect = mapOf(
            "삼성전자" to "005930.KS", "SK하이닉스" to "000660.KS", "NAVER" to "035420.KS", "카카오" to "035720.KS",
            "엔비디아" to "NVDA", "샌디스크" to "SNDK", "마이크론" to "MU", "테슬라" to "TSLA", "애플" to "AAPL",
            "NVIDIA" to "NVDA", "SNDK" to "SNDK", "NVDA" to "NVDA", "MU" to "MU", "TSLA" to "TSLA",
            "005930" to "005930.KS", "하이닉스" to "000660.KS", "네이버" to "035420.KS",
        )
        expect.forEach { (q, sym) ->
            val first = StockDirectory.search(q).firstOrNull()
            assertNotNull("'$q' 검색 결과 없음", first)
            assertEquals("'$q' 첫 결과", sym, first!!.symbol)
        }
    }

    @Test fun directory_has_no_duplicate_symbols() {
        val dup = StockDirectory.entries.groupBy { it.symbol }.filter { it.value.size > 1 }.keys
        assertTrue("중복: $dup", dup.isEmpty())
        (StockDirectory.krUniverse + StockDirectory.usUniverse).forEach { assertNotNull("후보 $it 사전에 없음", StockDirectory.bySymbol(it)) }
    }

    @Test fun news_classifier() {
        fun c(t: String) = NewsClassifier.classify(NewsItem(t, "", "", NOW, null, null)).sentiment
        assertEquals(Sentiment.POSITIVE, c("삼성전자, 3분기 영업이익 사상 최대…HBM 수주 확대"))
        assertEquals(Sentiment.NEGATIVE, c("○○전자 유상증자 결정에 주가 급락"))
        assertEquals(Sentiment.POSITIVE, c("Micron beats estimates, shares surge"))
        assertEquals(Sentiment.NEUTRAL, c("오늘의 증시 일정"))
        val cats = NewsClassifier.classify(NewsItem("FOMC 앞두고 CPI 발표", "", "", NOW, null, null)).categories
        assertTrue(cats.contains("FOMC") && cats.contains("CPI"))
    }

    @Test fun fmt_outputs() {
        assertEquals("71,200원", Fmt.price(71_200.0, true))
        assertEquals("$181.23", Fmt.price(181.234, false))
        assertEquals("+1.50%", Fmt.pct(1.5))
        assertEquals("1.5조원", Fmt.marketCap(1.5e12, true))
        assertTrue(abs(0.0) == 0.0)
    }
}
