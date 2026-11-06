package com.aistock.pro.data

import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 공공데이터포털 「금융위원회_주식시세정보」 (무료, 사용자 본인 인증키 필요)
 * - 한국 전 종목 한글명 검색, 시가총액(전 거래일 종가 기준)
 * - 일 단위 데이터(실시간 아님)
 */
object DataGoKrSource {
    private const val BASE = "https://apis.data.go.kr/1160100/service/GetStockSecuritiesInfoService/getStockPriceInfo"
    private val ymd = DateTimeFormatter.ofPattern("yyyyMMdd")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun begin(): String = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(10).format(ymd)

    data class Row(val basDt: String, val code: String, val name: String, val market: String, val marketCap: Double?)

    private suspend fun query(key: String, params: String): List<Row> {
        val url = "$BASE?serviceKey=${enc(key)}&resultType=json&pageNo=1&numOfRows=100&beginBasDt=${begin()}&$params"
        val txt = Http.get(url, retries = 1)
        if (txt.trimStart().startsWith("<")) throw DataException("공공데이터 인증키 오류 또는 서비스 미승인 상태입니다.")
        val body = JSONObject(txt).optJSONObject("response")?.optJSONObject("body") ?: return emptyList()
        val items = body.optJSONObject("items")?.optJSONArray("item") ?: return emptyList()
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            Row(o.optString("basDt"), o.optString("srtnCd"), o.optString("itmsNm"), o.optString("mrktCtg"),
                o.optString("mrktTotAmt").toDoubleOrNull())
        }
    }

    /** 한글명 검색 → 최신 거래일 기준 종목 목록 */
    suspend fun searchByName(key: String, name: String): List<StockRef> {
        val rows = query(key, "likeItmsNm=${enc(name)}")
        val latest = rows.maxOfOrNull { it.basDt } ?: return emptyList()
        return rows.filter { it.basDt == latest }.mapNotNull { r ->
            val suffix = when (r.market.uppercase()) { "KOSPI" -> "KS"; "KOSDAQ" -> "KQ"; else -> null } ?: return@mapNotNull null
            StockRef("${r.code}.$suffix", r.name, r.name, if (suffix == "KS") "코스피" else "코스닥", Market.KR)
        }.take(15)
    }

    /** 시가총액 (원) + 기준일 */
    suspend fun marketCap(key: String, code: String): Pair<Double, String>? {
        val rows = query(key, "likeSrtnCd=${enc(code)}").filter { it.code == code }
        val r = rows.maxByOrNull { it.basDt } ?: return null
        return r.marketCap?.let { it to r.basDt }
    }
}

/**
 * Finnhub (무료 키: 미국 주식 · 분당 60회 · 개인 비상업 용도)
 * - 회사 프로필(시가총액), 실적 발표 일정, 회사 뉴스
 * - 무료 키로는 과거 캔들/한국 시세 불가 → 차트는 Yahoo 사용
 */
object FinnhubSource {
    private const val BASE = "https://finnhub.io/api/v1"
    private val ymd = DateTimeFormatter.ISO_LOCAL_DATE

    suspend fun marketCapUsd(key: String, symbol: String): Double? {
        val o = JSONObject(Http.get("$BASE/stock/profile2?symbol=${symbol}&token=$key", retries = 1))
        val m = o.optDouble("marketCapitalization", Double.NaN)  // 백만 달러 단위
        return if (m.isNaN() || m <= 0) null else m * 1_000_000.0
    }

    suspend fun earnings(key: String, symbol: String): List<EventItem> {
        val today = LocalDate.now(ZoneId.of("America/New_York"))
        val url = "$BASE/calendar/earnings?from=${today.format(ymd)}&to=${today.plusDays(90).format(ymd)}&symbol=$symbol&token=$key"
        val arr = JSONObject(Http.get(url, retries = 1)).optJSONArray("earningsCalendar") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val d = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull() ?: return@mapNotNull null
            val hour = when (o.optString("hour")) { "bmo" -> "장 시작 전"; "amc" -> "장 마감 후"; else -> "" }
            val eps = o.optDouble("epsEstimate", Double.NaN)
            EventItem(d.toString(), "$symbol 실적 발표 $hour".trim(),
                if (eps.isNaN()) "EPS 예상치 정보 없음" else "EPS 예상 %.2f".format(eps),
                java.time.temporal.ChronoUnit.DAYS.between(today, d))
        }.sortedBy { it.daysLeft }
    }

    suspend fun companyNews(key: String, symbol: String, name: String?): List<NewsItem> {
        val today = LocalDate.now(ZoneId.of("America/New_York"))
        val url = "$BASE/company-news?symbol=$symbol&from=${today.minusDays(7).format(ymd)}&to=${today.format(ymd)}&token=$key"
        val txt = Http.get(url, retries = 1)
        val arr = org.json.JSONArray(txt)
        return (0 until minOf(arr.length(), 25)).map { i ->
            val o = arr.getJSONObject(i)
            NewsItem(o.optString("headline"), o.optString("source", "Finnhub"), o.optString("url"),
                o.optLong("datetime"), symbol, name)
        }.filter { it.title.isNotBlank() && it.link.startsWith("http") }
    }
}
