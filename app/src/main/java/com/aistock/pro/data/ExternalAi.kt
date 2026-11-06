package com.aistock.pro.data

import com.aistock.pro.analysis.AnalysisResult
import com.aistock.pro.analysis.Fmt
import org.json.JSONArray
import org.json.JSONObject

/**
 * (선택) 외부 AI API 연결 — OpenAI 호환 "chat/completions" 형식.
 * 사용자가 설정 화면에 직접 주소·모델·키를 입력한 경우에만 동작하며, 기본 분석은 무료 로컬 엔진이 담당합니다.
 * 키는 기기 보안칩으로 암호화 저장되고, 앱 코드에는 어떤 키도 포함되어 있지 않습니다.
 */
object ExternalAi {
    suspend fun comment(endpoint: String, model: String, key: String, q: Quote, r: AnalysisResult): String {
        val kr = q.ref.market == Market.KR
        val facts = buildString {
            appendLine("종목: ${q.ref.displayName} (${q.ref.symbol})")
            appendLine("현재가: ${Fmt.price(q.price, kr)} (${Fmt.pct(q.changePct)}), 기준시각 ${MarketClock.format(q.marketTime, q.ref.market)}")
            appendLine("로컬 엔진 판단: ${r.verdict.label}, 점수 ${r.score}/100")
            r.factors.forEach { appendLine("- ${it.title}: ${it.status} / ${it.detail}") }
            r.strategy?.let { s ->
                appendLine("매수가: " + s.buys.joinToString { "${it.label} ${Fmt.price(it.price, kr)}" })
                appendLine("목표가: " + s.targets.joinToString { "${it.label} ${Fmt.price(it.price, kr)}" })
                appendLine("손절가: ${Fmt.price(s.stop.price, kr)}")
            }
        }
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.3)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content",
                    "너는 신중한 주식 분석 보조자다. 아래 제공된 데이터만 근거로 한국어로 5~8문장 의견을 써라. " +
                        "제공되지 않은 숫자·뉴스를 지어내지 말고, 투자 권유가 아니라는 점을 마지막에 한 줄로 밝혀라."))
                put(JSONObject().put("role", "user").put("content", facts))
            })
        }
        val url = endpoint.trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" }
        val res = JSONObject(Http.post(url, body.toString(), mapOf("Authorization" to "Bearer $key")))
        return res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?.takeIf { it.isNotBlank() } ?: throw DataException("외부 AI 응답 형식을 해석하지 못했습니다.")
    }
}
