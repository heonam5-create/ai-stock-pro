import SwiftUI
import AIStockCore

enum Tab: Hashable { case home, analysis, best, watch, settings }

enum Load<T> {
    case idle, loading, success(T), failure(String)
    var value: T? { if case .success(let v) = self { return v }; return nil }
    var isLoading: Bool { if case .loading = self { return true }; return false }
}

/// 앱 전체 상태 (안드로이드 MainViewModel 과 같은 역할)
@MainActor
final class AppModel: ObservableObject {
    let repo = Repository.shared
    let prefs = Prefs.shared

    @Published var tab: Tab = .home
    @Published var lastError: String?

    // 검색
    @Published var query = ""
    @Published var searchResults: [StockRef] = []
    @Published var searching = false
    private var searchTask: Task<Void, Never>?

    func onQuery(_ q: String) {
        query = q
        searchTask?.cancel()
        let t = q.trimmingCharacters(in: .whitespaces)
        if t.isEmpty { searchResults = []; return }
        // 내장 전체 목록 결과는 즉시 표시 (네트워크 불필요)
        searchResults = StockDirectory.shared.search(t).map(\.ref)
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 400_000_000)
            if Task.isCancelled { return }
            searching = true
            let r = await repo.search(t)
            if !Task.isCancelled && query.trimmingCharacters(in: .whitespaces) == t { searchResults = r }
            searching = false
        }
    }
    func clearSearch() { query = ""; searchResults = []; searchTask?.cancel() }

    // 선택 종목
    @Published var selected: StockRef?
    @Published var quote: Load<Quote> = .idle
    @Published var interval: Interval = .d1
    @Published var chart: Load<ChartData> = .idle
    @Published var unsupported: [Interval: String] = [:]
    @Published var analysis: Load<Repository.FullAnalysis> = .idle
    @Published var news: Load<[NewsItem]> = .idle
    @Published var ai: Load<String> = .idle
    @Published var refreshing = false
    @Published var overlays = ChartOverlays()
    @Published var subPanel: SubPanel = .volume

    func open(_ r: StockRef) {
        let ref = repo.enrich(r)
        selected = ref
        tab = .analysis
        clearSearch()
        prefs.addRecent(ref); recent = prefs.recent
        analysis = .idle; ai = .idle; unsupported = [:]; interval = .d1
        quote = .loading; chart = .loading; news = .loading
        Task { await loadQuote() }
        Task { await loadChart() }
        Task { await loadNews(force: false) }
    }

    func loadQuote() async {
        guard let ref = selected else { return }
        do {
            let q = try await repo.quote(ref)
            if selected?.symbol == ref.symbol { quote = .success(q) }
        } catch { if selected?.symbol == ref.symbol { quote = .failure(Http.friendly(error)) } }
    }

    /// 자동/수동 새로고침: 화면을 지우지 않고 시세만 갱신. 실패해도 이전 값을 '데이터 오류'로 표시 (최신가처럼 보이지 않게)
    func refreshQuote() async {
        guard let ref = selected else { return }
        guard case .success(let prev) = quote else { await loadQuote(); return }
        refreshing = true
        defer { refreshing = false }
        do {
            let q = try await repo.quote(ref)
            if selected?.symbol == ref.symbol { quote = .success(q) }
        } catch {
            var p = prev
            p.freshness = .error
            p.statusText = QuoteLogic.statusText(.error, p.session, open: p.marketOpen, ageMin: 0)
            p.warning = "새로고침 실패: \(Http.friendly(error)) — 표시된 값은 이전에 받은 데이터입니다."
            if selected?.symbol == ref.symbol { quote = .success(p) }
        }
    }

    var nextRefreshSeconds: Double { quote.value.map { QuoteLogic.refreshSeconds($0) } ?? 30 }

    func selectInterval(_ i: Interval) {
        if unsupported[i] != nil { return }
        interval = i
        Task { await loadChart() }
    }

    func loadChart() async {
        guard let ref = selected else { return }
        let itv = interval
        chart = .loading
        do {
            let cd = try await repo.chart(ref, itv)
            if selected?.symbol == ref.symbol && interval == itv { chart = .success(cd) }
        } catch {
            if itv != .d1 {
                unsupported[itv] = "데이터 공급원이 이 종목의 \(itv.label) 데이터를 제공하지 않습니다"
                interval = .d1
                await loadChart()
            } else { chart = .failure(Http.friendly(error)) }
        }
    }

    func loadNews(force: Bool) async {
        guard let ref = selected else { return }
        do { let n = try await repo.news(ref, force: force); if selected?.symbol == ref.symbol { news = .success(n) } }
        catch { if selected?.symbol == ref.symbol { news = .failure(Http.friendly(error)) } }
    }

    func runAnalysis() {
        guard let ref = selected, !analysis.isLoading else { return }
        analysis = .loading; ai = .idle
        Task {
            do {
                let fa = try await repo.analyze(ref)
                guard selected?.symbol == ref.symbol else { return }
                quote = .success(fa.quote)
                if let n = fa.news { news = .success(n) }
                analysis = .success(fa)
            } catch { analysis = .failure(Http.friendly(error)) }
        }
    }

    /// 분석에 쓴 시세가 현재와 달라졌으면 경고 (오래된 가격으로 매매 판단 방지)
    var analysisDrift: String? {
        guard let fa = analysis.value, let cur = quote.value, fa.quote.ref.symbol == cur.ref.symbol else { return nil }
        let d = (cur.price / fa.quote.price - 1) * 100
        let age = Date().timeIntervalSince(fa.quote.fetchedAt) / 60
        if abs(d) >= 1 { return "분석 이후 가격이 \(Fmt.pct(d)) 변동했습니다. 매수·매도가를 다시 계산하려면 'AI 종합 분석'을 다시 누르세요." }
        if cur.marketOpen && age >= 30 { return "분석한 지 \(Int(age))분이 지났습니다. 장중이므로 다시 분석하세요." }
        return nil
    }

    var chartLevels: ChartLevels? {
        guard let fa = analysis.value, fa.quote.ref.symbol == selected?.symbol, !fa.quote.unreliable else { return nil }
        let r = fa.result, s = r.strategy
        return ChartLevels(supports: r.supports.prefix(3).map(\.price), resistances: r.resistances.prefix(3).map(\.price),
                           buyHigh: s?.buys.first?.price, buyLow: s?.buys.last?.price,
                           sellLow: s?.targets.first?.price, sellHigh: s?.targets.last?.price, stop: s?.stop.price)
    }

    func runExternalAi() {
        guard let fa = analysis.value else { return }
        ai = .loading
        let ep = prefs.aiEndpoint, model = prefs.aiModel, key = prefs.aiKey
        Task {
            do { ai = .success(try await ExternalAi.comment(endpoint: ep, model: model, key: key, quote: fa.quote, result: fa.result)) }
            catch { ai = .failure(Http.friendly(error)) }
        }
    }

    // 홈
    @Published var indices: Load<[Repository.IndexQuote]> = .idle
    @Published var macro: Load<[NewsItem]> = .idle
    @Published var recent: [StockRef] = Prefs.shared.recent

    func loadHome(force: Bool = false) {
        if !force, indices.value != nil { return }
        indices = .loading; macro = .loading
        Task {
            let r = await repo.indices()
            indices = r.isEmpty ? .failure("시장 지수를 불러오지 못했습니다.") : .success(r)
        }
        Task { macro = .success(await repo.macroNews()) }
    }

    // BEST 5
    @Published var bestMarket: Market = .KR
    @Published var bestKr: Load<Repository.BestResult> = .idle
    @Published var bestUs: Load<Repository.BestResult> = .idle
    @Published var bestProgress: (Int, Int, String) = (0, 0, "")

    func runBest(_ m: Market) {
        if (m == .KR ? bestKr : bestUs).isLoading { return }
        if m == .KR { bestKr = .loading } else { bestUs = .loading }
        bestProgress = (0, 0, "")
        Task {
            let res: Load<Repository.BestResult>
            do {
                res = .success(try await repo.best5(m) { d, t, msg in Task { @MainActor in self.bestProgress = (d, t, msg) } })
            } catch { res = .failure(Http.friendly(error)) }
            if m == .KR { bestKr = res } else { bestUs = res }
        }
    }

    // 관심 종목
    @Published var watchlist: [WatchItem] = Prefs.shared.watchlist
    @Published var watchQuotes: [String: Quote] = [:]
    @Published var watchErrors: [String: String] = [:]
    @Published var watchLoading = false
    @Published var fx: Double?

    func isWatched(_ s: String) -> Bool { watchlist.contains { $0.symbol == s } }
    func toggleWatch(_ r: StockRef) { isWatched(r.symbol) ? removeWatch(r.symbol) : addWatch(r) }
    func addWatch(_ r: StockRef) {
        if isWatched(r.symbol) { return }
        watchlist.append(WatchItem(symbol: r.symbol, name: r.displayName, naverCode: r.naverCode, yahoo: r.yahooSymbol))
        prefs.watchlist = watchlist
        refreshWatch()
    }
    func removeWatch(_ s: String) { watchlist.removeAll { $0.symbol == s }; prefs.watchlist = watchlist }
    func updateWatch(_ w: WatchItem) { if let i = watchlist.firstIndex(where: { $0.symbol == w.symbol }) { watchlist[i] = w; prefs.watchlist = watchlist } }

    func refreshWatch() {
        if watchLoading { return }
        watchLoading = true
        let list = watchlist
        Task {
            async let fxT = repo.usdKrw()
            var qs: [String: Quote] = [:], es: [String: String] = [:]
            await withTaskGroup(of: (String, Result<Quote, Error>).self) { g in
                for w in list { g.addTask { do { return (w.symbol, .success(try await Repository.shared.quote(w.ref))) } catch { return (w.symbol, .failure(error)) } } }
                for await (s, r) in g {
                    switch r { case .success(let q): qs[s] = q; case .failure(let e): es[s] = Http.friendly(e) }
                }
            }
            watchQuotes = qs; watchErrors = es
            if let f = await fxT { fx = f.0 }
            watchLoading = false
        }
    }

    // 알림
    @Published var alerts: [AlertRule] = Prefs.shared.alerts
    func addAlert(_ symbol: String, _ name: String, _ type: AlertType, _ value: Double) {
        alerts.append(AlertRule(symbol: symbol, name: name, type: type, value: value))
        prefs.alerts = alerts
        AlertMonitor.requestPermission()
        AlertMonitor.schedule()
    }
    func removeAlert(_ id: Int64) { alerts.removeAll { $0.id == id }; prefs.alerts = alerts }
    func toggleAlert(_ id: Int64) {
        if let i = alerts.firstIndex(where: { $0.id == id }) { alerts[i].enabled.toggle(); alerts[i].lastFired = .distantPast; prefs.alerts = alerts }
    }
    func reloadAlerts() { alerts = prefs.alerts }
}
