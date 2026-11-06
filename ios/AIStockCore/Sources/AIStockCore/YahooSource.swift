import Foundation

/// Yahoo Finance 차트 (API 키 불필요, 비공식). 보조 공급원: 교차검증·미국 분봉·네이버 실패 시 대체
public enum YahooSource {
    public static let name = "Yahoo Finance"
    static let hosts = ["https://query1.finance.yahoo.com", "https://query2.finance.yahoo.com"]

    public static func chart(_ symbol: String, _ interval: Interval) async throws -> ChartData {
        var last: Error?
        let enc = symbol.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? symbol
        for h in hosts {
            do {
                let url = "\(h)/v8/finance/chart/\(enc)?interval=\(interval.yahoo)&range=\(interval.range)&includePrePost=false&events=div%2Csplits"
                return try parse(try await Http.get(url, retries: 1), symbol: symbol, interval: interval)
            } catch let e as Http.HttpError where e.code == 404 {
                throw DataError("'\(symbol)' 종목 데이터를 찾을 수 없습니다.")
            } catch let e as DataError { throw e } catch { last = error }
        }
        throw last ?? DataError("차트 데이터를 불러오지 못했습니다.")
    }

    public static func parse(_ data: Data, symbol: String, interval: Interval) throws -> ChartData {
        let root = try J.parse(data)["chart"]
        let r = root["result"][0]
        guard !r.isNull else { throw DataError("'\(symbol)' 데이터가 없습니다. \(root["error"]["description"].string?.prefix(80) ?? "")") }
        let m = r["meta"]
        let kr = symbol.hasSuffix(".KS") || symbol.hasSuffix(".KQ")
        let meta = ChartMeta(symbol: m["symbol"].string ?? symbol, currency: m["currency"].string ?? (kr ? "KRW" : "USD"),
                             exchange: m["fullExchangeName"].string ?? m["exchangeName"].string ?? "",
                             longName: m["longName"].string ?? m["shortName"].string,
                             price: m["regularMarketPrice"].double, marketTime: m["regularMarketTime"].int64 ?? 0,
                             timezone: m["exchangeTimezoneName"].string ?? "UTC",
                             high52: m["fiftyTwoWeekHigh"].double, low52: m["fiftyTwoWeekLow"].double,
                             dayHigh: m["regularMarketDayHigh"].double, dayLow: m["regularMarketDayLow"].double, volume: m["regularMarketVolume"].double)
        let ts = r["timestamp"].array
        let q = r["indicators"]["quote"][0]
        var bars: [Bar] = []
        for i in ts.indices {
            guard let t = ts[i].int64, let c = q["close"][i].double, c > 0 else { continue }
            let o = q["open"][i].double ?? c
            let h = q["high"][i].double ?? max(o, c), l = q["low"][i].double ?? min(o, c)
            bars.append(Bar(t: t, o: o, h: max(h, o, c), l: min(l, o, c), c: c, v: q["volume"][i].double ?? 0))
        }
        return ChartData(meta: meta, bars: bars, interval: interval, source: name)
    }
}

/// Google 뉴스 RSS (무료, 키 불필요) — 최근 7일 기사만
public enum NewsSource {
    static let maxAge: Int64 = 7 * 86400

    static func url(_ q: String, korean: Bool) -> String {
        let enc = "\(q) when:7d".addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? q
        return korean ? "https://news.google.com/rss/search?q=\(enc)&hl=ko&gl=KR&ceid=KR:ko"
                      : "https://news.google.com/rss/search?q=\(enc)&hl=en-US&gl=US&ceid=US:en"
    }

    public static func forStock(_ ref: StockRef) async throws -> [NewsItem] {
        var feeds: [(String, Bool)] = []
        let kr = ref.nameKr
        if ref.market == .KR {
            feeds = [("\"\(kr ?? ref.name)\" 주가", true), ("\"\(kr ?? ref.name)\"", true)]
        } else {
            let en = ref.name.replacingOccurrences(of: "(?i),? (inc\\.?|corporation|corp\\.?|ltd\\.?|holdings?|plc|co\\.)$", with: "", options: .regularExpression)
                .trimmingCharacters(in: .whitespaces)
            feeds = [("\"\(en)\" OR \(ref.symbol) stock", false)]
            if let k = kr, !k.isEmpty { feeds.append(("\"\(k)\" 주가", true)) }
        }
        var all: [NewsItem] = []
        var lastErr: Error?
        for (q, ko) in feeds {
            do { all += parseRss(try await Http.get(url(q, korean: ko), retries: 1), symbol: ref.symbol, name: kr ?? ref.name) } catch { lastErr = error }
        }
        if all.isEmpty, let e = lastErr { throw e }
        return all
    }

