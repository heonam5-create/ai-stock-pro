import Foundation

/**
 네이버 금융 공개 엔드포인트 (API 키 불필요, 비공식 — 개인·비상업 용도).
 2026-11 GitHub Actions 실측:
  - 국내 실시간: polling.finance.naver.com/api/realtime/domestic/stock/{코드} → delayTime 0, KRX + NXT 가격
  - 해외 실시간: polling.finance.naver.com/api/realtime/worldstock/stock/{네이버코드} → delayTime 0, 정규장 + 프리/애프터
    네이버코드: 나스닥 "NVDA.O", NYSE/AMEX 접미사 없음("JPM"), 특수 티커 "BRK.B" → "BRKb"
  - 자동완성: ac.stock.naver.com/ac?q=…&target=stock (한국·미국 한글명)
  - 시가총액 순위: m.stock.naver.com/api/stocks/marketValue/{KOSPI|KOSDAQ}, api.stock.naver.com/stock/exchange/{NASDAQ|NYSE|AMEX}/marketValue
  - 차트: api.stock.naver.com/chart/{domestic|foreign}/item/{코드}/{day|week|month|minute}
  - 미국 '주간거래'(야간 대체거래소) 가격은 제공되지 않음
 '실시간' 여부는 응답의 delayTime 과 체결 시각으로 판단하며, 무조건 실시간으로 가정하지 않는다.
 */
public enum NaverSource {
    public static let name = "네이버 금융"
    static let poll = "https://polling.finance.naver.com/api/realtime"
    static let chartBase = "https://api.stock.naver.com/chart"

    public struct Snap: Sendable {
        public var code: String, name: String?, symbolCode: String?
        public var price: Double, change: Double?, changePct: Double?
        public var open: Double?, high: Double?, low: Double?, volume: Double?
        public var marketStatus: String, tradedAt: Int64, delayMin: Int
        public var ext: ExtPrice?, pollingMs: Int?, zone: String, currency: String, marketCap: Double?
        public var prevClose: Double? { change.map { price - $0 } }
        public var isOpen: Bool { marketStatus.uppercased() == "OPEN" }
    }

