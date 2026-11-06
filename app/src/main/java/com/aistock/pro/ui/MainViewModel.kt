package com.aistock.pro.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistock.pro.data.AlertRule
import com.aistock.pro.data.AlertType
import com.aistock.pro.data.ChartData
import com.aistock.pro.data.DataException
import com.aistock.pro.data.ExternalAi
import com.aistock.pro.data.Freshness
import com.aistock.pro.data.QuoteLogic
import com.aistock.pro.data.Http
import com.aistock.pro.data.Interval
import com.aistock.pro.data.Market
import com.aistock.pro.data.NewsItem
import com.aistock.pro.data.Prefs
import com.aistock.pro.data.Quote
import com.aistock.pro.data.Repository
import com.aistock.pro.data.StockDirectory
import com.aistock.pro.data.StockRef
import com.aistock.pro.data.UiState
import com.aistock.pro.data.WatchItem
import com.aistock.pro.ui.components.ChartLevels
import com.aistock.pro.ui.components.Overlays
import com.aistock.pro.ui.components.SubPanel
import com.aistock.pro.worker.AlertScheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class Tab(val label: String) { HOME("홈"), ANALYSIS("종목 분석"), BEST("추천 BEST 5"), WATCH("관심 종목"), SETTINGS("설정") }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val repo = Repository(prefs)

    // 어떤 예외도 앱을 종료시키지 않도록 최상위 처리
    private val guard = CoroutineExceptionHandler { _, e -> lastError = Http.friendly(e) }
    var lastError by mutableStateOf<String?>(null)

    var tab by mutableStateOf(Tab.HOME)

    // ── 검색 ──
    var query by mutableStateOf("")
        private set
    var searchState by mutableStateOf<UiState<List<StockRef>>>(UiState.Idle)
        private set
    private var searchJob: Job? = null

    fun onQuery(q: String) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) { searchState = UiState.Idle; return }
        // 내장 사전 결과는 즉시 표시, 온라인 검색은 입력이 멈춘 뒤 실행
        val local = StockDirectory.search(q).map { it.toRef() }
        if (local.isNotEmpty()) searchState = UiState.Success(local)
        searchJob = viewModelScope.launch(guard) {
            delay(450)
            if (local.isEmpty()) searchState = UiState.Loading
            searchState = try { UiState.Success(repo.search(q)) } catch (e: Exception) {
                if (local.isNotEmpty()) UiState.Success(local) else UiState.Error(Http.friendly(e))
            }
        }
    }

    fun clearSearch() { query = ""; searchJob?.cancel(); searchState = UiState.Idle }

    // ── 선택 종목 ──
    var selected by mutableStateOf<StockRef?>(null)
        private set
    var quoteState by mutableStateOf<UiState<Quote>>(UiState.Idle)
        private set
    var interval by mutableStateOf(Interval.D1)
        private set
    var chartState by mutableStateOf<UiState<ChartData>>(UiState.Idle)
        private set
    var unsupported by mutableStateOf<Map<Interval, String>>(emptyMap())
        private set
    var overlays by mutableStateOf(Overlays())
    var subPanel by mutableStateOf(SubPanel.VOLUME)
    var analysisState by mutableStateOf<UiState<Repository.FullAnalysis>>(UiState.Idle)
        private set
    var newsState by mutableStateOf<UiState<List<NewsItem>>>(UiState.Idle)
        private set
    var aiState by mutableStateOf<UiState<String>>(UiState.Idle)
        private set
    private var loadJob: Job? = null

    fun open(ref: StockRef) {
        selected = ref
        tab = Tab.ANALYSIS
        clearSearch()
        prefs.addRecent(ref)
        recent = prefs.loadRecent()
        analysisState = UiState.Idle
        aiState = UiState.Idle
        unsupported = emptyMap()
        loadSelected()
    }

    fun openSymbol(symbol: String, name: String?) {
        val ref = StockDirectory.bySymbol(symbol)?.toRef() ?: StockRef(symbol, name ?: symbol, name)
        open(ref)
    }

    fun loadSelected() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(guard) {
            launch { loadQuote() }
            launch { loadChart() }
            launch { loadNews(false) }
        }
    }

    suspend fun loadQuote() {
        val ref = selected ?: return
        quoteState = UiState.Loading
        quoteState = try {
            val q = repo.quote(ref)
            if (q.ref.symbol != ref.symbol || q.ref.name != ref.name) selected = q.ref.copy(nameKr = ref.nameKr ?: q.ref.nameKr)
            UiState.Success(q)
        } catch (e: Exception) { UiState.Error(Http.friendly(e)) }
    }

    fun refreshQuote() { viewModelScope.launch(guard) { silentRefreshQuote() } }

    /** 자동/수동 새로고침: 기존 화면을 지우지 않고 시세만 갱신. 실패 시 이전 값을 '데이터 오류'로 표시 */
    var refreshing by mutableStateOf(false)
        private set

    suspend fun silentRefreshQuote() {
        val ref = selected ?: return
        val prev = (quoteState as? UiState.Success)?.data
        if (prev == null) { loadQuote(); return }
        refreshing = true
        try {
            val q = repo.quote(ref)
            if (selected?.symbol == ref.symbol) quoteState = UiState.Success(q)
        } catch (e: Exception) {
            if (selected?.symbol == ref.symbol) quoteState = UiState.Success(prev.copy(
                freshness = Freshness.ERROR,
                statusText = QuoteLogic.statusText(Freshness.ERROR, prev.session, prev.marketOpen, 0),
                warning = "새로고침 실패: ${Http.friendly(e)} — 표시된 값은 이전에 받은 데이터입니다.",
            ))
        } finally { refreshing = false }
    }

    /** 다음 자동 새로고침까지 대기 시간(ms) */
    fun nextRefreshMs(): Long = (quoteState as? UiState.Success)?.data?.let { QuoteLogic.refreshMs(it) } ?: 30_000L

    /** 분석에 쓴 시세가 현재 시세와 달라졌으면 경고 문구 (오래된 가격으로 매매 판단 방지) */
    fun analysisDrift(): String? {
        val fa = (analysisState as? UiState.Success)?.data ?: return null
        val cur = (quoteState as? UiState.Success)?.data ?: return null
        if (fa.quote.ref.symbol != cur.ref.symbol) return null
        val d = (cur.price / fa.quote.price - 1) * 100
        val ageMin = (System.currentTimeMillis() - fa.quote.fetchedAt) / 60_000
        return when {
            kotlin.math.abs(d) >= 1.0 -> "분석 이후 가격이 ${com.aistock.pro.analysis.Fmt.pct(d)} 변동했습니다. 매수·매도가를 다시 계산하려면 'AI 종합 분석'을 다시 누르세요."
            cur.marketOpen && ageMin >= 30 -> "분석한 지 ${ageMin}분이 지났습니다. 장중이므로 다시 분석하세요."
            else -> null
        }
    }

    fun selectInterval(i: Interval) {
        if (unsupported.containsKey(i)) return
        interval = i
        viewModelScope.launch(guard) { loadChart() }
    }

    suspend fun loadChart() {
        val ref = selected ?: return
        val itv = interval
        chartState = UiState.Loading
        chartState = try {
            val cd = repo.chart(ref.symbol, itv)
            if (cd.bars.size < 2) throw DataException("${itv.label} 데이터가 부족합니다")
            UiState.Success(cd)
        } catch (e: DataException) {
            if (itv != Interval.D1) {
                unsupported = unsupported + (itv to "데이터 공급원이 이 종목의 ${itv.label} 데이터를 제공하지 않습니다")
                interval = Interval.D1
                runCatching { UiState.Success(repo.chart(ref.symbol, Interval.D1)) }.getOrElse { UiState.Error(Http.friendly(it)) }
            } else UiState.Error(Http.friendly(e))
        } catch (e: Exception) { UiState.Error(Http.friendly(e)) }
    }

    fun retryChart() { viewModelScope.launch(guard) { loadChart() } }

    suspend fun loadNews(force: Boolean) {
        val ref = selected ?: return
        newsState = UiState.Loading
        newsState = try { UiState.Success(repo.news(ref, force)) } catch (e: Exception) { UiState.Error(Http.friendly(e)) }
    }

    fun refreshNews() { viewModelScope.launch(guard) { loadNews(true) } }

    fun runAnalysis() {
        val ref = selected ?: return
        if (analysisState is UiState.Loading) return
        analysisState = UiState.Loading
        aiState = UiState.Idle
        viewModelScope.launch(guard) {
            analysisState = try {
                val fa = repo.analyze(ref)
                quoteState = UiState.Success(fa.quote)
                fa.news?.let { newsState = UiState.Success(it) }
                UiState.Success(fa)
            } catch (e: Exception) { UiState.Error(Http.friendly(e)) }
        }
    }

    fun chartLevels(): ChartLevels? {
        val fa = (analysisState as? UiState.Success)?.data ?: return null
        if (fa.quote.ref.symbol != (selected?.symbol ?: "")) return null
        val r = fa.result
        val s = r.strategy
        return ChartLevels(
            supports = r.supports.take(3).map { it.price },
            resistances = r.resistances.take(3).map { it.price },
            buyHigh = s?.buys?.first()?.price, buyLow = s?.buys?.last()?.price,
            sellLow = s?.targets?.first()?.price, sellHigh = s?.targets?.last()?.price,
            stop = s?.stop?.price,
        )
    }

    val externalAiConfigured: Boolean get() = prefs.aiEndpoint.isNotBlank() && prefs.aiKey.isNotBlank() && prefs.aiModel.isNotBlank()

    fun runExternalAi() {
        val fa = (analysisState as? UiState.Success)?.data ?: return
        aiState = UiState.Loading
        viewModelScope.launch(guard) {
            aiState = try {
                UiState.Success(ExternalAi.comment(prefs.aiEndpoint, prefs.aiModel, prefs.aiKey, fa.quote, fa.result))
            } catch (e: Exception) { UiState.Error(Http.friendly(e)) }
        }
    }

    // ── 홈 ──
    var indicesState by mutableStateOf<UiState<List<Repository.IndexQuote>>>(UiState.Idle)
        private set
    var macroState by mutableStateOf<UiState<List<NewsItem>>>(UiState.Idle)
        private set
    var recent by mutableStateOf(prefs.loadRecent())
        private set

    fun loadHome(force: Boolean = false) {
        if (!force && indicesState is UiState.Success) return
        viewModelScope.launch(guard) {
            indicesState = UiState.Loading
            val r = runCatching { repo.indices() }
            indicesState = r.fold({ if (it.isEmpty()) UiState.Error("시장 지수를 불러오지 못했습니다.") else UiState.Success(it) }, { UiState.Error(Http.friendly(it)) })
        }
        viewModelScope.launch(guard) {
            macroState = UiState.Loading
            macroState = try { UiState.Success(repo.macroNews()) } catch (e: Exception) { UiState.Error(Http.friendly(e)) }
        }
    }

    // ── BEST 5 ──
    var bestMarket by mutableStateOf(Market.KR)
    var bestKr by mutableStateOf<UiState<Repository.BestResult>>(UiState.Idle)
        private set
    var bestUs by mutableStateOf<UiState<Repository.BestResult>>(UiState.Idle)
        private set
    var bestProgress by mutableStateOf(0 to 0)
        private set

    fun runBest(market: Market) {
        val cur = if (market == Market.KR) bestKr else bestUs
        if (cur is UiState.Loading) return
        if (market == Market.KR) bestKr = UiState.Loading else bestUs = UiState.Loading
        bestProgress = 0 to 0
        viewModelScope.launch(guard) {
            val res: UiState<Repository.BestResult> = try {
                UiState.Success(repo.best5(market) { d, t -> bestProgress = d to t })
            } catch (e: Exception) { UiState.Error(Http.friendly(e)) }
            if (market == Market.KR) bestKr = res else bestUs = res
        }
    }

    // ── 관심 종목 ──
    var watchlist by mutableStateOf(prefs.loadWatchlist())
        private set
    var watchQuotes by mutableStateOf<Map<String, Quote>>(emptyMap())
        private set
    var watchErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var watchLoading by mutableStateOf(false)
        private set
    var fx by mutableStateOf<Double?>(null)
        private set

    fun isWatched(symbol: String) = watchlist.any { it.symbol == symbol }

    fun toggleWatch(ref: StockRef) {
        if (isWatched(ref.symbol)) removeWatch(ref.symbol) else addWatch(ref)
    }

    fun addWatch(ref: StockRef) {
        if (isWatched(ref.symbol)) return
        watchlist = watchlist + WatchItem(ref.symbol, ref.displayName)
        prefs.saveWatchlist(watchlist)
        refreshWatch()
    }

    fun removeWatch(symbol: String) {
        watchlist = watchlist.filter { it.symbol != symbol }
        prefs.saveWatchlist(watchlist)
    }

    fun updateWatch(item: WatchItem) {
        watchlist = watchlist.map { if (it.symbol == item.symbol) item else it }
        prefs.saveWatchlist(watchlist)
    }

    fun refreshWatch() {
        if (watchLoading) return
        watchLoading = true
        viewModelScope.launch(guard) {
            try {
                val fxDef = async { repo.usdKrw() }
                val sem = Semaphore(4)
                val results = watchlist.map { w ->
                    async {
                        sem.withPermit {
                            w.symbol to runCatching { repo.quote(StockRef(w.symbol, w.name, w.name), withCap = false) }
                        }
                    }
                }.map { it.await() }
                watchQuotes = results.mapNotNull { (s, r) -> r.getOrNull()?.let { s to it } }.toMap()
                watchErrors = results.mapNotNull { (s, r) -> r.exceptionOrNull()?.let { s to Http.friendly(it) } }.toMap()
                fx = fxDef.await() ?: fx
            } finally { watchLoading = false }
        }
    }

    // ── 알림 ──
    var alerts by mutableStateOf(prefs.loadAlerts())
        private set

    fun addAlert(symbol: String, name: String, type: AlertType, value: Double) {
        alerts = alerts + AlertRule(System.currentTimeMillis(), symbol, name, type, value)
        prefs.saveAlerts(alerts)
        AlertScheduler.apply(getApplication<Application>(), prefs)
    }

    fun removeAlert(id: Long) {
        alerts = alerts.filter { it.id != id }
        prefs.saveAlerts(alerts)
        AlertScheduler.apply(getApplication<Application>(), prefs)
    }

    fun toggleAlert(id: Long) {
        alerts = alerts.map { if (it.id == id) it.copy(enabled = !it.enabled, lastFired = 0L) else it }
        prefs.saveAlerts(alerts)
        AlertScheduler.apply(getApplication<Application>(), prefs)
    }

    fun reloadAlerts() { alerts = prefs.loadAlerts() }

    fun checkAlertsNow() { AlertScheduler.runNow(getApplication<Application>()) }

    // ── 설정 ──
    var settingsVersion by mutableStateOf(0)
        private set

    fun saveSettings(alertsOn: Boolean, intervalMin: Int, includeWatch: Boolean) {
        prefs.alertsEnabled = alertsOn
        prefs.alertIntervalMin = intervalMin
        prefs.includeWatchInBest = includeWatch
        AlertScheduler.apply(getApplication<Application>(), prefs)
        settingsVersion++
    }

    fun saveKeys(finnhub: String, dataGoKr: String, aiEndpoint: String, aiModel: String, aiKey: String) {
        prefs.finnhubKey = finnhub
        prefs.dataGoKrKey = dataGoKr
        prefs.aiEndpoint = aiEndpoint
        prefs.aiModel = aiModel
        prefs.aiKey = aiKey
        repo.clearCache()
        settingsVersion++
    }

    fun clearCache() { repo.clearCache(); settingsVersion++ }
}
