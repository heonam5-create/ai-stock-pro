package com.aistock.pro.data

import com.aistock.pro.analysis.AnalysisEngine
import com.aistock.pro.analysis.AnalysisInput
import com.aistock.pro.analysis.AnalysisResult
import com.aistock.pro.analysis.Recommendation
import com.aistock.pro.analysis.Recommender
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 모든 데이터 조회의 단일 진입점 (캐시·재시도·대체 경로 포함) */
class Repository(private val prefs: Prefs) {

    private val chartCache = ConcurrentHashMap<String, ChartData>()
    private val capCache = ConcurrentHashMap<String, Pair<Double?, Long>>()
    private val newsCache = ConcurrentHashMap<String, Pair<List<NewsItem>, Long>>()
    private val symbolFix = ConcurrentHashMap<String, String>()   // 005930.KQ → 005930.KS 등 시장 보정

    fun clearCache() { chartCache.clear(); capCache.clear(); newsCache.clear() }

    private fun alt(symbol: String): String? = when {
        symbol.endsWith(".KS") -> symbol.removeSuffix(".KS") + ".KQ"
        symbol.endsWith(".KQ") -> symbol.removeSuffix(".KQ") + ".KS"
        else -> null
    }

    fun fixed(symbol: String) = symbolFix[symbol] ?: symbol

    /** 차트 조회. 캐시 유효시간: 분봉 60초, 일봉 이상 120초 (BEST5 는 더 길게 허용) */
    suspend fun chart(symbol: String, interval: Interval, maxAgeMs: Long = if (interval.intraday) 60_000L else 120_000L): ChartData {
        val sym = fixed(symbol)
        val key = "$sym|${interval.name}"
        chartCache[key]?.let { if (System.currentTimeMillis() - it.fetchedAt < maxAgeMs) return it }
        val data = try {
            YahooSource.chart(sym, interval).also { if (it.bars.isEmpty()) throw DataException("empty") }
        } catch (e: DataException) {
            val a = alt(sym) ?: throw if (e.message == "empty") DataException("'$sym' 의 ${interval.label} 데이터가 제공되지 않습니다.") else e
            // 코스피↔코스닥 이전 상장 대비 자동 재시도
            val d2 = runCatching { YahooSource.chart(a, interval) }.getOrNull()
            if (d2 != null && d2.bars.isNotEmpty()) { symbolFix[symbol] = a; d2 }
            else throw DataException("'${sym.substringBefore(".")}' 종목의 ${interval.label} 데이터를 찾을 수 없습니다.")
        }
        chartCache[key] = data
        return data
    }

    fun cachedChart(symbol: String, interval: Interval): ChartData? = chartCache["${fixed(symbol)}|${interval.name}"]

    fun buildQuote(ref: StockRef, cd: ChartData, marketCap: Double? = null, capSource: String? = null): Quote {
        val bars = cd.bars; val m = cd.meta
        val price = m.price ?: bars.lastOrNull()?.c ?: throw DataException("현재가 데이터가 없습니다.")
        val zone = runCatching { ZoneId.of(m.timezone) }.getOrDefault(ZoneId.of("UTC"))
        fun day(t: Long) = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
        val mt = if (m.marketTime > 0) m.marketTime else bars.lastOrNull()?.t ?: 0L
        val sameDay = bars.isNotEmpty() && day(bars.last().t) == day(mt)
        val prev = when {
            bars.size >= 2 && sameDay -> bars[bars.size - 2].c
            bars.isNotEmpty() && !sameDay -> bars.last().c
            else -> null
        }
        val chg = prev?.let { price - it }
        val chgPct = prev?.let { (price / it - 1) * 100 }
        val last252 = bars.takeLast(252)
        val (open, status) = MarketClock.status(ref.market, mt)
        val name = if (ref.name.isBlank() || ref.name == ref.symbol) (m.longName ?: ref.name) else ref.name
        return Quote(
            ref = ref.copy(name = name, exchange = ref.exchange.ifBlank { m.exchange }),
            currency = m.currency, price = price, prevClose = prev, change = chg, changePct = chgPct,
            volume = m.volume ?: if (sameDay) bars.lastOrNull()?.v else null,
            dayHigh = m.dayHigh ?: if (sameDay) bars.lastOrNull()?.h else null,
            dayLow = m.dayLow ?: if (sameDay) bars.lastOrNull()?.l else null,
            high52 = m.high52 ?: last252.maxOfOrNull { it.h },
            low52 = m.low52 ?: last252.minOfOrNull { it.l },
            marketCap = marketCap, marketCapSource = capSource,
            marketTime = mt, timezone = m.timezone, marketOpen = open, statusText = status,
        )
    }

