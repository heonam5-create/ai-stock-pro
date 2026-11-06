package com.aistock.pro.data

import android.util.Xml
import com.aistock.pro.analysis.NewsClassifier
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Google 뉴스 RSS (무료, 키 불필요, 개인 열람 용도).
 * 최근 7일(when:7d) 기사만 요청하고, 게시 시각을 그대로 표시하여 과거 기사를 오늘 기사처럼 보이지 않게 함.
 */
object NewsSource {
    private const val MAX_AGE_SEC = 7L * 24 * 3600

    private fun url(query: String, korean: Boolean): String {
        val q = URLEncoder.encode("$query when:7d", "UTF-8")
        return if (korean) "https://news.google.com/rss/search?q=$q&hl=ko&gl=KR&ceid=KR:ko"
        else "https://news.google.com/rss/search?q=$q&hl=en-US&gl=US&ceid=US:en"
    }

    suspend fun forStock(ref: StockRef): List<NewsItem> {
        val kr = ref.nameKr ?: StockDirectory.koreanNameFor(ref.symbol)
        val feeds = ArrayList<Pair<String, Boolean>>()
        if (ref.market == Market.KR) {
            feeds.add("\"${kr ?: ref.name}\" 주가" to true)
            feeds.add("\"${kr ?: ref.name}\"" to true)
        } else {
            val en = ref.name.replace(Regex("(?i),? (inc\\.?|corporation|corp\\.?|ltd\\.?|holdings?|plc|co\\.)$"), "").trim()
            feeds.add("\"$en\" OR ${ref.symbol} stock" to false)
            if (kr != null) feeds.add("\"$kr\" 주가" to true)
        }
        val all = ArrayList<NewsItem>()
        var lastErr: Exception? = null
        for ((q, ko) in feeds) {
            try { all.addAll(fetch(url(q, ko), ref.symbol, kr ?: ref.name)) } catch (e: Exception) { lastErr = e }
        }
        if (all.isEmpty() && lastErr != null) throw lastErr
        return finish(all)
    }

    /** 시장 전체 경제 뉴스 (금리·CPI·FOMC·고용·반도체) */
    suspend fun macro(): List<NewsItem> {
        val all = ArrayList<NewsItem>()
        runCatching { all.addAll(fetch(url("FOMC OR 금리 OR CPI OR 고용지표 OR 연준", true), null, null)) }
        runCatching { all.addAll(fetch(url("반도체 업황 OR HBM OR 메모리 가격", true), null, null)) }
        runCatching { all.addAll(fetch(url("Fed OR CPI OR \"jobs report\" stocks", false), null, null)) }
        return finish(all).take(30)
    }

    fun finish(list: List<NewsItem>): List<NewsItem> {
        val now = System.currentTimeMillis() / 1000
        val seen = HashSet<String>()
        return list.filter { now - it.publishedAt in -3600..MAX_AGE_SEC }
            .filter { seen.add(it.title.take(40)) }
            .sortedByDescending { it.publishedAt }
            .take(40)
            .map { NewsClassifier.classify(it) }
    }

    private suspend fun fetch(url: String, symbol: String?, name: String?): List<NewsItem> {
        val xml = Http.get(url, retries = 1)
        return parseRss(xml, symbol, name)
    }

    private val rfc: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

    fun parseRss(xml: String, symbol: String?, name: String?): List<NewsItem> {
        val out = ArrayList<NewsItem>()
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))
        var inItem = false
        var tag = ""
        var title = ""; var link = ""; var date = ""; var source = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "item") { inItem = true; title = ""; link = ""; date = ""; source = "" }
                }
                XmlPullParser.TEXT -> if (inItem) {
                    val t = p.text ?: ""
                    when (tag) {
                        "title" -> title += t
                        "link" -> link += t.trim()
                        "pubDate" -> date += t.trim()
                        "source" -> source += t
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "item") {
                        inItem = false
                        val ts = runCatching { ZonedDateTime.parse(date, rfc).toEpochSecond() }.getOrNull()
                        if (title.isNotBlank() && ts != null) {
                            // 구글 뉴스 제목 끝의 " - 언론사" 제거
                            val src = source.trim().ifBlank { title.substringAfterLast(" - ", "") }
                            val clean = if (src.isNotBlank() && title.endsWith(" - $src")) title.removeSuffix(" - $src") else title
                            out.add(NewsItem(clean.trim(), src.ifBlank { "Google 뉴스" }, link, ts, symbol, name))
                        }
                    }
                    tag = ""
                }
            }
            ev = p.next()
        }
        return out
    }
}