    public static func macro() async -> [NewsItem] {
        var all: [NewsItem] = []
        for (q, ko) in [("FOMC OR 금리 OR CPI OR 고용지표 OR 연준", true), ("반도체 업황 OR HBM OR 메모리 가격", true), ("Fed OR CPI OR \"jobs report\" stocks", false)] {
            if let d = try? await Http.get(url(q, korean: ko), retries: 1) { all += parseRss(d, symbol: nil, name: nil) }
        }
        return Array(finish(all).prefix(30))
    }

    /// 7일 이내·중복 제거·최신순·호재/악재 분류
    public static func finish(_ list: [NewsItem], now: Int64 = Int64(Date().timeIntervalSince1970)) -> [NewsItem] {
        var seen = Set<String>()
        return list.filter { now - $0.publishedAt >= -3600 && now - $0.publishedAt <= maxAge }
            .filter { seen.insert(String($0.title.prefix(40))).inserted }
            .sorted { $0.publishedAt > $1.publishedAt }
            .prefix(40).map { NewsClassifier.classify($0) }
    }

    public static func parseRss(_ data: Data, symbol: String?, name: String?) -> [NewsItem] {
        let p = RssParser(); let x = XMLParser(data: data); x.delegate = p; x.parse()
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return p.items.compactMap { it in
            guard !it.title.isEmpty, let d = f.date(from: it.date.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
            var src = it.source.trimmingCharacters(in: .whitespacesAndNewlines)
            if src.isEmpty, let r = it.title.range(of: " - ", options: .backwards) { src = String(it.title[r.upperBound...]) }
            var title = it.title
            if !src.isEmpty, title.hasSuffix(" - \(src)") { title = String(title.dropLast(src.count + 3)) }
            return NewsItem(title: title.trimmingCharacters(in: .whitespaces), source: src.isEmpty ? "Google 뉴스" : src,
                            link: it.link.trimmingCharacters(in: .whitespacesAndNewlines), publishedAt: Int64(d.timeIntervalSince1970),
                            relatedSymbol: symbol, relatedName: name)
        }
    }

    final class RssParser: NSObject, XMLParserDelegate {
        struct Item { var title = "", link = "", date = "", source = "" }
        var items: [Item] = []
        var cur: Item?
        var tag = ""
        func parser(_ parser: XMLParser, didStartElement e: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
            tag = e
            if e == "item" { cur = Item() }
        }
        func parser(_ parser: XMLParser, foundCharacters s: String) {
            guard cur != nil else { return }
            switch tag { case "title": cur!.title += s; case "link": cur!.link += s; case "pubDate": cur!.date += s; case "source": cur!.source += s; default: break }
        }
        func parser(_ parser: XMLParser, didEndElement e: String, namespaceURI: String?, qualifiedName: String?) {
            if e == "item", let c = cur { items.append(c); cur = nil }
            tag = ""
        }
    }
}

/// Finnhub (무료 키: 미국 주식, 분당 60회, 개인 비상업) — 교차검증·실적 일정·회사 뉴스·시가총액
public enum FinnhubSource {
    static let base = "https://finnhub.io/api/v1"

    public static func quote(key: String, symbol: String) async throws -> (Double, Int64)? {
        let j = try J.parse(try await Http.get("\(base)/quote?symbol=\(symbol)&token=\(key)", retries: 1))
        guard let c = j["c"].double, c > 0, let t = j["t"].int64, t > 0 else { return nil }
        return (c, t)
    }

    public static func marketCapUsd(key: String, symbol: String) async throws -> Double? {
        let j = try J.parse(try await Http.get("\(base)/stock/profile2?symbol=\(symbol)&token=\(key)", retries: 1))
        guard let m = j["marketCapitalization"].double, m > 0 else { return nil }
        return m * 1_000_000
    }

