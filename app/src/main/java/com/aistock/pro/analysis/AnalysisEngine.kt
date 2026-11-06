package com.aistock.pro.analysis

import com.aistock.pro.data.Bar
import com.aistock.pro.data.EventItem
import com.aistock.pro.data.NewsItem
import com.aistock.pro.data.Sentiment
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Verdict(val label: String) {
    STRONG_BUY("적극 매수"), SPLIT_BUY("분할매수"), HOLD("관망"), SPLIT_SELL("분할매도"), AVOID("매수 금지 / 위험"), NA("분석 불가")
}

/** 10개 분석 항목 각각의 결과. score: -2(매우 부정) ~ +2(매우 긍정) */
data class Factor(
    val no: Int, val title: String, val status: String, val detail: String,
    val score: Int, val weight: Int, val available: Boolean = true,
)

data class PriceLevel(
    val label: String, val price: Double, val diffPct: Double,
    val basis: String, val action: String, val extra: String = "",
)

data class Strategy(
    val buys: List<PriceLevel>,
    val noBuyFrom: Double?,
    val noBuyText: String,
    val targets: List<PriceLevel>,
    val stop: PriceLevel,
    val trendBreak: PriceLevel,
    val riskReward: Double?,
)

data class SRPoint(val price: Double, val basis: String)

data class AnalysisResult(
    val verdict: Verdict,
    val score: Int,                 // 0~100
    val summary: String,
    val reasons: List<String>,
    val risks: List<String>,
    val factors: List<Factor>,
    val strategy: Strategy?,
    val supports: List<SRPoint>,
    val resistances: List<SRPoint>,
    val price: Double,
    val atr: Double,
    val atrPct: Double,
    val rsi: Double,
    val ret120: Double,
    val warnings: List<String>,
)

data class AnalysisInput(
    val bars: List<Bar>,             // 일봉 (분할 반영)
    val price: Double,               // 현재가(최신 체결가)
    val isKr: Boolean,
    val news: List<NewsItem>?,       // null = 조회하지 않음
    val events: List<EventItem>?,    // null = 조회하지 않음
    val nowSec: Long = System.currentTimeMillis() / 1000,
)

/**
 * 로컬 AI 분석 엔진 (무료·오프라인 규칙 기반 다요인 점수 모델).
 * 모든 결론은 실제 시세 데이터에서 계산된 근거와 함께 반환되며, 데이터가 부족하면 '분석 불가'를 반환합니다.
 */
object AnalysisEngine {
    const val MIN_BARS = 120

    private fun na(msg: String, price: Double) = AnalysisResult(
        Verdict.NA, 0, msg, emptyList(), emptyList(), emptyList(), null, emptyList(), emptyList(),
        price, Double.NaN, Double.NaN, Double.NaN, Double.NaN, listOf(msg),
    )

