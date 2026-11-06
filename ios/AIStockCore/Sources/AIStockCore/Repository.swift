import Foundation

/// 모든 데이터 조회의 단일 진입점 (캐시·교차검증·대체 공급원)
public actor Repository {
    public static let shared = Repository()
    let prefs: Prefs
    let dir: StockDirectory
    public init(prefs: Prefs = .shared, directory: StockDirectory = .shared) { self.prefs = prefs; self.dir = directory }

    var chartCache: [String: ChartData] = [:]
    var quoteCache: [String: Quote] = [:]
    var newsCache: [String: (Date, [NewsItem])] = [:]
    var naverCodes: [String: String] = [:]          // 미국 티커 → 확인된 네이버 코드

    public func clearCache() { chartCache = [:]; newsCache = [:] }

    /// 목록 정보로 참조 보강 (네이버 코드·Yahoo 코드·한글명)
    public nonisolated func enrich(_ ref: StockRef) -> StockRef {
        guard let e = dir.bySymbol(ref.symbol) else { return ref }
        var r = ref
        if r.naverCode == nil { r.naverCode = e.naverCode }
        if r.yahooSymbol == nil { r.yahooSymbol = e.yahoo }
        if r.nameKr == nil || r.nameKr?.isEmpty == true { r.nameKr = e.nameKr.isEmpty ? nil : e.nameKr }
        if r.exchange.isEmpty { r.exchange = e.exchange }
        if r.name == r.symbol { r.name = e.nameEn.isEmpty ? e.nameKr : e.nameEn }
        return r
    }

    // MARK: 차트

    /// 일·주·월봉: 네이버 우선 → Yahoo 대체. 분봉: 한국 네이버 → Yahoo, 미국 Yahoo
    public func chart(_ ref0: StockRef, _ interval: Interval, maxAge: TimeInterval? = nil) async throws -> ChartData {
        let ref = enrich(ref0)
        let key = "\(ref.symbol)|\(interval.rawValue)"
        let ttl = maxAge ?? (interval.intraday ? 60 : 120)
        if let c = chartCache[key], Date().timeIntervalSince(c.fetchedAt) < ttl { return c }
        let kr = ref.market == .KR
        let tz = kr ? "Asia/Seoul" : "America/New_York"
        var errs: [String] = []
        let naverFirst = !interval.intraday || kr
        if naverFirst {
            do {
                let bars = try await NaverSource.candles(ref, naverCode: naverCodes[ref.symbol] ?? ref.naverCode, interval: interval)
                if bars.count >= 2 {
                    let d = ChartData(meta: ChartMeta(symbol: ref.symbol, currency: kr ? "KRW" : "USD", timezone: tz), bars: bars, interval: interval, source: NaverSource.name)
                    chartCache[key] = d; return d
                }
                errs.append("네이버: 데이터 부족")
            } catch { errs.append("네이버: \(Http.friendly(error))") }
        }
        do {
            let d = try await YahooSource.chart(ref.yahoo, interval)
            if d.bars.count >= 2 { chartCache[key] = d; return d }
            errs.append("Yahoo: 데이터 부족")
        } catch { errs.append("Yahoo: \(Http.friendly(error))") }
        throw DataError("\(ref.displayName) \(interval.label) 차트를 불러오지 못했습니다 (\(errs.joined(separator: " / ")))")
    }

    // MARK: 시세

    /**
     현재가 스냅샷 (분석·손익·추천·알림 공용 단일 기준)
     1) 네이버 금융 (정규장 + 프리/애프터·NXT 별도)  2) Yahoo·Finnhub 교차검증
     3) 네이버 실패 시 Yahoo 대체 ("지연 가능 / 대체 데이터 사용 중")  4) 모두 실패 시 마지막 성공값을 '데이터 오류'로 (임의 가격 생성 금지)
     */
    public func quote(_ ref0: StockRef) async throws -> Quote {
        let ref = enrich(ref0)
        let kr = ref.market == .KR
        let fkey = prefs.finnhubKey
        let useFh = !kr && !fkey.isEmpty
        async let nvT = capture { try await NaverSource.quote(ref) }
        async let yT = capture { try await YahooSource.meta(ref.yahoo) }
        async let fT = capture { useFh ? try await FinnhubSource.quote(key: fkey, symbol: ref.symbol) : nil }
        let nv = await nvT, ym = try? (await yT).get(), fh = (try? (await fT).get()) ?? nil
        let now = Int64(Date().timeIntervalSince1970)

        var q: Quote
        switch nv {
        case .success(let (s, code)):
            if !kr { naverCodes[ref.symbol] = code }
            var r2 = ref; r2.naverCode = code
            q = fromNaver(r2, s, yahoo: ym, finnhub: fh, now: now)
            if q.marketCap == nil { q.marketCap = await marketCap(r2) ; q.marketCapSource = q.marketCap != nil ? (kr ? NaverSource.name : "Finnhub") : nil }
            else { q.marketCapSource = NaverSource.name }
        case .failure(let nerr):
            if let m = ym, let p = m.price, p > 0 {
                q = fromYahoo(ref, m, finnhub: fh, now: now, naverError: Http.friendly(nerr))
            } else if let old = quoteCache[ref.symbol] {
                var o = old
                o.freshness = .error
                o.statusText = QuoteLogic.statusText(.error, o.session, open: o.marketOpen, ageMin: 0)
                o.warning = "최신 시세를 받지 못했습니다 (\(Http.friendly(nerr))). 표시된 값은 \(MarketClock.formatKstSec(old.fetchedAt)) 에 받은 데이터입니다."
                return o
            } else {
                throw DataError("시세를 불러오지 못했습니다 — 네이버: \(Http.friendly(nerr)) / Yahoo: 응답 없음. 임의의 가격은 표시하지 않습니다.")
            }
        }
        // 52주 고저: 일봉에서 계산 (공급원 메타가 있으면 우선)
        if q.high52 == nil || q.low52 == nil, let cd = try? await chart(ref, .d1) {
            let last = cd.bars.suffix(252)
            q.high52 = q.high52 ?? last.map(\.h).max(); q.low52 = q.low52 ?? last.map(\.l).min()
        }
        quoteCache[ref.symbol] = q
        return q
    }

    func fromNaver(_ ref: StockRef, _ s: NaverSource.Snap, yahoo: ChartMeta?, finnhub: (Double, Int64)?, now: Int64) -> Quote {
        let kr = ref.market == .KR
        // 네이버는 NXT·시간외가 열려 있으면 정규장 마감 후에도 OPEN 을 줄 수 있음 → 정규장 시간표와 함께 판단 (2026-11-06 실측)
        let open = QuoteLogic.regularOpen(naverStatusOpen: s.isOpen, market: ref.market, at: Date(timeIntervalSince1970: TimeInterval(now)))
        let fr = QuoteLogic.freshness(open: open, tradedAt: s.tradedAt, nowSec: now, delayMin: s.delayMin, sourceRealtime: true)
        let session: Session = open ? .regular : .closed
        var checks = [QuoteLogic.crossCheck(primaryPrice: s.price, primaryTime: s.tradedAt, primaryOpen: open, source: YahooSource.name,
                                            otherPrice: yahoo?.price, otherTime: (yahoo?.marketTime ?? 0) > 0 ? yahoo?.marketTime : nil)]
        if let f = finnhub { checks.append(QuoteLogic.crossCheck(primaryPrice: s.price, primaryTime: s.tradedAt, primaryOpen: open, source: "Finnhub", otherPrice: f.0, otherTime: f.1)) }
        let check = checks.first { $0.mismatch } ?? checks.first { $0.diffPct != nil && !$0.message.contains("생략") } ?? checks[0]
        var warning: String?
        if fr == .stale { warning = "시세가 오래되었습니다 (체결 기준 \(MarketClock.formatKst(s.tradedAt))). 새로고침 후 다시 확인하세요." }
        else if check.mismatch { warning = check.message }
        else if let e = s.ext, !open, abs(e.changePct ?? 0) >= 2 { warning = "\(e.label) 가격이 정규장 종가 대비 \(Fmt.pct(e.changePct)) 차이 — 분석·매매가는 정규장 종가 기준입니다." }
        var r = ref
        if r.nameKr == nil, let n = s.name { r.nameKr = n }
        return Quote(ref: r, currency: s.currency, price: s.price, prevClose: s.prevClose, change: s.change, changePct: s.changePct,
                     volume: s.volume, dayHigh: s.high, dayLow: s.low, high52: yahoo?.high52, low52: yahoo?.low52,
                     marketCap: s.marketCap, marketCapSource: nil, marketTime: s.tradedAt, timezone: s.zone, marketOpen: open,
                     statusText: QuoteLogic.statusText(fr, session, open: open, ageMin: (now - s.tradedAt) / 60),
                     session: session, freshness: fr, source: NaverSource.name,
                     delayText: QuoteLogic.delayText(NaverSource.name, s.delayMin, kr: kr), ext: s.ext, check: check,
                     fetchedAt: Date(), warning: warning, fallback: false)
    }

    func fromYahoo(_ ref: StockRef, _ m: ChartMeta, finnhub: (Double, Int64)?, now: Int64, naverError: String) -> Quote {
        let kr = ref.market == .KR
        let open = MarketClock.isOpen(ref.market)
        let p = m.price ?? 0
        let fr = QuoteLogic.freshness(open: open, tradedAt: m.marketTime, nowSec: now, delayMin: nil, sourceRealtime: false)
        let session: Session = open ? .regular : .closed
        let check = finnhub.map { QuoteLogic.crossCheck(primaryPrice: p, primaryTime: m.marketTime, primaryOpen: open, source: "Finnhub", otherPrice: $0.0, otherTime: $0.1) }
            ?? CrossCheck(source: NaverSource.name, price: nil, time: nil, diffPct: nil, mismatch: false, message: "기본 공급원(네이버) 실패: \(naverError) — 교차검증 불가")
        return Quote(ref: ref, currency: m.currency, price: p, prevClose: nil, change: nil, changePct: nil, volume: m.volume,
                     dayHigh: m.dayHigh, dayLow: m.dayLow, high52: m.high52, low52: m.low52, marketCap: nil, marketCapSource: nil,
                     marketTime: m.marketTime, timezone: m.timezone, marketOpen: open,
                     statusText: QuoteLogic.statusText(fr, session, open: open, ageMin: (now - m.marketTime) / 60),
                     session: session, freshness: fr, source: YahooSource.name,
                     delayText: QuoteLogic.delayText(YahooSource.name, nil, kr: kr, fallback: true), ext: nil, check: check,
                     fetchedAt: Date(), warning: check.mismatch ? check.message : "지연 가능 / 대체 데이터 사용 중 — 기본 공급원(네이버) 조회 실패", fallback: true)
            .withPrev(bars: chartCache["\(ref.symbol)|d1"]?.bars)
    }

    func marketCap(_ ref: StockRef) async -> Double? {
        if ref.market == .KR {
            guard let d = try? await Http.get("https://m.stock.naver.com/api/stock/\(ref.code)/integration", retries: 0), let j = try? J.parse(d) else { return nil }
            for it in j["totalInfos"].array where it["code"].string == "marketValue" { return NaverSource.koreanAmount(it["value"].string) }
            return nil
        }
        let k = prefs.finnhubKey
        return k.isEmpty ? nil : try? await FinnhubSource.marketCapUsd(key: k, symbol: ref.symbol)
    }

    /// 시세 스냅샷과 같은 기준으로 정렬된 일봉 (분석용)
    public func alignedDaily(_ q: Quote, maxAge: TimeInterval = 120) async throws -> [Bar] {
        let cd = try await chart(q.ref, .d1, maxAge: maxAge)
        let tz = TimeZone(identifier: q.timezone) ?? MarketClock.seoul
        return QuoteLogic.alignDaily(cd.bars, price: q.price, time: q.marketTime, zone: tz, high: q.dayHigh, low: q.dayLow, volume: q.volume)
    }

    // MARK: 검색

    /// 내장 전체 목록 검색 (오프라인) + 네이버 자동완성 보강 (온라인, 실패해도 무시)
    public func search(_ query: String, online: Bool = true) async -> [StockRef] {
        var out: [StockRef] = dir.search(query).map(\.ref)
        if online, out.count < 8, query.trimmingCharacters(in: .whitespaces).count >= 1,
           let ac = try? await NaverSource.autocomplete(query) {
            for a in ac {
                let ref: StockRef?
                if a.nation == "KOR" {
                    ref = dir.byKrCode(a.code)?.ref ?? StockRef(symbol: "\(a.code).\(a.market == "KOSDAQ" ? "KQ" : "KS")", name: a.name, nameKr: a.name,
                                                                 exchange: a.market == "KOSDAQ" ? "코스닥" : "코스피", market: .KR)
                } else if a.nation == "USA" {
                    let t = a.code.replacingOccurrences(of: " ", with: ".")
                    ref = dir.bySymbol(t)?.ref ?? StockRef(symbol: t, name: a.name, nameKr: a.name, exchange: a.market, market: .US,
                                                           naverCode: a.naverCode, yahooSymbol: t.replacingOccurrences(of: ".", with: "-"))
                } else { ref = nil }
                if let r = ref, !out.contains(where: { $0.symbol == r.symbol }) { out.append(r) }
            }
        }
        return Array(out.prefix(30))
    }

    // MARK: 뉴스·일정

    public func news(_ ref0: StockRef, force: Bool = false) async throws -> [NewsItem] {
        let ref = enrich(ref0)
        if !force, let c = newsCache[ref.symbol], Date().timeIntervalSince(c.0) < 600 { return c.1 }
        var all: [NewsItem] = []
        var errs: [Error] = []
        do { all += try await NaverSource.news(ref, naverCode: naverCodes[ref.symbol] ?? ref.naverCode) } catch { errs.append(error) }
        do { all += try await NewsSource.forStock(ref) } catch { errs.append(error) }
        if all.isEmpty, let e = errs.first, errs.count >= 2 { throw e }
        let r = NewsSource.finish(all)
        newsCache[ref.symbol] = (Date(), r)
        return r
    }

    public func macroNews() async -> [NewsItem] {
        if let c = newsCache["__macro"], Date().timeIntervalSince(c.0) < 900 { return c.1 }
        let r = await NewsSource.macro()
        newsCache["__macro"] = (Date(), r)
        return r
    }

    public func events(_ ref: StockRef) async -> [EventItem] {
        var list = Events.upcomingMacro()
        let k = prefs.finnhubKey
        if ref.market == .US, !k.isEmpty, let e = try? await FinnhubSource.earnings(key: k, symbol: ref.symbol) { list += e }
        return list.sorted { $0.daysLeft < $1.daysLeft }
    }

    // MARK: 분석

    public struct FullAnalysis: Sendable {
        public let quote: Quote, result: AnalysisResult, news: [NewsItem]?, events: [EventItem], newsError: String?, barsSource: String
    }

    /// AI 종합 분석: 항상 같은 시세 스냅샷 + 그 스냅샷에 맞춘 일봉
    public func analyze(_ ref0: StockRef) async throws -> FullAnalysis {
        let ref = enrich(ref0)
        let q = try await quote(ref)
        var newsErr: String?
        var news: [NewsItem]?
        do { news = try await self.news(q.ref) } catch { newsErr = Http.friendly(error) }
        let ev = await events(q.ref)
        let cd = try await chart(q.ref, .d1)
        let tz = TimeZone(identifier: q.timezone) ?? MarketClock.seoul
        let bars = QuoteLogic.alignDaily(cd.bars, price: q.price, time: q.marketTime, zone: tz, high: q.dayHigh, low: q.dayLow, volume: q.volume)
        let r = AnalysisEngine.analyze(AnalysisInput(bars: bars, price: q.price, isKr: q.ref.market == .KR, news: news, events: ev))
        return FullAnalysis(quote: q, result: r, news: news, events: ev, newsError: newsErr, barsSource: cd.source)
    }

    // MARK: BEST 5

    public struct BestResult: Sendable {
        public let picks: [Recommendation]
        public let candidates: Int          // 후보군(시가총액 상위 목록) 수
        public let liquid: Int              // 거래대금·가격 필터 통과
        public let analyzed: Int            // 실제 일봉 분석 완료
        public let failed: [String]
        public let excluded: Int            // 매수 금지·분할매도·분석 불가
        public let staleExcluded: Int
        public let scope: String
        public let at: Int64
    }

    /**
     효율적 BEST 5:
     1) 네이버 시가총액 순위로 후보군 확보 (한국 코스피 200+코스닥 100, 미국 나스닥 200+NYSE 200 — 요청 수 4~6회)
     2) 거래대금·주가 필터 (한국 50억원/일·1,000원 이상, 미국 1억달러/일·5달러 이상, 스팩·ETF 제외)
     3) 거래대금 상위 N개(기본 80)만 일봉 2년 분석 → 4) 상위 10개 뉴스 반영 재분석 → 5) 종합 점수 상위 5개
     분석하지 않은 종목은 평가했다고 표시하지 않음 (화면에 각 단계 수치를 그대로 표시)
     */
    public func best5(_ market: Market, deep: Int = 80, progress: @Sendable @escaping (Int, Int, String) -> Void) async throws -> BestResult {
        let kr = market == .KR
        progress(0, 1, "후보군 수집 중 (시가총액 순위)")
        var cands: [NaverSource.RankItem] = []
        let plan: [(String, Int)] = kr ? [("KOSPI", 1), ("KOSPI", 2), ("KOSDAQ", 1)] : [("NASDAQ", 1), ("NASDAQ", 2), ("NYSE", 1), ("NYSE", 2)]
        var rankErr: String?
        for (m, pg) in plan {
            do { cands += try await NaverSource.ranking(m, page: pg, pageSize: 100) } catch { rankErr = Http.friendly(error) }
            try await Task.sleep(nanoseconds: 200_000_000)
        }
        if cands.isEmpty {   // 순위 조회 실패 → 내장 목록 시가총액 상위로 대체
            cands = dir.topByRank(market, 200).map { NaverSource.RankItem(ref: $0.ref, price: 0, changePct: nil, tradingValue: nil, marketCap: nil, tradedAt: nil) }
        }
        var seen = Set<String>()
        cands = cands.filter { seen.insert($0.ref.symbol).inserted }
        let liquidMin = kr ? 5e9 : 1e8, priceMin = kr ? 1000.0 : 5.0
        var liquid = cands.filter { c in
            let n = c.ref.displayName
            if n.contains("스팩") || n.uppercased().contains("ACQUISITION") || n.uppercased().contains(" ETF") { return false }
            guard let tv = c.tradingValue else { return true }   // 거래대금 정보 없으면 통과(후속 분석에서 판단)
            return tv >= liquidMin && c.price >= priceMin
        }.sorted { ($0.tradingValue ?? 0) > ($1.tradingValue ?? 0) }
        let liquidCount = liquid.count
        liquid = Array(liquid.prefix(deep))
        if prefs.includeWatchInBest {
            for w in prefs.watchlist where w.market == market && !liquid.contains(where: { $0.ref.symbol == w.symbol }) {
                liquid.append(NaverSource.RankItem(ref: enrich(w.ref), price: 0, changePct: nil, tradingValue: nil, marketCap: nil, tradedAt: nil))
            }
        }

        let total = liquid.count
        var done = 0
        var failed: [String] = []
        var stale = 0
        var first: [(Quote, AnalysisResult, [Bar])] = []
        // 동시 3개씩 (무료 공급원 호출 제한 고려)
        for chunk in stride(from: 0, to: liquid.count, by: 3).map({ Array(liquid[$0..<min($0 + 3, liquid.count)]) }) {
            await withTaskGroup(of: (String, Result<(Quote, AnalysisResult, [Bar]), Error>).self) { g in
                for c in chunk {
                    g.addTask {
                        do { return (c.ref.displayName, .success(try await self.analyzeLight(c))) }
                        catch { return (c.ref.displayName, .failure(error)) }
                    }
                }
                for await (name, r) in g {
                    done += 1
                    switch r {
                    case .success(let v): if v.0.unreliable { stale += 1 } else { first.append(v) }
                    case .failure: failed.append(name)
                    }
                }
            }
            progress(done, total, "일봉 분석 중 \(done)/\(total)")
            try await Task.sleep(nanoseconds: 150_000_000)
        }
        let prelim = first.compactMap { (q, r, b) in Recommender.score(q.ref, r, dataTime: q.marketTime).map { (q, $0, b) } }
            .sorted { $0.1.total > $1.1.total }.prefix(10)
        progress(total, total, "상위 \(prelim.count)개 뉴스 반영 재분석 중")
        var finals: [Recommendation] = []
        for (q, _, bars) in prelim {
            let n = try? await news(q.ref)
            let ev = await events(q.ref)
            let r = AnalysisEngine.analyze(AnalysisInput(bars: bars, price: q.price, isKr: kr, news: n, events: ev))
            if let s = Recommender.score(q.ref, r, dataTime: q.marketTime) { finals.append(s) }
        }
        let excluded = first.count - first.filter { Recommender.score($0.0.ref, $0.1, dataTime: 0) != nil }.count
        let scope = kr ? "코스피 시총 상위 200 + 코스닥 시총 상위 100" : "나스닥 시총 상위 200 + NYSE 시총 상위 200"
        _ = rankErr
        return BestResult(picks: Recommender.rank(finals), candidates: cands.count, liquid: liquidCount, analyzed: first.count + stale,
                          failed: failed, excluded: excluded, staleExcluded: stale, scope: scope, at: Int64(Date().timeIntervalSince1970))
    }

    /// BEST5 1단계: 순위 목록의 네이버 시세(같은 공급원) 또는 개별 조회 + 일봉 분석 (뉴스 제외)
    func analyzeLight(_ c: NaverSource.RankItem) async throws -> (Quote, AnalysisResult, [Bar]) {
        let q = try await quote(c.ref)
        let bars = try await alignedDaily(q, maxAge: 600)
        let r = AnalysisEngine.analyze(AnalysisInput(bars: bars, price: q.price, isKr: q.ref.market == .KR, news: nil, events: Events.upcomingMacro()))
        return (q, r, bars)
    }

    // MARK: 홈 지수·환율

    public struct IndexQuote: Sendable, Identifiable {
        public var id: String { name }
        public let name: String, price: Double, changePct: Double?, time: Int64, kr: Bool, source: String
    }

    public func indices() async -> [IndexQuote] {
        var out: [IndexQuote] = []
        for (code, name, dom) in [("KOSPI", "코스피", true), ("KOSDAQ", "코스닥", true), (".INX", "S&P 500", false), (".IXIC", "나스닥", false)] {
            if let s = try? await NaverSource.index(code, domestic: dom) {
                out.append(IndexQuote(name: name, price: s.price, changePct: s.changePct, time: s.tradedAt, kr: dom, source: NaverSource.name))
            }
        }
        if let fx = await usdKrw() { out.append(IndexQuote(name: "원/달러", price: fx.0, changePct: nil, time: fx.1, kr: true, source: fx.2)) }
        return out
    }

    /// 원/달러 환율 (Yahoo KRW=X, 실패 시 네이버)
    public func usdKrw() async -> (Double, Int64, String)? {
        if let m = try? await YahooSource.meta("KRW=X"), let p = m.price, p > 0 { return (p, m.marketTime, YahooSource.name) }
        if let d = try? await Http.get("https://m.stock.naver.com/front-api/marketIndex/prices?category=exchange&reutersCode=FX_USDKRW&page=1&pageSize=10", retries: 0),
           let j = try? J.parse(d) {
            let first = j["result"][0].isNull ? j[0] : j["result"][0]
            if let p = first["closePrice"].double, p > 0 { return (p, Int64(Date().timeIntervalSince1970), NaverSource.name) }
        }
        return nil
    }
}

/// async 작업 결과를 Result 로 (병렬 조회 후 개별 실패 처리)
func capture<T: Sendable>(_ f: @Sendable () async throws -> T) async -> Result<T, Error> {
    do { return .success(try await f()) } catch { return .failure(error) }
}

extension Quote {
    /// Yahoo 대체 시세의 전일 종가·등락률을 일봉으로 계산
    func withPrev(bars: [Bar]?) -> Quote {
        guard let b = bars, b.count >= 2 else { return self }
        var q = self
        let tz = TimeZone(identifier: timezone) ?? MarketClock.seoul
        let sameDay = MarketClock.day(b.last!.t, tz) == MarketClock.day(marketTime, tz)
        let prev = sameDay ? b[b.count - 2].c : b.last!.c
        q.prevClose = prev; q.change = price - prev; q.changePct = (price / prev - 1) * 100
        return q
    }
}