    static let iso: ISO8601DateFormatter = { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return f }()
    static let isoNoFrac: ISO8601DateFormatter = { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime]; return f }()

    public static func time(_ s: String?) -> Int64? {
        guard var s = s, !s.isEmpty else { return nil }
        // 소수점 6자리 등은 ISO8601DateFormatter 가 처리하지 못하므로 3자리로 자름
        if let dot = s.firstIndex(of: "."), let tz = s[dot...].firstIndex(where: { $0 == "+" || $0 == "-" || $0 == "Z" }) {
            let frac = s[s.index(after: dot)..<tz]
            s = String(s[..<dot]) + "." + String(frac.prefix(3)).padding(toLength: 3, withPad: "0", startingAt: 0) + String(s[tz...])
        }
        if let d = iso.date(from: s) ?? isoNoFrac.date(from: s) { return Int64(d.timeIntervalSince1970) }
        return nil
    }

    /// 등락 코드(1 상한, 2 상승, 3 보합, 4 하한, 5 하락)로 부호 보정
    static func signed(_ v: Double?, _ o: J) -> Double? {
        guard let v = v else { return nil }
        let code = o["compareToPreviousPrice"]["code"].string
        return (code == "4" || code == "5") && v > 0 ? -v : v
    }

    static func session(_ type: String?) -> Session {
        switch type?.uppercased() { case "PRE_MARKET": return .pre; case "AFTER_MARKET": return .post; case "REGULAR_MARKET": return .regular; default: return .unknown }
    }

    /// 한국어 금액 표기("1,103조 1,928억원", "1,147억 USD") → 숫자
    public static func koreanAmount(_ s: String?) -> Double? {
        guard let s = s?.replacingOccurrences(of: ",", with: "") else { return nil }
        var total = 0.0, found = false
        for (unit, mul) in [("조", 1e12), ("억", 1e8), ("만", 1e4)] {
            if let r = s.range(of: "([0-9.]+)\(unit)", options: .regularExpression) {
                total += (Double(s[r].dropLast()) ?? 0) * mul; found = true
            }
        }
        return found ? total : nil
    }

    /// polling 응답 1건 파싱 (국내·해외 공통)
    public static func parse(_ data: Data, kr: Bool, nowSec: Int64 = Int64(Date().timeIntervalSince1970)) throws -> Snap {
        let root = try J.parse(data)
        let d = root["datas"][0]
        guard !d.isNull else { throw DataError("네이버 금융에 해당 종목 시세가 없습니다.") }
        guard let price = d["closePriceRaw"].double ?? d["closePrice"].double, price > 0 else { throw DataError("네이버 금융 시세 형식 오류(현재가 없음)") }
        let chg = signed(d["compareToPreviousClosePriceRaw"].double ?? d["compareToPreviousClosePrice"].double, d)
        let pct = signed(d["fluctuationsRatioRaw"].double ?? d["fluctuationsRatio"].double, d)
        let ex = d["stockExchangeType"]
        var zone = ex["zoneId"].string ?? ""
        if zone == "EST5EDT" || zone.isEmpty { zone = kr ? "Asia/Seoul" : "America/New_York" }
        let tradedAt = time(d["localTradedAt"].string) ?? nowSec
        var ext: ExtPrice?
        let o = d["overMarketPriceInfo"]
        if !o.isNull, let p = o["overPriceRaw"].double ?? o["overPrice"].double, p > 0 {
            let ses = session(o["tradingSessionType"].string)
            ext = ExtPrice(session: kr ? .nxt : ses,
                           label: kr ? "NXT " + (ses == .regular ? "메인마켓" : ses.label) : ses.label,
                           price: p,
                           change: signed(o["compareToPreviousClosePriceRaw"].double ?? o["compareToPreviousClosePrice"].double, o),
                           changePct: signed(o["fluctuationsRatio"].double, o),
                           time: time(o["localTradedAt"].string) ?? tradedAt,
                           active: o["overMarketStatus"].string?.uppercased() == "OPEN")
        }
        let cap = d["marketValueFullRaw"].double ?? d["marketValueRaw"].double ?? koreanAmount(d["marketValueHangeul"].string)
        return Snap(code: d["itemCode"].string ?? d["reutersCode"].string ?? "", name: d["stockName"].string ?? d["indexName"].string,
                    symbolCode: d["symbolCode"].string,
                    price: price, change: chg, changePct: pct,
                    open: d["openPriceRaw"].double ?? d["openPrice"].double,
                    high: d["highPriceRaw"].double ?? d["highPrice"].double,
                    low: d["lowPriceRaw"].double ?? d["lowPrice"].double,
                    volume: d["accumulatedTradingVolumeRaw"].double ?? d["accumulatedTradingVolume"].double,
                    marketStatus: d["marketStatus"].string ?? "UNKNOWN", tradedAt: tradedAt,
                    delayMin: ex["delayTime"].int ?? d["delayTime"].int ?? 0,
                    ext: ext, pollingMs: root["pollingInterval"].int, zone: zone,
                    currency: d["currencyType"]["code"].string ?? (kr ? "KRW" : "USD"), marketCap: cap)
    }

    /// 미국 공식 티커 → 네이버 코드 후보 (목록에 코드가 있으면 그것만 사용)
    public static func usCandidates(_ ticker: String, known: String?) -> [String] {
        if let k = known, !k.isEmpty { return [k] }
        let t = ticker.uppercased()
        if let dot = t.firstIndex(of: ".") {          // BRK.B → BRKb, BF.B → BFb
            let base = t[..<dot], cls = t[t.index(after: dot)...]
            return ["\(base)\(cls.lowercased())", "\(base)\(cls.lowercased()).O"]
        }
        return ["\(t).O", t, "\(t).K"]
    }

    /// 네이버가 돌려준 종목이 요청한 티커와 같은 기업인지 확인 (다른 기업 가격 연결 방지)
    public static func matches(ticker: String, snap: Snap) -> Bool {
        guard let sc = snap.symbolCode?.uppercased() else { return true }
        let norm = { (s: String) in s.uppercased().replacingOccurrences(of: " ", with: ".").replacingOccurrences(of: "-", with: ".") }
        return norm(sc) == norm(ticker)
    }

    public static func quote(_ ref: StockRef) async throws -> (Snap, String) {
        if ref.market == .KR {
            let d = try await Http.get("\(poll)/domestic/stock/\(ref.code)", retries: 1)
            return (try parse(d, kr: true), ref.code)
        }
        let t = ref.symbol.uppercased()
        if t.hasPrefix("^") || t.contains("=") { throw DataError("네이버 미지원 종목") }
        var last: Error?
        for c in usCandidates(t, known: ref.naverCode) {
            do {
                let enc = c.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? c
                let s = try parse(try await Http.get("\(poll)/worldstock/stock/\(enc)", retries: 1), kr: false)
                if !matches(ticker: t, snap: s) { last = DataError("네이버 코드 \(c) 가 다른 종목(\(s.symbolCode ?? "?"))으로 연결되어 사용하지 않습니다."); continue }
                return (s, c)
            } catch { last = error }
        }
        throw last ?? DataError("네이버 금융에 해당 종목이 없습니다.")
    }

    /// 지수: KOSPI, KOSDAQ (국내) / .IXIC .INX .DJI (해외)
    public static func index(_ code: String, domestic: Bool) async throws -> Snap {
        let path = domestic ? "domestic/index/\(code)" : "worldstock/index/\(code)"
        return try parse(try await Http.get("\(poll)/\(path)", retries: 1), kr: domestic)
    }

    // MARK: 차트

    static func ymdhm(_ d: Date, _ tz: TimeZone) -> String { MarketClock.fmt(tz, "yyyyMMddHHmm").string(from: d) }

    public static func parseCandles(_ data: Data, zone: TimeZone, openHour: Int, openMinute: Int) throws -> [Bar] {
        let arr = try J.parse(data).array
        let dayF = MarketClock.fmt(zone, "yyyyMMdd"), dtF = MarketClock.fmt(zone, "yyyyMMddHHmmss")
        var out: [Bar] = []
        for o in arr {
            let close = o["closePrice"].double ?? o["currentPrice"].double
            guard let c = close, c > 0 else { continue }
            let t: Int64
            if let ld = o["localDate"].string, let d = dayF.date(from: ld) {
                t = Int64(d.timeIntervalSince1970) + Int64(openHour * 3600 + openMinute * 60)
            } else if let ldt = o["localDateTime"].string, let d = dtF.date(from: ldt) {
                t = Int64(d.timeIntervalSince1970)
            } else { continue }
            let op = (o["openPrice"].double ?? c) > 0 ? (o["openPrice"].double ?? c) : c
            let h = max(o["highPrice"].double ?? c, op, c), l = min(o["lowPrice"].double ?? c, op, c)
            let v = o["accumulatedTradingVolume"].double ?? 0     // 분봉도 봉별 거래량 (실측)
            out.append(Bar(t: t, o: op, h: h, l: l, c: c, v: v))
        }
        return out.sorted { $0.t < $1.t }
    }

    /// 일/주/월봉 (Yahoo 차단 시에도 동작)
    public static func candles(_ ref: StockRef, naverCode: String?, interval: Interval) async throws -> [Bar] {
        let kr = ref.market == .KR
        let tz = kr ? MarketClock.seoul : MarketClock.newYork
        let now = Date()
        let years: Double = interval == .mo1 ? 25 : interval == .w1 ? 10 : 2
        let unit: String
        switch interval {
        case .d1: unit = "day"; case .w1: unit = "week"; case .mo1: unit = "month"
        case .m1, .m5, .m15, .m30, .h1:
            guard kr else { throw DataError("네이버는 미국 분봉을 제공하지 않습니다.") }
            unit = "minute"
        }
        let start = interval.intraday ? now.addingTimeInterval(-6 * 86400) : now.addingTimeInterval(-years * 365 * 86400)
        let code = kr ? ref.code : (naverCode ?? ref.naverCode ?? "\(ref.symbol).O")
        let enc = code.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? code
        let url = "\(chartBase)/\(kr ? "domestic" : "foreign")/item/\(enc)/\(unit)?startDateTime=\(ymdhm(start, tz))&endDateTime=\(ymdhm(now.addingTimeInterval(86400), tz))"
        var bars = try parseCandles(try await Http.get(url, retries: 1), zone: tz, openHour: kr ? 9 : 9, openMinute: kr ? 0 : 30)
        if interval.intraday { bars = aggregate(bars, minutes: [Interval.m1: 1, .m5: 5, .m15: 15, .m30: 30, .h1: 60][interval] ?? 1) }
        return bars
    }

    /// 1분봉 → n분봉
    public static func aggregate(_ bars: [Bar], minutes: Int) -> [Bar] {
        if minutes <= 1 { return bars }
        var out: [Bar] = []
        let step = Int64(minutes * 60)
        for b in bars {
            let key = b.t - b.t % step
            if var last = out.last, last.t == key {
                last.h = max(last.h, b.h); last.l = min(last.l, b.l); last.c = b.c; last.v += b.v
                out[out.count - 1] = last
            } else { out.append(Bar(t: key, o: b.o, h: b.h, l: b.l, c: b.c, v: b.v)) }
        }
        return out
    }

    // MARK: 검색·순위·뉴스

    public struct AcItem: Sendable { public let code: String, name: String, market: String, naverCode: String, nation: String }

    public static func autocomplete(_ q: String) async throws -> [AcItem] {
        let enc = q.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? q
        let j = try J.parse(try await Http.get("https://ac.stock.naver.com/ac?q=\(enc)&target=stock", retries: 1))
        return j["items"].array.compactMap { o in
            guard let code = o["code"].string, let name = o["name"].string else { return nil }
            return AcItem(code: code, name: name, market: o["typeCode"].string ?? "", naverCode: o["reutersCode"].string ?? code, nation: o["nationCode"].string ?? "")
        }
    }

    public struct RankItem: Sendable {
        public let ref: StockRef, price: Double, changePct: Double?, tradingValue: Double?, marketCap: Double?, tradedAt: Int64?
    }

    /// 시가총액 순위 (BEST 5 후보군). 국내: KOSPI/KOSDAQ, 해외: NASDAQ/NYSE/AMEX
    public static func ranking(_ market: String, page: Int = 1, pageSize: Int = 100) async throws -> [RankItem] {
        let domestic = market == "KOSPI" || market == "KOSDAQ"
        let url = domestic ? "https://m.stock.naver.com/api/stocks/marketValue/\(market)?page=\(page)&pageSize=\(pageSize)"
                           : "https://api.stock.naver.com/stock/exchange/\(market)/marketValue?page=\(page)&pageSize=\(pageSize)"
        let j = try J.parse(try await Http.get(url, retries: 1))
        return j["stocks"].array.compactMap { s in
            guard let p = s["closePriceRaw"].double ?? s["closePrice"].double, p > 0 else { return nil }
            let ref: StockRef
            if domestic {
                guard let code = s["itemCode"].string else { return nil }
                ref = StockRef(symbol: "\(code).\(market == "KOSPI" ? "KS" : "KQ")", name: s["stockName"].string ?? code, nameKr: s["stockName"].string,
                               exchange: market == "KOSPI" ? "코스피" : "코스닥", market: .KR)
            } else {
                guard let sym = s["symbolCode"].string, let nc = s["reutersCode"].string else { return nil }
                let t = sym.replacingOccurrences(of: " ", with: ".")
                ref = StockRef(symbol: t, name: s["stockNameEng"].string ?? t, nameKr: s["stockName"].string, exchange: market, market: .US,
                               naverCode: nc, yahooSymbol: t.replacingOccurrences(of: ".", with: "-"))
            }
            return RankItem(ref: ref, price: p, changePct: signed(s["fluctuationsRatioRaw"].double ?? s["fluctuationsRatio"].double, s),
                            tradingValue: s["accumulatedTradingValueRaw"].double, marketCap: s["marketValueRaw"].double,
                            tradedAt: time(s["localTradedAt"].string))
        }
    }

    /// 종목 뉴스 (국내: 네이버 뉴스, 해외: 네이버 해외증시 뉴스)
    public static func news(_ ref: StockRef, naverCode: String?) async throws -> [NewsItem] {
        let kr = ref.market == .KR
        let f = MarketClock.fmt(MarketClock.seoul, "yyyyMMddHHmm"), f2 = MarketClock.fmt(MarketClock.seoul, "yyyyMMddHHmmss")
        if kr {
            let j = try J.parse(try await Http.get("https://m.stock.naver.com/api/news/stock/\(ref.code)?pageSize=20&page=1", retries: 1))
            return j.array.flatMap { $0["items"].array }.compactMap { o in
                guard let title = o["titleFull"].string ?? o["title"].string, let dt = o["datetime"].string, let d = f.date(from: dt) else { return nil }
                let link = o["mobileNewsUrl"].string ?? "https://n.news.naver.com/mnews/article/\(o["officeId"].string ?? "")/\(o["articleId"].string ?? "")"
                return NewsItem(title: decode(title), source: o["officeName"].string ?? "네이버 뉴스", link: link,
                                publishedAt: Int64(d.timeIntervalSince1970), relatedSymbol: ref.symbol, relatedName: ref.displayName)
            }
        }
        let code = naverCode ?? ref.naverCode ?? "\(ref.symbol).O"
        let enc = code.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? code
        let j = try J.parse(try await Http.get("https://api.stock.naver.com/news/worldStock/\(enc)?pageSize=20&page=1", retries: 1))
        return j.array.compactMap { o in
            guard let title = o["tit"].string, let dt = o["dt"].string, let d = f2.date(from: dt) else { return nil }
            let oid = o["oid"].string ?? "", aid = o["aid"].string ?? ""
            return NewsItem(title: decode(title), source: o["ohnm"].string ?? "네이버 해외증시", link: "https://m.stock.naver.com/worldstock/news/\(oid)/\(aid)",
                            publishedAt: Int64(d.timeIntervalSince1970), relatedSymbol: ref.symbol, relatedName: ref.displayName)
        }
    }

    static func decode(_ s: String) -> String {
        s.replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">").replacingOccurrences(of: "&#39;", with: "'")
    }
}