    fun analyze(inp: AnalysisInput): AnalysisResult {
        val bars = inp.bars
        val price = inp.price
        if (price.isNaN() || price <= 0) return na("현재가 데이터가 없어 분석할 수 없습니다.", price)
        if (bars.size < MIN_BARS) return na(
            "일봉 데이터가 ${bars.size}개뿐이라 분석할 수 없습니다 (최소 ${MIN_BARS}거래일 필요 · 신규 상장 종목 등).", price)

        val kr = inp.isKr
        val ind = IndicatorSet(bars, false, ZoneId.of("UTC"))
        val i = bars.size - 1
        val c = ind.c
        val atr = ind.atr[i]
        if (atr.isNaN() || atr <= 0) return na("변동성(ATR) 계산에 필요한 데이터가 부족합니다.", price)
        val warnings = ArrayList<String>()
        val p = { x: Double -> Fmt.price(x, kr) }

        // 오래된 데이터 경고 (마지막 일봉이 10일 이상 전)
        if (inp.nowSec - bars.last().t > 10L * 86400) warnings.add("마지막 일봉이 10일 이상 지난 데이터입니다. 거래정지·데이터 지연 여부를 확인하세요.")
        // 분할 등 비정상 가격 점프 탐지
        for (j in max(1, bars.size - 250) until bars.size) {
            val r = bars[j].c / bars[j - 1].c
            if (r > 2.2 || r < 0.45) {
                val day = com.aistock.pro.data.MarketClock.formatKst(bars[j].t).take(10)
                warnings.add("$day 에 하루 ±50% 이상 가격 급변이 있습니다 (분할·병합 미반영 가능성). 지표 해석에 주의하세요.")
                break
            }
        }

        val ma5 = ind.ma.getValue(5)[i]; val ma20 = ind.ma.getValue(20)[i]; val ma60 = ind.ma.getValue(60)[i]
        val ma120 = ind.ma.getValue(120)[i]; val ma200 = ind.ma.getValue(200)[i]
        val ma20Prev = ind.ma.getValue(20)[i - 5]; val ma60Prev = ind.ma.getValue(60)[i - 20]
        val ma120Prev = ind.ma.getValue(120).getOrElse(i - 20) { Double.NaN }
        val rsi = ind.rsi[i]
        val atrPct = atr / price * 100
        val ret20 = (price / c[i - 20] - 1) * 100
        val ret60 = (price / c[i - 60] - 1) * 100
        val ret120 = (price / c[i - 119] - 1) * 100

        // ─── 지지/저항 후보 수집 ───
        val levels = Indicators.srLevels(bars, atr)
        val vp = Indicators.volumeProfile(bars)
        val vpAvg = if (vp.isEmpty()) 0.0 else vp.sumOf { it.volume } / vp.size
        val hi20 = bars.takeLast(20).maxOf { it.h }; val lo20 = bars.takeLast(20).minOf { it.l }
        val hi52 = bars.takeLast(252).maxOf { it.h }
        val vwap20 = ind.vwap[i]
        val bbU = ind.bb.upper[i]; val bbL = ind.bb.lower[i]

        val supCand = ArrayList<SRPoint>(); val resCand = ArrayList<SRPoint>()
        fun add(px: Double, basis: String) {
            if (px.isNaN() || px <= 0) return
            if (px < price * 0.998 && px > price - 6 * atr) supCand.add(SRPoint(px, basis))
            else if (px > price * 1.002 && px < price + 8 * atr) resCand.add(SRPoint(px, basis))
        }
        levels.forEach { add(it.price, if (it.touches >= 2) "과거 고·저점 밀집(${it.touches}회)" else "직전 고·저점") }
        add(ma20, "20일 이동평균선"); add(ma60, "60일 이동평균선"); add(ma120, "120일 이동평균선")
        if (!ma200.isNaN()) add(ma200, "200일 이동평균선")
        add(bbL, "볼린저밴드 하단"); add(bbU, "볼린저밴드 상단"); add(vwap20, "20일 VWAP(평균 거래단가)")
        vp.filter { it.volume > vpAvg * 1.3 }.forEach { add(it.mid, "매물대(거래 집중 가격대)") }
        add(lo20, "최근 20일 최저가"); add(hi20, "최근 20일 최고가"); add(hi52, "52주(1년) 최고가")

        val supports = merge(supCand.sortedByDescending { it.price }, atr * 0.25)
        val resistances = merge(resCand.sortedBy { it.price }, atr * 0.25)
        val nearSup = supports.firstOrNull()
        val nearRes = resistances.firstOrNull()

        val factors = ArrayList<Factor>()

        // 1. 현재 추세
        run {
            val slope20 = (ma20 / ma20Prev - 1) * 100
            val aligned = ma5 > ma20 && ma20 > ma60 && ma60 > ma120
            val reverse = ma5 < ma20 && ma20 < ma60 && ma60 < ma120
            val s = when {
                aligned && price > ma20 && slope20 > 0 -> 2
                price > ma20 && price > ma60 && slope20 > 0 -> 1
                reverse && price < ma20 && slope20 < 0 -> -2
                price < ma20 && price < ma60 -> -1
                else -> 0
            }
            val st = arrayOf("강한 하락 추세", "하락 추세", "횡보·중립", "상승 추세", "강한 상승 추세")[s + 2]
            val arr = when { aligned -> "이동평균 정배열"; reverse -> "이동평균 역배열"; else -> "이동평균 혼조" }
            val d = "현재가 ${p(price)} 기준 20일선(${p(ma20)}) ${if (price > ma20) "위" else "아래"}, 60일선(${p(ma60)}) ${if (price > ma60) "위" else "아래"}, " +
                "120일선 ${if (price > ma120) "위" else "아래"}${if (!ma200.isNaN()) ", 200일선 " + (if (price > ma200) "위" else "아래") else ""}. " +
                "$arr, 20일선 5일 기울기 ${Fmt.pct(slope20)}."
            factors.add(Factor(1, "현재 추세", st, d, s, 8))
        }
        // 2. 상승·하락 모멘텀
        run {
            val h = ind.macd.hist; val hNow = h[i]; val hPrev = h[i - 3]
            var pts = 0
            if (ret20 > 5) pts++ else if (ret20 < -5) pts--
            if (hNow > 0 && hNow > hPrev) pts++ else if (hNow < 0 && hNow < hPrev) pts--
            if (ret60 > 15) pts++ else if (ret60 < -15) pts--
            val s = pts.coerceIn(-2, 2)
            val st = when { s >= 2 -> "상승 모멘텀 강함"; s == 1 -> "상승 모멘텀"; s == 0 -> "모멘텀 약함"; s == -1 -> "하락 모멘텀"; else -> "하락 모멘텀 강함" }
            val d = "20일 수익률 ${Fmt.pct(ret20)}, 60일 ${Fmt.pct(ret60)}. MACD 히스토그램 ${if (hNow >= 0) "양(+)" else "음(-)"}, " +
                "3일 전 대비 ${if (hNow > hPrev) "확대" else "축소"} (MACD ${"%.2f".format(ind.macd.macd[i])} / 시그널 ${"%.2f".format(ind.macd.signal[i])})."
            factors.add(Factor(2, "상승·하락 모멘텀", st, d, s, 6))
        }
        // 3. 단기 매수 적합성
        val extension = (price - ma20) / atr
        run {
            val k = ind.stoch.k; val dd = ind.stoch.d
            var pts = 0
            val notes = ArrayList<String>()
            if (rsi in 40.0..65.0) { pts++; notes.add("RSI ${rsi.roundToInt()} 적정 구간") }
            else if (rsi > 72) { pts--; notes.add("RSI ${rsi.roundToInt()} 과열") }
            else notes.add("RSI ${rsi.roundToInt()}")
            val distSup = if (nearSup != null) (price - nearSup.price) / atr else Double.NaN
            if (!distSup.isNaN() && distSup <= 1.2) { pts++; notes.add("지지선(${p(nearSup!!.price)})과 ATR ${"%.1f".format(distSup)}배 거리로 근접") }
            if (extension > 2.5) { pts--; notes.add("20일선 대비 ATR ${"%.1f".format(extension)}배 이격 → 추격매수 위험") }
            if (k[i] > dd[i] && k[i - 1] <= dd[i - 1] && k[i] < 40) { pts++; notes.add("스토캐스틱 침체권 골든크로스") }
            if (k[i] < dd[i] && k[i - 1] >= dd[i - 1] && k[i] > 70) { pts--; notes.add("스토캐스틱 과열권 데드크로스") }
            val s = pts.coerceIn(-2, 2)
            val st = when { s >= 2 -> "단기 매수 적합"; s == 1 -> "조건부 적합(눌림 대기)"; s == 0 -> "중립"; s == -1 -> "단기 매수 부적합"; else -> "단기 매수 매우 부적합" }
            factors.add(Factor(3, "단기 매수 적합성", st, notes.joinToString(", ") + ".", s, 5))
        }
        // 4. 중기 보유 적합성
        run {
            var pts = 0
            val slope60 = (ma60 / ma60Prev - 1) * 100
            pts += if (price > ma60) 1 else -1
            pts += if (slope60 > 0) 1 else -1
            if (!ma120Prev.isNaN() && ma120 > ma120Prev && price > ma120) pts++
            if (!ma200.isNaN() && price < ma200) pts--
            val s = pts.coerceIn(-2, 2)
            val st = when { s >= 2 -> "중기 보유 적합"; s == 1 -> "중기 보유 무난"; s == 0 -> "중립"; s == -1 -> "중기 보유 주의"; else -> "중기 보유 부적합" }
            val d = "60일선 20일 기울기 ${Fmt.pct(slope60)}, 120일 수익률 ${Fmt.pct(ret120)}" +
                (if (!ma200.isNaN()) ", 200일선 ${if (price > ma200) "위(장기 상승 구조)" else "아래(장기 약세 구조)"}" else ", 200일선 계산 불가(데이터 부족)") + "."
            factors.add(Factor(4, "중기 보유 적합성", st, d, s, 6))
        }
        // 5. 거래량 변화
        run {
            val v = ind.v
            val avg5 = (i - 5 until i).map { v[it] }.average()        // 완성된 직전 5일 (당일 장중 거래량 제외)
            val avg20 = (i - 25 until i - 5).map { v[it] }.average()
            val ratio = if (avg20 > 0) avg5 / avg20 else Double.NaN
            var upV = 0.0; var dnV = 0.0
            for (j in i - 20 until i) if (c[j] >= c[j - 1]) upV += v[j] else dnV += v[j]
            val ud = if (dnV > 0) upV / dnV else 2.0
            var pts = 0
            val up5 = c[i - 1] > c[i - 6]
            if (!ratio.isNaN() && ratio > 1.4) pts += if (up5) 1 else -1
            if (ud > 1.3) pts++ else if (ud < 0.75) pts--
            val s = pts.coerceIn(-2, 2)
            val st = when { s >= 1 -> "매수세 우위 거래량"; s == 0 -> "거래량 평이"; else -> "매도세 우위 거래량" }
            val d = if (avg20 <= 0) "거래량 데이터가 부족합니다." else
                "최근 5일 평균 거래량이 이전 20일 대비 ${"%.2f".format(ratio)}배, 20일간 상승일/하락일 거래량 비율 ${"%.2f".format(ud)}."
            factors.add(Factor(5, "거래량 변화", st, d, s, 4, avg20 > 0))
        }
        // 6. 매물대 및 지지·저항
        run {
            var pts = 0
            val poc = vp.maxByOrNull { it.volume }
            val total = vp.sumOf { it.volume }
            val over = vp.filter { it.low >= price && it.low <= price * 1.10 }.sumOf { it.volume }
            val overRatio = if (total > 0) over / total else 0.0
            if (poc != null) pts += if (price > poc.mid) 1 else -1
            if (overRatio > 0.25) pts--
            if (nearRes != null && (nearRes.price - price) / atr < 0.5) pts--
            if (nearSup != null && (price - nearSup.price) / atr < 1.0) pts++
            val s = pts.coerceIn(-2, 2)
            val st = when { s >= 1 -> "매물 부담 적음"; s == 0 -> "매물 중립"; else -> "상단 매물 부담" }
            val d = (if (poc != null) "최대 매물대 ${p(poc.mid)} ${if (price > poc.mid) "위(지지 역할)" else "아래(저항 역할)"}. " else "") +
                "현재가 위 +10% 구간 매물 비중 ${"%.0f".format(overRatio * 100)}%. " +
                "가까운 지지 ${nearSup?.let { p(it.price) } ?: "없음"}, 가까운 저항 ${nearRes?.let { p(it.price) } ?: "없음"}."
            factors.add(Factor(6, "매물대 및 지지·저항", st, d, s, 4, poc != null))
        }
        // 7. 과매수·과매도
        run {
            val pb = if (bbU > bbL) (price - bbL) / (bbU - bbL) else 0.5
            val k = ind.stoch.k[i]
            val trendScore = factors[0].score
            val (s, st) = when {
                rsi > 78 || pb > 1.1 -> -2 to "강한 과매수"
                rsi > 70 || pb > 1.0 -> -1 to "과매수"
                rsi < 30 || pb < 0 -> (if (trendScore >= 0) 1 else 0) to (if (trendScore >= 0) "과매도(반등 가능)" else "과매도(하락추세 중 · 반등 신호 아님)")
                else -> 0 to "중립"
            }
            factors.add(Factor(7, "과매수·과매도", st,
                "RSI ${"%.1f".format(rsi)}, 스토캐스틱 %K ${"%.1f".format(k)}, 볼린저 %B ${"%.2f".format(pb)} (0 미만 과매도 · 1 초과 과매수).", s, 4))
        }
        // 8. 변동성 및 갭 위험
        run {
            var gaps = 0
            for (j in i - 59..i) if (abs(bars[j].o / c[j - 1] - 1) > 0.03) gaps++
            var pts = when { atrPct > 6 -> -2; atrPct > 4 -> -1; atrPct < 2 -> 1; else -> 0 }
            if (gaps >= 4) pts--
            val s = pts.coerceIn(-2, 2)
            val st = when { atrPct > 6 -> "변동성 매우 높음"; atrPct > 4 -> "변동성 높음"; atrPct < 2 -> "변동성 낮음"; else -> "변동성 보통" }
            factors.add(Factor(8, "변동성 및 갭 위험", st,
                "하루 평균 변동폭(ATR) ${p(atr)} = 주가의 ${"%.2f".format(atrPct)}%. 최근 60일 중 3% 이상 갭 발생 ${gaps}회.", s, 3))
        }
        // 9. 최신 뉴스 영향
        run {
            val news = inp.news
            if (news == null) {
                factors.add(Factor(9, "최신 뉴스 영향", "뉴스 미조회", "뉴스를 불러오지 못해 이 항목은 점수에서 제외했습니다.", 0, 3, false))
            } else {
                val recent = news.filter { inp.nowSec - it.publishedAt <= 7 * 86400 }
                if (recent.isEmpty()) {
                    factors.add(Factor(9, "최신 뉴스 영향", "최근 7일 뉴스 없음", "최근 7일 내 관련 뉴스가 없습니다 (중립 처리).", 0, 3, true))
                } else {
                    var net = 0.0
                    recent.forEach { n ->
                        val w = if (inp.nowSec - n.publishedAt <= 72 * 3600) 2.0 else 1.0
                        net += when (n.sentiment) { Sentiment.POSITIVE -> w; Sentiment.NEGATIVE -> -w; else -> 0.0 }
                    }
                    val pos = recent.count { it.sentiment == Sentiment.POSITIVE }; val neg = recent.count { it.sentiment == Sentiment.NEGATIVE }
                    val s = when { net >= 4 -> 2; net >= 1.5 -> 1; net <= -4 -> -2; net <= -1.5 -> -1; else -> 0 }
                    val st = when { s > 0 -> "호재 우위"; s < 0 -> "악재 우위"; else -> "중립" }
                    val top = recent.filter { it.sentiment != Sentiment.NEUTRAL }.take(2).joinToString(" / ") { "「${it.title.take(40)}」" }
                    factors.add(Factor(9, "최신 뉴스 영향", st,
                        "최근 7일 ${recent.size}건 중 호재 ${pos}건, 악재 ${neg}건 (제목 키워드 자동 분류). $top", s, 3, true))
                }
            }
        }
        // 10. 실적 발표 등 주요 일정
        run {
            val ev = inp.events
            if (ev == null) {
                factors.add(Factor(10, "실적 발표 등 주요 일정", "일정 미조회", "일정 데이터를 불러오지 못했습니다.", 0, 2, false))
            } else {
                val soon = ev.filter { it.daysLeft in 0..7 }
                var s = 0
                val notes = ArrayList<String>()
                soon.forEach { e ->
                    if (e.title.contains("실적")) { s--; notes.add("${e.title} D-${e.daysLeft} → 발표 전후 갭 위험") }
                    if (e.title.contains("FOMC") && e.daysLeft <= 3) { s--; notes.add("FOMC D-${e.daysLeft} → 시장 변동성 확대 가능") }
                }
                if (notes.isEmpty()) notes.add("7일 이내 확인된 주요 일정 없음" + if (kr) " (한국 기업 실적일은 무료 데이터 미제공 → DART 공시 확인)" else "")
                factors.add(Factor(10, "실적 발표 등 주요 일정", if (s < 0) "일정 리스크 있음" else "일정 리스크 낮음", notes.joinToString(" · "), s.coerceIn(-2, 0), 2, true))
            }
        }

        // ─── 종합 점수 ───
        val avail = factors.filter { it.available }
        val maxAbs = avail.sumOf { it.weight } * 2.0
        val raw = avail.sumOf { it.weight * it.score }.toDouble()
        val score = (50 + raw / maxAbs * 50).roundToInt().coerceIn(0, 100)

        var verdict = when {
            score >= 72 -> Verdict.STRONG_BUY
            score >= 58 -> Verdict.SPLIT_BUY
            score >= 43 -> Verdict.HOLD
            score >= 32 -> Verdict.SPLIT_SELL
            else -> Verdict.AVOID
        }
        val trendS = factors[0].score; val momS = factors[1].score
        if (trendS <= -2 && momS <= -1) { verdict = Verdict.AVOID; warnings.add("강한 하락 추세 + 하락 모멘텀으로 '매수 금지' 판정") }
        if (atrPct > 8 && trendS < 0 && verdict != Verdict.AVOID) { verdict = Verdict.AVOID; warnings.add("하락 추세 중 일 변동폭 8% 초과로 위험 판정") }
        if (verdict == Verdict.STRONG_BUY && (rsi > 75 || extension > 3)) { verdict = Verdict.SPLIT_BUY; warnings.add("단기 과열(RSI·이격)로 '적극 매수' → '분할매수'로 낮춤") }
        if (verdict == Verdict.STRONG_BUY && factors[9].score <= -1) { verdict = Verdict.SPLIT_BUY; warnings.add("임박한 주요 일정으로 '적극 매수' → '분할매수'로 낮춤") }

        val strategy = buildStrategy(price, atr, kr, supports, resistances, bars, ma60, ma120, verdict)
        val rrNow = strategy.riskReward ?: 0.0
        if ((verdict == Verdict.STRONG_BUY || verdict == Verdict.SPLIT_BUY) && rrNow < 0.8) {
            verdict = if (verdict == Verdict.STRONG_BUY) Verdict.SPLIT_BUY else Verdict.HOLD
            warnings.add("손익비 ${"%.2f".format(rrNow)}:1 로 불리하여 판단을 한 단계 낮춤")
        }

        val ranked = avail.sortedByDescending { it.weight * it.score }
        val reasons = ranked.filter { it.score > 0 }.take(3).map { "${it.title}: ${it.status} — ${it.detail}" }
        val risks = ranked.reversed().filter { it.score < 0 }.take(3).map { "${it.title}: ${it.status} — ${it.detail}" }

        val summary = buildString {
            append("${verdict.label} (종합 ${score}점/100). ")
            append(factors[0].status).append(", ").append(factors[1].status).append(", ").append(factors[6].status).append(". ")
            strategy.riskReward?.let { append("계획 평균단가 기준 손익비 ${"%.2f".format(it)}:1. ") }
            if (verdict == Verdict.AVOID) append("신규 매수는 보류하고 추세 회복(20일선 회복·거래량 동반 반등)을 확인하세요.")
        }

        return AnalysisResult(verdict, score, summary, reasons, risks, factors, strategy,
            supports.take(5), resistances.take(5), price, atr, atrPct, rsi, ret120, warnings)
    }