    /** 현재가 + 일봉 2년 (분석·52주 계산 공용) */
    suspend fun quote(ref: StockRef, withCap: Boolean = true): Quote {
        val cd = chart(ref.symbol, Interval.D1)
        val realRef = if (fixed(ref.symbol) != ref.symbol) ref.copy(symbol = fixed(ref.symbol)) else ref
        var cap: Double? = null; var src: String? = null
        if (withCap) {
            val cached = capCache[realRef.symbol]
            if (cached != null && System.currentTimeMillis() - cached.second < 6 * 3600_000L) {
                cap = cached.first; src = if (cap != null) capSourceName(realRef) else null
            } else {
                cap = runCatching { marketCap(realRef) }.getOrNull()
                capCache[realRef.symbol] = cap to System.currentTimeMillis()
                src = if (cap != null) capSourceName(realRef) else null
            }
        }
        return buildQuote(realRef, cd, cap, src)
    }

    private fun capSourceName(ref: StockRef) = if (ref.market == Market.KR) "공공데이터포털(전 거래일 기준)" else "Finnhub"

    private suspend fun marketCap(ref: StockRef): Double? = when (ref.market) {
        Market.KR -> prefs.dataGoKrKey.takeIf { it.isNotBlank() }?.let { DataGoKrSource.marketCap(it, ref.code)?.first }
        Market.US -> prefs.finnhubKey.takeIf { it.isNotBlank() }?.let { FinnhubSource.marketCapUsd(it, ref.symbol) }
    }

