package com.aistock.pro.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Yahoo Finance 차트/검색 (API 키 불필요, 비공식 공개 엔드포인트).
 * - 한국(.KS/.KQ)·미국 주식, 1분~월봉 모두 지원
 * - 가격은 주식분할이 반영(수정)된 값으로 제공됨
 * - 개인적·비상업적 용도로만 사용하세요 (Yahoo 이용약관). 자세한 내용은 docs/03_무료데이터API_정리.md
 */
object YahooSource {
    private val HOSTS = listOf("https://query1.finance.yahoo.com", "https://query2.finance.yahoo.com")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun chart(symbol: String, interval: Interval): ChartData {
        var lastErr: Exception? = null
        for (host in HOSTS) {
            try {
                val url = "$host/v8/finance/chart/${enc(symbol)}?interval=${interval.yahoo}&range=${interval.range}" +
                    "&includePrePost=false&events=div%2Csplits"
                return parseChart(Http.get(url), symbol, interval)
            } catch (e: Http.HttpError) {
                if (e.code == 404) throw DataException("'$symbol' 종목 데이터를 찾을 수 없습니다.")
                lastErr = e
            } catch (e: DataException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: DataException("차트 데이터를 불러오지 못했습니다.")
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private fun JSONArray?.at(i: Int): Double =
        if (this == null || i >= length() || isNull(i)) Double.NaN else optDouble(i)

    fun parseChart(json: String, symbol: String, interval: Interval): ChartData {
        val root = JSONObject(json).getJSONObject("chart")
        val results = root.optJSONArray("result")
        if (results == null || results.length() == 0) {
            val desc = root.optJSONObject("error")?.optString("description").orEmpty()
            throw DataException("'$symbol' 데이터가 없습니다. ${desc.take(80)}")
        }
        val r = results.getJSONObject(0)
        val m = r.getJSONObject("meta")
        val meta = ChartMeta(
            symbol = m.optString("symbol", symbol),
            currency = m.optString("currency", if (symbol.endsWith(".KS") || symbol.endsWith(".KQ")) "KRW" else "USD"),
            exchange = m.optString("fullExchangeName", m.optString("exchangeName", "")),
            longName = m.optString("longName", "").ifBlank { m.optString("shortName", "") }.ifBlank { null },
            price = m.optDoubleOrNull("regularMarketPrice"),
            marketTime = m.optLong("regularMarketTime", 0L),
            timezone = m.optString("exchangeTimezoneName", "UTC"),
            high52 = m.optDoubleOrNull("fiftyTwoWeekHigh"),
            low52 = m.optDoubleOrNull("fiftyTwoWeekLow"),
            dayHigh = m.optDoubleOrNull("regularMarketDayHigh"),
            dayLow = m.optDoubleOrNull("regularMarketDayLow"),
            volume = m.optDoubleOrNull("regularMarketVolume"),
            instrumentType = m.optString("instrumentType", "").ifBlank { null },
        )
        val ts = r.optJSONArray("timestamp")
        val q = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
        val bars = ArrayList<Bar>()
        if (ts != null && q != null) {
            val o = q.optJSONArray("open"); val h = q.optJSONArray("high"); val l = q.optJSONArray("low")
            val c = q.optJSONArray("close"); val v = q.optJSONArray("volume")
            for (i in 0 until ts.length()) {
                val cc = c.at(i)
                if (cc.isNaN() || cc <= 0) continue      // 거래 없는 구간(null) 제외
                var oo = o.at(i); var hh = h.at(i); var ll = l.at(i)
                if (oo.isNaN()) oo = cc
                if (hh.isNaN()) hh = maxOf(oo, cc)
                if (ll.isNaN()) ll = minOf(oo, cc)
                val vv = v.at(i).let { if (it.isNaN()) 0.0 else it }
                bars.add(Bar(ts.getLong(i), oo, maxOf(hh, oo, cc), minOf(ll, oo, cc), cc, vv))
            }
        }
        return ChartData(meta, bars, interval, System.currentTimeMillis())
    }

    /** 영문명·티커 온라인 검색 (주식/ETF만) */
    suspend fun search(query: String): List<StockRef> {
        val url = "${HOSTS[1]}/v1/finance/search?q=${enc(query)}&quotesCount=12&newsCount=0&listsCount=0&lang=en-US"
        val root = JSONObject(Http.get(url, retries = 1))
        val arr = root.optJSONArray("quotes") ?: return emptyList()
        val out = ArrayList<StockRef>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("quoteType")
            if (type != "EQUITY" && type != "ETF") continue
            val sym = o.optString("symbol")
            if (sym.isBlank()) continue
            // 지원 범위: 한국(.KS/.KQ) + 미국(접미사 없음)
            val isKr = sym.endsWith(".KS") || sym.endsWith(".KQ")
            if (!isKr && sym.contains(".")) continue
            val name = o.optString("longname").ifBlank { o.optString("shortname", sym) }
            val exch = o.optString("exchDisp", o.optString("exchange", ""))
            out.add(StockRef(sym, name, StockDirectory.koreanNameFor(sym), exch))
        }
        return out
    }
}
