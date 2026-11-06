import Foundation

// 안드로이드 data/Models.kt 와 같은 구조

public enum Market: String, Codable, Sendable { case KR, US }

/// 종목 식별 정보. symbol: 한국은 "005930.KS" 형식, 미국은 공식 티커("BRK.B")
public struct StockRef: Codable, Hashable, Sendable, Identifiable {
    public var symbol: String
    public var name: String
    public var nameKr: String?
    public var exchange: String
    public var market: Market
    /// 네이버 시세 코드 (미국: "NVDA.O", "BRKb" 등). nil 이면 조회 시 자동 확인
    public var naverCode: String?
    /// Yahoo 시세 코드 (BRK.B → BRK-B)
    public var yahooSymbol: String?

    public var id: String { symbol }
    public var displayName: String { nameKr?.isEmpty == false ? nameKr! : name }
    public var code: String { symbol.components(separatedBy: ".").first ?? symbol }
    public var yahoo: String {
        if let y = yahooSymbol, !y.isEmpty { return y }
        return market == .KR ? symbol : symbol.replacingOccurrences(of: ".", with: "-")
    }

    public init(symbol: String, name: String, nameKr: String? = nil, exchange: String = "", market: Market? = nil,
                naverCode: String? = nil, yahooSymbol: String? = nil) {
        self.symbol = symbol; self.name = name; self.nameKr = nameKr; self.exchange = exchange
        self.market = market ?? (symbol.hasSuffix(".KS") || symbol.hasSuffix(".KQ") ? .KR : .US)
        self.naverCode = naverCode; self.yahooSymbol = yahooSymbol
    }
}

/// 캔들. t = epoch 초
public struct Bar: Codable, Equatable, Sendable {
    public var t: Int64, o: Double, h: Double, l: Double, c: Double, v: Double
    public init(t: Int64, o: Double, h: Double, l: Double, c: Double, v: Double) { self.t = t; self.o = o; self.h = h; self.l = l; self.c = c; self.v = v }
}

public enum Interval: String, CaseIterable, Sendable {
    case m1, m5, m15, m30, h1, d1, w1, mo1
    public var label: String { ["1분", "5분", "15분", "30분", "1시간", "일봉", "주봉", "월봉"][Interval.allCases.firstIndex(of: self)!] }
    public var yahoo: String { ["1m", "5m", "15m", "30m", "60m", "1d", "1wk", "1mo"][Interval.allCases.firstIndex(of: self)!] }
    public var range: String { ["5d", "1mo", "1mo", "1mo", "3mo", "2y", "10y", "max"][Interval.allCases.firstIndex(of: self)!] }
    public var intraday: Bool {
        switch self { case .m1, .m5, .m15, .m30, .h1: return true; default: return false }
    }
}

public struct ChartMeta: Sendable {
    public var symbol: String, currency: String, exchange: String, longName: String?
    public var price: Double?, marketTime: Int64, timezone: String
    public var high52: Double?, low52: Double?, dayHigh: Double?, dayLow: Double?, volume: Double?
    public init(symbol: String, currency: String, exchange: String = "", longName: String? = nil, price: Double? = nil, marketTime: Int64 = 0,
                timezone: String, high52: Double? = nil, low52: Double? = nil, dayHigh: Double? = nil, dayLow: Double? = nil, volume: Double? = nil) {
        self.symbol = symbol; self.currency = currency; self.exchange = exchange; self.longName = longName; self.price = price
        self.marketTime = marketTime; self.timezone = timezone; self.high52 = high52; self.low52 = low52
        self.dayHigh = dayHigh; self.dayLow = dayLow; self.volume = volume
    }
}

public struct ChartData: Sendable {
    public var meta: ChartMeta, bars: [Bar], interval: Interval, fetchedAt: Date, source: String
    public init(meta: ChartMeta, bars: [Bar], interval: Interval, fetchedAt: Date = Date(), source: String) {
        self.meta = meta; self.bars = bars; self.interval = interval; self.fetchedAt = fetchedAt; self.source = source
    }
}

public enum Session: String, Sendable {
    case pre, regular, post, nxt, closed, unknown
    public var label: String {
        switch self {
        case .pre: return "프리마켓"; case .regular: return "정규장"; case .post: return "애프터마켓"
        case .nxt: return "NXT 대체거래소"; case .closed: return "장 마감"; case .unknown: return "세션 확인 불가"
        }
    }
}

/// 시세 신선도 — 실시간이 아닌 값을 실시간으로 표시하지 않기 위한 단일 기준
public enum Freshness: String, Sendable {
    case realtime, delayed, closed, stale, error
    public var label: String {
        switch self {
        case .realtime: return "실시간"; case .delayed: return "지연 시세"; case .closed: return "장 마감"
        case .stale: return "⚠ 시세 지연"; case .error: return "⚠ 데이터 오류"
        }
    }
}

public struct ExtPrice: Sendable {
    public var session: Session, label: String, price: Double, change: Double?, changePct: Double?, time: Int64, active: Bool
}

public struct CrossCheck: Sendable {
    public var source: String, price: Double?, time: Int64?, diffPct: Double?, mismatch: Bool, message: String
}

public struct Quote: Sendable {
    public var ref: StockRef
    public var currency: String
    /// 정규장 가격 (장중=현재가, 마감=정규장 종가). 분석·손익·알림의 단일 기준
    public var price: Double
    public var prevClose: Double?, change: Double?, changePct: Double?
    public var volume: Double?, dayHigh: Double?, dayLow: Double?, high52: Double?, low52: Double?
    public var marketCap: Double?, marketCapSource: String?
    public var marketTime: Int64
    public var timezone: String
    public var marketOpen: Bool
    public var statusText: String
    public var session: Session
    public var freshness: Freshness
    public var source: String
    public var delayText: String
    public var ext: ExtPrice?
    public var check: CrossCheck?
    public var fetchedAt: Date
    public var warning: String?
    /// 대체 공급원 사용 중 (네이버 실패)
    public var fallback: Bool

    /// 매수·매도 판단에 쓰면 안 되는 상태
    public var unreliable: Bool { freshness == .stale || freshness == .error || check?.mismatch == true }
}

public enum Sentiment: String, Codable, Sendable {
    case positive, negative, neutral
    public var label: String { self == .positive ? "호재" : self == .negative ? "악재" : "중립" }
}

public struct NewsItem: Identifiable, Sendable {
    public var id: String { link + title }
    public var title: String, source: String, link: String, publishedAt: Int64
    public var relatedSymbol: String?, relatedName: String?
    public var sentiment: Sentiment = .neutral
    public var categories: [String] = []
    public init(title: String, source: String, link: String, publishedAt: Int64, relatedSymbol: String? = nil, relatedName: String? = nil) {
        self.title = title; self.source = source; self.link = link; self.publishedAt = publishedAt
        self.relatedSymbol = relatedSymbol; self.relatedName = relatedName
    }
}

public struct EventItem: Sendable { public var date: String, title: String, detail: String, daysLeft: Int }

public struct DataError: LocalizedError, Sendable {
    public let message: String
    public init(_ m: String) { message = m }
    public var errorDescription: String? { message }
}
