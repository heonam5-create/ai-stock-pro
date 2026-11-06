import Foundation

/**
 한국·미국 전체 종목 목록 (앱에 내장 + 주 1회 갱신본 자동 다운로드).
 - 한국: KRX KIND 상장법인목록(공식) + 네이버 시가총액 목록(우선주 등 보완)
 - 미국: 네이버 거래소별 목록(한글명·네이버 코드) + Nasdaq Trader 공식 심볼 목록(티커 검증)
 - 종목 검색은 시세 조회와 분리되어 있어 Yahoo·네이버 시세가 실패해도 검색은 동작한다.
 */
public final class StockDirectory: @unchecked Sendable {
    public struct Entry: Sendable, Hashable {
        public let symbol: String          // 005930.KS / BRK.B
        public let nameKr: String
        public let nameEn: String
        public let market: Market
        public let exchange: String        // 코스피/코스닥/NASDAQ/NYSE/AMEX
        public let naverCode: String?
        public let yahoo: String
        public let rank: Int               // 시가총액 순위 (작을수록 큼)
        let keys: [String]                 // 정규화된 검색 키
        let chosung: String

        public var ref: StockRef {
            StockRef(symbol: symbol, name: nameEn.isEmpty ? nameKr : nameEn, nameKr: nameKr.isEmpty ? nil : nameKr,
                     exchange: exchange, market: market, naverCode: naverCode, yahooSymbol: yahoo)
        }
    }

    public static let shared = StockDirectory()
    public private(set) var entries: [Entry] = []
    public private(set) var krUpdated = "-", usUpdated = "-"
    private var bySym: [String: Entry] = [:]
    private let lock = NSLock()

    /// 갱신본 다운로드 주소 (저장소 main 브랜치의 data/ — 주 1회 자동 생성)
    public static let remoteBase = "https://raw.githubusercontent.com/heonam5-create/ai-stock-pro/main/data"

    init() { reload() }

    static var cacheDir: URL {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("stocklists", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    /// 내장본과 다운로드본 중 더 최신 것을 읽음
    static func load(_ file: String) -> Data? {
        let cached = try? Data(contentsOf: cacheDir.appendingPathComponent(file))
        let bundled = Bundle.module.url(forResource: file.replacingOccurrences(of: ".json", with: ""), withExtension: "json").flatMap { try? Data(contentsOf: $0) }
        func updated(_ d: Data?) -> String { d.flatMap { try? J.parse($0)["updated"].string } ?? "" }
        if let c = cached, updated(c) > updated(bundled) { return c }
        return bundled ?? cached
    }

    public func reload() {
        var list: [Entry] = []
        var ku = "-", uu = "-"
        if let d = Self.load("stocks_kr.json"), let j = try? J.parse(d) {
            ku = j["updated"].string ?? "-"
            for it in j["items"].array {
                guard let code = it[0].string, let name = it[1].string, let mk = it[2].string else { continue }
                let sym = "\(code).\(mk)"
                list.append(Self.make(sym, name, "", .KR, mk == "KS" ? "코스피" : "코스닥", nil, sym, it[3].int ?? 9999))
            }
        }
        if let d = Self.load("stocks_us.json"), let j = try? J.parse(d) {
            uu = j["updated"].string ?? "-"
            for it in j["items"].array {
                guard let t = it[0].string else { continue }
                let nc = it[4].string.flatMap { $0.isEmpty ? nil : $0 }
                list.append(Self.make(t, it[1].string ?? "", it[2].string ?? t, .US, it[3].string ?? "", nc, it[5].string ?? t.replacingOccurrences(of: ".", with: "-"), it[6].int ?? 99999))
            }
        }
        var map: [String: Entry] = [:]
        for e in list where map[e.symbol] == nil { map[e.symbol] = e }
        lock.lock(); entries = list; bySym = map; krUpdated = ku; usUpdated = uu; lock.unlock()
    }

    static func make(_ sym: String, _ kr: String, _ en: String, _ m: Market, _ ex: String, _ nc: String?, _ y: String, _ rank: Int) -> Entry {
        var keys = [norm(kr), norm(en), norm(sym), norm(sym.components(separatedBy: ".").first ?? sym)]
        if let al = StockAliases.map[sym] { keys += al.map(norm) }
        // 영문명에서 Inc, Corp 등 제거한 키
        let shortEn = en.replacingOccurrences(of: "(?i)(,? inc\\.?|,? corporation|,? corp\\.?|,? ltd\\.?|,? plc| common stock| class [a-z]| ordinary shares?)", with: "", options: .regularExpression)
        keys.append(norm(shortEn))
        return Entry(symbol: sym, nameKr: kr.isEmpty ? (StockAliases.map[sym]?.first ?? "") : kr, nameEn: en, market: m, exchange: ex,
                     naverCode: nc, yahoo: y, rank: rank, keys: Array(Set(keys.filter { !$0.isEmpty })), chosung: chosung(kr))
    }

    public static func norm(_ s: String) -> String {
        s.lowercased().filter { !" -.&,'()".contains($0) }
    }

    /// 한글 초성 추출 (삼성전자 → ㅅㅅㅈㅈ)
    static let cho: [Character] = ["ㄱ","ㄲ","ㄴ","ㄷ","ㄸ","ㄹ","ㅁ","ㅂ","ㅃ","ㅅ","ㅆ","ㅇ","ㅈ","ㅉ","ㅊ","ㅋ","ㅌ","ㅍ","ㅎ"]
    public static func chosung(_ s: String) -> String {
        String(s.unicodeScalars.compactMap { u -> Character? in
            let v = Int(u.value)
            if v >= 0xAC00 && v <= 0xD7A3 { return cho[(v - 0xAC00) / 588] }
            return nil
        })
    }
    static func isChosungQuery(_ q: String) -> Bool { !q.isEmpty && q.allSatisfy { cho.contains($0) } }

    static func lev(_ a: [Character], _ b: [Character]) -> Int {
        if a.isEmpty { return b.count }; if b.isEmpty { return a.count }
        var prev = Array(0...b.count)
        for i in 1...a.count {
            var cur = [i] + [Int](repeating: 0, count: b.count)
            for j in 1...b.count { cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1)) }
            prev = cur
        }
        return prev[b.count]
    }