    public static func earnings(key: String, symbol: String) async throws -> [EventItem] {
        let f = MarketClock.fmt(MarketClock.newYork, "yyyy-MM-dd")
        let now = Date()
        let url = "\(base)/calendar/earnings?from=\(f.string(from: now))&to=\(f.string(from: now.addingTimeInterval(90 * 86400)))&symbol=\(symbol)&token=\(key)"
        let j = try J.parse(try await Http.get(url, retries: 1))
        let today = f.date(from: f.string(from: now))!
        return j["earningsCalendar"].array.compactMap { o in
            guard let ds = o["date"].string, let d = f.date(from: ds) else { return nil }
            let hour = o["hour"].string == "bmo" ? "장 시작 전" : o["hour"].string == "amc" ? "장 마감 후" : ""
            let eps = o["epsEstimate"].double
            return EventItem(date: ds, title: "\(symbol) 실적 발표 \(hour)".trimmingCharacters(in: .whitespaces),
                             detail: eps.map { "EPS 예상 \(Fmt.d($0))" } ?? "EPS 예상치 정보 없음",
                             daysLeft: Int((d.timeIntervalSince(today) / 86400).rounded()))
        }.sorted { $0.daysLeft < $1.daysLeft }
    }
}

/// (선택) 외부 AI — OpenAI 호환 chat/completions. 사용자가 입력한 주소·모델·키로만 동작.
/// 외부 AI 는 데이터를 직접 조회하지 않으며, 앱이 계산한 사실만 전달한다 (화면에도 명시).
public enum ExternalAi {
    public static func facts(_ q: Quote, _ r: AnalysisResult) -> String {
        let kr = q.ref.market == .KR
        var s = "종목: \(q.ref.displayName) (\(q.ref.symbol))\n"
        s += "정규장 가격: \(Fmt.price(q.price, kr: kr)) (\(Fmt.pct(q.changePct))), 상태 \(q.statusText), 기준시각 \(MarketClock.format(q.marketTime, q.ref.market)), 출처 \(q.source)\n"
        s += "로컬 엔진 판단: \(r.verdict.label), 점수 \(r.score)/100\n"
        r.factors.forEach { s += "- \($0.title): \($0.status) / \($0.detail)\n" }
        if let st = r.strategy {
            s += "매수가: " + st.buys.map { "\($0.label) \(Fmt.price($0.price, kr: kr))" }.joined(separator: ", ") + "\n"
            s += "목표가: " + st.targets.map { "\($0.label) \(Fmt.price($0.price, kr: kr))" }.joined(separator: ", ") + "\n"
            s += "손절가: \(Fmt.price(st.stop.price, kr: kr))\n"
        }
        return s
    }

    static func endpointURL(_ endpoint: String) -> String {
        var e = endpoint.trimmingCharacters(in: .whitespaces)
        while e.hasSuffix("/") { e.removeLast() }
        return e.hasSuffix("/chat/completions") ? e : e + "/chat/completions"
    }

    public static func comment(endpoint: String, model: String, key: String, quote: Quote, result: AnalysisResult) async throws -> String {
        let body: [String: Any] = [
            "model": model, "temperature": 0.3,
            "messages": [
                ["role": "system", "content": "너는 신중한 주식 분석 보조자다. 아래 제공된 데이터만 근거로 한국어로 5~8문장 의견을 써라. 너는 인터넷이나 시세를 직접 조회할 수 없으므로 제공되지 않은 숫자·뉴스를 지어내지 말고, 조회한 것처럼 말하지 마라. 투자 권유가 아니라는 점을 마지막에 한 줄로 밝혀라."],
                ["role": "user", "content": facts(quote, result)],
            ],
        ]
        return try await send(endpoint: endpoint, key: key, body: body)
    }

    /// 연결 테스트 (짧은 요청)
    public static func test(endpoint: String, model: String, key: String) async throws -> String {
        let body: [String: Any] = ["model": model, "max_tokens": 10, "messages": [["role": "user", "content": "연결 테스트입니다. '정상' 이라고만 답하세요."]]]
        return try await send(endpoint: endpoint, key: key, body: body)
    }

    static func send(endpoint: String, key: String, body: [String: Any]) async throws -> String {
        do {
            let d = try await Http.post(endpointURL(endpoint), json: body, headers: ["Authorization": "Bearer \(key)"])
            if let t = try J.parse(d)["choices"][0]["message"]["content"].string, !t.isEmpty { return t }
            throw DataError("외부 AI 응답 형식을 해석하지 못했습니다.")
        } catch let e as Http.HttpError {
            throw DataError("외부 AI 오류 HTTP \(e.code): \(e.body.prefix(160))")
        }
    }
}

extension YahooSource {
    /// 교차검증용 가벼운 조회 (정규장 가격·시각만)
    public static func meta(_ symbol: String) async throws -> ChartMeta {
        let enc = symbol.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? symbol
        var last: Error?
        for h in hosts {
            do { return try parse(try await Http.get("\(h)/v8/finance/chart/\(enc)?interval=1d&range=5d", retries: 0), symbol: symbol, interval: .d1).meta }
            catch { last = error }
        }
        throw last ?? DataError("Yahoo 응답 없음")
    }
}
