package com.aistock.pro.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 네이버 금융 실시간 시세 (API 키 불필요, 비공식 공개 엔드포인트 — 개인·비상업 용도).
 * 2026-11 GitHub Actions 실측:
 *  - 국내: polling.finance.naver.com/api/realtime/domestic/stock/{6자리}  → delayTime 0(실시간), KRX 가격 + NXT(넥스트레이드) 가격
 *  - 해외: polling.finance.naver.com/api/realtime/worldstock/stock/{코드} → delayTime 0, 정규장 + 프리/애프터마켓 가격
 *    나스닥은 "티커.O", NYSE·AMEX 는 접미사 없이 티커 그대로.
 *  - 미국 야간 대체거래소(국내 증권사 "주간거래") 가격은 제공되지 않음.
 *  - 일봉: api.stock.naver.com/chart/{domestic|foreign}/item/{코드}/day (Yahoo 차단 시 대체)
 */
object NaverSource {
    const val NAME = "네이버 금융"
    private const val POLL = "https://polling.finance.naver.com/api/realtime"
    private const val CHART = "https://api.stock.naver.com/chart"
    private val usCode = ConcurrentHashMap<String, String>()   // NVDA → NVDA.O

    data class Snap(
        val code: String,
        val name: String?,
        val price: Double,
        val change: Double?,
        val changePct: Double?,
        val open: Double?,
        val high: Double?,
        val low: Double?,
        val volume: Double?,
        val marketStatus: String,      // OPEN / CLOSE / PREOPEN ...
        val tradedAt: Long,            // epoch 초
        val delayMin: Int,
        val ext: ExtPrice?,
        val pollingMs: Long?,
        val zone: String,
        val currency: String,
    ) {
        val prevClose: Double? get() = change?.let { price - it }
        val open_: Boolean get() = marketStatus.equals("OPEN", true)
    }

    fun num(s: String?): Double? {
        if (s == null) return null
        val t = s.replace(",", "").replace("+", "").trim()
        return t.toDoubleOrNull()
    }

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

    fun time(s: String?): Long? = s?.let { runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() }

    /** 등락 코드(1 상한, 2 상승, 3 보합, 4 하한, 5 하락)로 부호 보정 */
    private fun signed(v: Double?, o: JSONObject?): Double? {
        if (v == null) return null
        val code = o?.optJSONObject("compareToPreviousPrice")?.optString("code")
        return if ((code == "4" || code == "5") && v > 0) -v else v
    }

    private fun sessionOf(type: String?): Session = when (type?.uppercase()) {
        "PRE_MARKET" -> Session.PRE
        "AFTER_MARKET" -> Session.POST
        "REGULAR_MARKET" -> Session.REGULAR
        else -> Session.UNKNOWN
    }

    /** polling 응답 1건 파싱 (국내·해외 공통 형식) */
    fun parse(json: String, kr: Boolean, nowSec: Long = System.currentTimeMillis() / 1000): Snap {
        val root = JSONObject(json)
        val arr = root.optJSONArray("datas")
        if (arr == null || arr.length() == 0) throw DataException("네이버 금융에 해당 종목 시세가 없습니다.")
        val d = arr.getJSONObject(0)
        val price = num(d.str("closePriceRaw") ?: d.str("closePrice"))
            ?: throw DataException("네이버 금융 시세 형식 오류(현재가 없음)")
        val chg = signed(num(d.str("compareToPreviousClosePriceRaw") ?: d.str("compareToPreviousClosePrice")), d)
        val pct = signed(num(d.str("fluctuationsRatioRaw") ?: d.str("fluctuationsRatio")), d)
        val ex = d.optJSONObject("stockExchangeType")
        val zone = ex?.optString("zoneId")?.let { if (it == "EST5EDT") "America/New_York" else it }?.ifBlank { null }
            ?: if (kr) "Asia/Seoul" else "America/New_York"
        val tradedAt = time(d.str("localTradedAt")) ?: nowSec
        val ext = d.optJSONObject("overMarketPriceInfo")?.let { o ->
            val p = num(o.str("overPrice")) ?: return@let null
            val ses = sessionOf(o.str("tradingSessionType"))
            val label = if (kr) "NXT ${if (ses == Session.REGULAR) "메인마켓" else ses.label}" else ses.label
            ExtPrice(
                session = if (kr) Session.NXT else ses,
                label = label,
                price = p,
                change = signed(num(o.str("compareToPreviousClosePrice")), o),
                changePct = signed(num(o.str("fluctuationsRatio")), o),
                time = time(o.str("localTradedAt")) ?: tradedAt,
                active = o.optString("overMarketStatus").equals("OPEN", true),
            )
        }
        val vol = num(d.str("accumulatedTradingVolumeRaw") ?: d.str("accumulatedTradingVolume"))
        val cur = d.optJSONObject("currencyType")?.optString("code")?.ifBlank { null } ?: if (kr) "KRW" else "USD"
        return Snap(
            code = d.str("itemCode") ?: d.str("reutersCode") ?: "",
            name = d.str("stockName"),
            price = price, change = chg, changePct = pct,
            open = num(d.str("openPriceRaw") ?: d.str("openPrice")),
            high = num(d.str("highPriceRaw") ?: d.str("highPrice")),
            low = num(d.str("lowPriceRaw") ?: d.str("lowPrice")),
            volume = vol,
            marketStatus = d.str("marketStatus") ?: "UNKNOWN",
            tradedAt = tradedAt,
            delayMin = ex?.optInt("delayTime", 0) ?: 0,
            ext = ext,
            pollingMs = if (root.has("pollingInterval")) root.optLong("pollingInterval") else null,
            zone = zone,
            currency = cur,
        )
    }

