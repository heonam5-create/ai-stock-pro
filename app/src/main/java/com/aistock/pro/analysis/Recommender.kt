package com.aistock.pro.analysis

import com.aistock.pro.data.StockRef
import kotlin.math.roundToInt

/** BEST 5 추천 점수 (실제 데이터로 계산된 AnalysisResult 를 9개 평가항목으로 환산) */
data class Recommendation(
    val ref: StockRef,
    val price: Double,
    val result: AnalysisResult,
    val total: Int,                       // 0~100
    val parts: List<Pair<String, Int>>,   // 항목명 to 0~10점
    val reason: String,
    val riskGrade: String,
    val dataTime: Long,
)

object Recommender {

    private fun f(s: Int) = ((s + 2) * 2.5).roundToInt()   // -2..2 → 0..10

    fun riskGrade(atrPct: Double, verdict: Verdict): String = when {
        verdict == Verdict.AVOID -> "매우 높음"
        atrPct.isNaN() -> "-"
        atrPct < 2.5 -> "낮음"
        atrPct < 4.0 -> "보통"
        atrPct < 6.0 -> "높음"
        else -> "매우 높음"
    }

    /** 추천 대상이 아니면 null (분석 불가·매수 금지·분할매도) */
    fun score(ref: StockRef, r: AnalysisResult, dataTime: Long): Recommendation? {
        if (r.verdict == Verdict.NA || r.verdict == Verdict.AVOID || r.verdict == Verdict.SPLIT_SELL) return null
        val fx = r.factors.associateBy { it.no }
        val st = r.strategy ?: return null
        val b1 = st.buys.first().price
        val dist = (r.price - b1) / r.price * 100
        val growth = when {
            r.ret120.isNaN() -> 5
            r.ret120 > 40 -> 10; r.ret120 > 20 -> 8; r.ret120 > 8 -> 6; r.ret120 > 0 -> 5; r.ret120 > -10 -> 3
            else -> 1
        }
        val rr = st.riskReward ?: 0.0
        val parts = listOf(
            "상승 추세" to f(fx[1]?.score ?: 0),
            "거래량 증가" to f(fx[5]?.score ?: 0),
            "기술적 지표" to ((f(fx[2]?.score ?: 0) + f(fx[7]?.score ?: 0)) / 2.0).roundToInt(),
            "성장성(120일 주가 성장률)" to growth,
            "호재·악재" to if (fx[9]?.available == true) f(fx[9]?.score ?: 0) else 5,
            "변동성 안정" to f(fx[8]?.score ?: 0),
            "지지선과의 거리" to when { dist < 2 -> 10; dist < 4 -> 7; dist < 7 -> 4; else -> 2 },
            "손익비" to when { rr >= 2.5 -> 10; rr >= 2.0 -> 8; rr >= 1.5 -> 6; rr >= 1.0 -> 4; else -> 1 },
            "단기 매매 적합성" to f(fx[3]?.score ?: 0),
        )
        val weights = listOf(1.4, 0.9, 1.1, 0.9, 0.8, 0.7, 1.0, 1.1, 1.0)
        val total = (parts.mapIndexed { idx, pr -> pr.second * weights[idx] }.sum() / (10 * weights.sum()) * 100).roundToInt()
        val top = parts.sortedByDescending { it.second }.take(3).joinToString(", ") { "${it.first} ${it.second}/10" }
        val reason = "${r.verdict.label} · $top. ${r.factors.firstOrNull()?.status ?: ""}, 손익비 ${"%.2f".format(rr)}:1"
        return Recommendation(ref, r.price, r, total, parts, reason, riskGrade(r.atrPct, r.verdict), dataTime)
    }

    fun rank(list: List<Recommendation>, n: Int = 5): List<Recommendation> =
        list.sortedWith(compareByDescending<Recommendation> { it.total }.thenByDescending { it.result.score }).take(n)
}