    /** 검색: 내장 한글 사전 → 6자리 코드 → 공공데이터(한글명) → Yahoo(영문/티커) */
    suspend fun search(query: String): List<StockRef> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, StockRef>()
        StockDirectory.search(q).forEach { out[it.symbol] = it.toRef() }
        if (Regex("^\\d{6}$").matches(q) && out.isEmpty()) {
            for (sfx in listOf("KS", "KQ")) {
                val cd = runCatching { YahooSource.chart("$q.$sfx", Interval.D1) }.getOrNull()
                if (cd != null && cd.bars.isNotEmpty()) {
                    out["$q.$sfx"] = StockRef("$q.$sfx", cd.meta.longName ?: q, null, if (sfx == "KS") "코스피" else "코스닥", Market.KR)
                    chartCache["$q.$sfx|${Interval.D1.name}"] = cd
                    break
                }
            }
        }
        val hasHangul = q.any { it in '가'..'힣' }
        if (hasHangul && prefs.dataGoKrKey.isNotBlank() && out.size < 5) {
            runCatching { DataGoKrSource.searchByName(prefs.dataGoKrKey, q) }.getOrNull()
                ?.forEach { if (!out.containsKey(it.symbol)) out[it.symbol] = it }
        }
        if (!hasHangul && out.size < 8) {
            runCatching { YahooSource.search(q) }.getOrNull()?.forEach { if (!out.containsKey(it.symbol)) out[it.symbol] = it }
        }
        return out.values.take(20)
    }

    suspend fun news(ref: StockRef, force: Boolean = false): List<NewsItem> {
        newsCache[ref.symbol]?.let { if (!force && System.currentTimeMillis() - it.second < 10 * 60_000) return it.first }
        val list = ArrayList<NewsItem>()
        var err: Exception? = null
        try { list.addAll(NewsSource.forStock(ref)) } catch (e: Exception) { err = e }
        if (ref.market == Market.US && prefs.finnhubKey.isNotBlank()) {
            runCatching { FinnhubSource.companyNews(prefs.finnhubKey, ref.symbol, ref.displayName) }.getOrNull()?.let { list.addAll(it) }
        }
        if (list.isEmpty() && err != null) throw err
        val result = NewsSource.finish(list)
        newsCache[ref.symbol] = result to System.currentTimeMillis()
        return result
    }

    suspend fun macroNews(): List<NewsItem> {
        newsCache["__macro"]?.let { if (System.currentTimeMillis() - it.second < 15 * 60_000) return it.first }
        val r = NewsSource.macro()
        newsCache["__macro"] = r to System.currentTimeMillis()
        return r
    }

    suspend fun events(ref: StockRef): List<EventItem> {
        val list = ArrayList(Events.upcomingMacro())
        if (ref.market == Market.US && prefs.finnhubKey.isNotBlank()) {
            runCatching { FinnhubSource.earnings(prefs.finnhubKey, ref.symbol) }.getOrNull()?.let { list.addAll(it) }
        }
        return list.sortedBy { it.daysLeft }
    }

    data class FullAnalysis(val quote: Quote, val result: AnalysisResult, val news: List<NewsItem>?, val events: List<EventItem>, val newsError: String?)

    /** AI 종합 분석: 항상 일봉 기준 */
    suspend fun analyze(ref: StockRef): FullAnalysis = coroutineScope {
        val q = async { quote(ref) }
        val newsDef = async { runCatching { news(ref) } }
        val evDef = async { events(ref) }
        val quote = q.await()
        val cd = chart(quote.ref.symbol, Interval.D1)
        val newsRes = newsDef.await()
        val ev = evDef.await()
        val result = AnalysisEngine.analyze(AnalysisInput(cd.bars, quote.price, quote.ref.market == Market.KR, newsRes.getOrNull(), ev))
        FullAnalysis(quote, result, newsRes.getOrNull(), ev, newsRes.exceptionOrNull()?.let { Http.friendly(it) })
    }

    data class BestResult(val picks: List<Recommendation>, val analyzed: Int, val failed: List<String>, val excluded: Int, val at: Long)

    /** BEST 5: 후보군 전체를 실제 일봉으로 분석 → 기술 점수 상위 10개는 뉴스까지 반영해 재분석 → 상위 5개 */
    suspend fun best5(market: Market, onProgress: (Int, Int) -> Unit): BestResult = coroutineScope {
        val base = if (market == Market.KR) StockDirectory.krUniverse else StockDirectory.usUniverse
        val extra = if (prefs.includeWatchInBest) prefs.loadWatchlist().filter { it.market == market }.map { it.symbol } else emptyList()
        val symbols = (base + extra).distinct()
        val sem = Semaphore(3)
        val done = AtomicInteger(0)
        val failed = java.util.Collections.synchronizedList(ArrayList<String>())
        val first = symbols.map { sym ->
            async {
                sem.withPermit {
                    try {
                        val ref = StockDirectory.bySymbol(sym)?.toRef()
                            ?: prefs.loadWatchlist().firstOrNull { it.symbol == sym }?.let { StockRef(it.symbol, it.name, it.name) }
                            ?: StockRef(sym, sym)
                        val cd = chart(sym, Interval.D1, maxAgeMs = 600_000L)
                        val q = buildQuote(ref, cd)
                        val r = AnalysisEngine.analyze(AnalysisInput(cd.bars, q.price, market == Market.KR, null, Events.upcomingMacro()))
                        Triple(q, r, cd)
                    } catch (e: Exception) {
                        failed.add(StockDirectory.koreanNameFor(sym) ?: sym); null
                    } finally {
                        onProgress(done.incrementAndGet(), symbols.size)
                        delay(150)
                    }
                }
            }
        }.mapNotNull { it.await() }

        val preliminary = first.mapNotNull { (q, r, _) -> Recommender.score(q.ref, r, q.marketTime)?.let { Triple(q, it, r) } }
            .sortedByDescending { it.second.total }.take(10)
        // 상위 후보는 뉴스 반영 재분석
        val finalList = preliminary.map { (q, _, _) ->
            async {
                sem.withPermit {
                    val cd = chart(q.ref.symbol, Interval.D1, maxAgeMs = 600_000L)
                    val n = runCatching { news(q.ref) }.getOrNull()
                    val r = AnalysisEngine.analyze(AnalysisInput(cd.bars, q.price, market == Market.KR, n, events(q.ref)))
                    Recommender.score(q.ref, r, q.marketTime)
                }
            }
        }.mapNotNull { it.await() }
        val excluded = first.size - first.count { (q, r, _) -> Recommender.score(q.ref, r, q.marketTime) != null }
        BestResult(Recommender.rank(finalList), first.size, failed.toList(), excluded, System.currentTimeMillis() / 1000)
    }

    /** 원/달러 환율 */
    suspend fun usdKrw(): Double? = runCatching {
        val cd = chart("KRW=X", Interval.D1, 300_000L)
        cd.meta.price ?: cd.bars.lastOrNull()?.c
    }.getOrNull()

    data class IndexQuote(val name: String, val price: Double, val changePct: Double?, val time: Long, val kr: Boolean)

    suspend fun indices(): List<IndexQuote> = coroutineScope {
        listOf("^KS11" to "코스피", "^KQ11" to "코스닥", "^GSPC" to "S&P 500", "^IXIC" to "나스닥", "KRW=X" to "원/달러").map { (s, n) ->
            async {
                runCatching {
                    val mk = if (s.startsWith("^K")) Market.KR else Market.US
                    val q = buildQuote(StockRef(s, n, n, "", mk), chart(s, Interval.D1, 180_000L))
                    IndexQuote(n, q.price, q.changePct, q.marketTime, mk == Market.KR || s == "KRW=X")
                }.getOrNull()
            }
        }.mapNotNull { it.await() }
    }
}