    suspend fun quote(ref: StockRef): Snap {
        return if (ref.market == Market.KR) {
            parse(Http.get("$POLL/domestic/stock/${ref.code}", retries = 1), true)
        } else {
            val t = ref.symbol.uppercase()
            if (t.startsWith("^") || t.contains("=")) throw DataException("네이버 미지원 종목")
            val known = usCode[t]
            val cands = if (known != null) listOf(known) else listOf("$t.O", t, "$t.K")
            var last: Exception? = null
            for (c in cands) {
                try {
                    val s = parse(Http.get("$POLL/worldstock/stock/$c", retries = 1), false)
                    usCode[t] = c
                    return s
                } catch (e: Exception) { last = e }
            }
            throw last ?: DataException("네이버 금융에 해당 종목이 없습니다.")
        }
    }

    private val ymdhm = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    private val ymd = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun parseDaily(json: String, zone: ZoneId, openAt: LocalTime): List<Bar> {
        val arr = JSONArray(json)
        val out = ArrayList<Bar>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val day = runCatching { LocalDate.parse(o.optString("localDate"), ymd) }.getOrNull() ?: continue
            val c = o.optDouble("closePrice", Double.NaN)
            if (c.isNaN() || c <= 0) continue
            val op = o.optDouble("openPrice", c).takeIf { !it.isNaN() && it > 0 } ?: c
            val h = o.optDouble("highPrice", c).takeIf { !it.isNaN() && it > 0 } ?: maxOf(op, c)
            val l = o.optDouble("lowPrice", c).takeIf { !it.isNaN() && it > 0 } ?: minOf(op, c)
            val v = o.optDouble("accumulatedTradingVolume", 0.0).takeIf { !it.isNaN() } ?: 0.0
            out.add(Bar(day.atTime(openAt).atZone(zone).toEpochSecond(), op, maxOf(h, op, c), minOf(l, op, c), c, v))
        }
        return out.sortedBy { it.t }
    }

    /** 일봉 약 2년 (Yahoo 일봉 실패 시 대체용) */
    suspend fun daily(ref: StockRef): List<Bar> {
        val kr = ref.market == Market.KR
        val zone = ZoneId.of(if (kr) "Asia/Seoul" else "America/New_York")
        val now = java.time.ZonedDateTime.now(zone)
        val range = "startDateTime=${now.minusYears(2).format(ymdhm)}&endDateTime=${now.plusDays(1).format(ymdhm)}"
        val code = if (kr) ref.code else (usCode[ref.symbol.uppercase()] ?: runCatching { quote(ref).code }.getOrNull() ?: ref.symbol)
        val url = if (kr) "$CHART/domestic/item/$code/day?$range" else "$CHART/foreign/item/$code/day?$range"
        return parseDaily(Http.get(url, retries = 1), zone, if (kr) LocalTime.of(9, 0) else LocalTime.of(9, 30))
    }
}