    /** 가까운 가격 후보 병합 (근거 문구 합침) */
    private fun merge(sorted: List<SRPoint>, tol: Double): List<SRPoint> {
        val out = ArrayList<SRPoint>()
        for (s in sorted) {
            val last = out.lastOrNull()
            if (last != null && abs(last.price - s.price) <= tol) {
                if (!last.basis.contains(s.basis)) out[out.size - 1] = SRPoint(last.price, last.basis + " + " + s.basis)
            } else out.add(s)
        }
        return out
    }

    private fun touches(bars: List<Bar>, level: Double, below: Boolean): Int =
        bars.takeLast(20).count { if (below) it.l <= level else it.h >= level }

    private fun buildStrategy(
        price: Double, atr: Double, kr: Boolean, sup: List<SRPoint>, res: List<SRPoint>,
        bars: List<Bar>, ma60: Double, ma120: Double, verdict: Verdict,
    ): Strategy {
        val r = { x: Double -> Indicators.roundPrice(x, kr) }
        val diff = { x: Double -> (x / price - 1) * 100 }

        // ── 매수가 (현재가에서 너무 멀지 않게: 1차는 ATR 1.2배 이내) ──
        val b1src = sup.firstOrNull { it.price >= price - 1.2 * atr }
            ?: SRPoint(price - 0.5 * atr, "근접 지지선 없음 → 평균 변동폭(ATR)의 0.5배 눌림목")
        val b1 = r(b1src.price)
        val b2src = sup.firstOrNull { it.price <= b1 - 0.4 * atr && it.price >= price - 3 * atr }
            ?: SRPoint(b1 - 1.0 * atr, "1차 매수가 − ATR 1배")
        val b2 = r(b2src.price)
        val b3src = sup.firstOrNull { it.price <= b2 - 0.4 * atr && it.price >= price - 5 * atr }
            ?: SRPoint(b2 - 1.2 * atr, "2차 매수가 − ATR 1.2배")
        val b3 = r(b3src.price)

        // ── 손절 ──
        val stopSrc = sup.firstOrNull { it.price < b3 - 0.2 * atr && it.price >= b3 - 1.5 * atr }
        val stop = r(if (stopSrc != null) stopSrc.price - 0.3 * atr else b3 - 1.0 * atr)
        val stopBasis = if (stopSrc != null) "${stopSrc.basis} 아래 (ATR 0.3배 여유)" else "3차 매수가 − ATR 1배 (변동폭 기준)"

        // ── 추세 붕괴 ──
        val (tbPrice, tbBasis) = when {
            !ma60.isNaN() && ma60 < price -> ma60 to "60일 이동평균선 (중기 추세 기준선)"
            !ma120.isNaN() && ma120 < price -> ma120 to "120일 이동평균선 (장기 추세 기준선)"
            else -> bars.takeLast(60).minOf { it.l } to "최근 60일 최저가 (이탈 시 하락 추세 연장)"
        }
        val tb = r(tbPrice)

        // ── 목표가 ──
        val t1src = res.firstOrNull { it.price >= price + 0.6 * atr } ?: SRPoint(price + 1.5 * atr, "상단 저항 없음 → ATR 1.5배")
        val t1 = r(t1src.price)
        val t2src = res.firstOrNull { it.price >= t1 + 0.5 * atr } ?: SRPoint(t1 + 1.5 * atr, "1차 목표가 + ATR 1.5배")
        val t2 = r(t2src.price)
        val t3src = res.firstOrNull { it.price >= t2 + 0.5 * atr } ?: SRPoint(t2 + 2.0 * atr, "2차 목표가 + ATR 2배")
        val t3 = r(t3src.price)

        fun fromB1(x: Double) = "1차 매수가 기준 ${Fmt.pct((x / b1 - 1) * 100)}"

        val buys = listOf(
            PriceLevel("1차 매수가", b1, diff(b1), b1src.basis, "계획 물량의 30~40% 매수. 종가가 이 가격 위에서 지지되는지 확인.",
                "최근 20거래일 중 ${touches(bars, b1, true)}일 장중 도달"),
            PriceLevel("2차 매수가", b2, diff(b2), b2src.basis, "추가 하락 시 30% 추가. 거래량 터진 급락이면 하루 관망 후 진행.",
                "최근 20거래일 중 ${touches(bars, b2, true)}일 장중 도달"),
            PriceLevel("3차 매수가", b3, diff(b3), b3src.basis, "마지막 30%. 이후 손절가 이탈 시 계획대로 정리.",
                "최근 20거래일 중 ${touches(bars, b3, true)}일 장중 도달"),
        )
        val targets = listOf(
            PriceLevel("1차 목표가", t1, diff(t1), t1src.basis, "보유 물량 30% 매도 후 손절가를 매수 평균가로 올림.", fromB1(t1)),
            PriceLevel("2차 목표가", t2, diff(t2), t2src.basis, "30~40% 추가 매도, 나머지는 추세 추종.", fromB1(t2)),
            PriceLevel("3차 목표가", t3, diff(t3), t3src.basis, "잔여 물량 정리 또는 직전 저점 기준 추적 손절.", fromB1(t3)),
        )
        val stopLv = PriceLevel("손절 기준가", stop, diff(stop), stopBasis, "종가 기준 이탈 시 손절 (손실 제한 우선).", fromB1(stop))
        val tbLv = PriceLevel("추세 붕괴 기준가", tb, diff(tb), tbBasis, "종가 이탈 시 중기 보유 판단 철회, 비중 축소.")

        val nearRes = res.firstOrNull()?.price
        val chase = min(nearRes?.let { it - 0.25 * atr } ?: Double.MAX_VALUE, bars.let {
            val c = DoubleArray(it.size) { k -> it[k].c }
            val ma20 = Indicators.sma(c, 20).last()
            ma20 + 2.5 * atr
        })
        val noBuyFrom = r(max(chase, price * 1.0))
        val noBuyText = when {
            verdict == Verdict.AVOID -> "현재 판단이 '매수 금지'이므로 모든 가격대에서 신규 매수를 보류하세요."
            chase <= price -> "현재가가 이미 추격매수 금지 구간입니다 (저항선 근접 또는 20일선 대비 과도한 이격). 눌림을 기다리세요."
            else -> "${Fmt.price(noBuyFrom, kr)} 이상은 저항선 근접·과열 구간으로 추격매수 금지."
        }
        // 손익비 = (2차 목표가 − 계획 평균단가) / (계획 평균단가 − 손절가), 평균단가 = 1·2·3차 40:30:30
        val avgEntry = b1 * 0.4 + b2 * 0.3 + b3 * 0.3
        val rr = if (avgEntry > stop) (t2 - avgEntry) / (avgEntry - stop) else null
        return Strategy(buys, noBuyFrom, noBuyText, targets, stopLv, tbLv, rr)
    }
}