    /// 검색: 정확 > 앞부분 > 포함 > 초성 > 유사(오타 1~2자). 같은 점수면 시가총액 순위.
    public func search(_ query: String, market: Market? = nil, limit: Int = 30) -> [Entry] {
        let raw = query.trimmingCharacters(in: .whitespaces)
        let q = Self.norm(raw)
        if q.isEmpty { return [] }
        lock.lock(); let all = entries; lock.unlock()
        let pool = market == nil ? all : all.filter { $0.market == market }
        let choQ = Self.isChosungQuery(raw.replacingOccurrences(of: " ", with: ""))
        var scored: [(Entry, Int)] = []
        for e in pool {
            var best = 0
            for k in e.keys {
                if k == q { best = 100; break }
                if k.hasPrefix(q) { best = max(best, 80) } else if q.count >= 2 && k.contains(q) { best = max(best, 60) }
            }
            if best == 0 && choQ && !e.chosung.isEmpty {
                if e.chosung.hasPrefix(raw) { best = 55 } else if e.chosung.contains(raw) { best = 45 }
            }
            if best > 0 { scored.append((e, best)) }
        }
        if scored.isEmpty && q.count >= 3 {
            let qa = Array(q), maxD = q.count >= 6 ? 2 : 1
            for e in pool {
                for k in e.keys where abs(k.count - q.count) <= maxD && k.count <= 30 {
                    if Self.lev(Array(k), qa) <= maxD { scored.append((e, 30)); break }
                }
            }
        }
        return scored.sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.0.rank < $1.0.rank }.prefix(limit).map(\.0)
    }

    public func bySymbol(_ s: String) -> Entry? { lock.lock(); defer { lock.unlock() }; return bySym[s] ?? bySym[s.uppercased()] }

    public func byKrCode(_ code: String) -> Entry? {
        lock.lock(); defer { lock.unlock() }
        return bySym["\(code.uppercased()).KS"] ?? bySym["\(code.uppercased()).KQ"]
    }

    public func topByRank(_ market: Market, _ n: Int) -> [Entry] {
        lock.lock(); let all = entries; lock.unlock()
        return Array(all.filter { $0.market == market }.sorted { $0.rank < $1.rank }.prefix(n))
    }

    public var counts: (kr: Int, us: Int) {
        lock.lock(); defer { lock.unlock() }
        return (entries.filter { $0.market == .KR }.count, entries.filter { $0.market == .US }.count)
    }

    /// 저장소의 최신 목록을 내려받아 캐시 (실패해도 내장본 사용). 신규 상장·상장폐지·이름 변경 반영
    @discardableResult
    public func updateFromRemote() async -> String {
        var msgs: [String] = []
        for f in ["stocks_kr.json", "stocks_us.json"] {
            do {
                let d = try await Http.get("\(Self.remoteBase)/\(f)", retries: 1)
                let j = try J.parse(d)
                guard j["items"].array.count > 1000 else { throw DataError("목록이 비정상적으로 짧습니다") }
                try d.write(to: Self.cacheDir.appendingPathComponent(f), options: .atomic)
                msgs.append("\(f == "stocks_kr.json" ? "한국" : "미국") \(j["updated"].string ?? "")")
            } catch { msgs.append("\(f == "stocks_kr.json" ? "한국" : "미국") 갱신 실패(\(Http.friendly(error)))") }
        }
        reload()
        return msgs.joined(separator: " · ")
    }
}
