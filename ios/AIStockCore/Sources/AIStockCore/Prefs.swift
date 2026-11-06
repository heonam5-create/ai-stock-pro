import Foundation
import Security

public struct WatchItem: Codable, Identifiable, Hashable, Sendable {
    public var id: String { symbol }
    public var symbol: String, name: String
    public var qty: Double = 0, avgPrice: Double = 0
    public var targetPrice: Double? = nil, stopPrice: Double? = nil
    public var naverCode: String? = nil, yahoo: String? = nil
    public var addedAt: Date = Date()
    public var market: Market { symbol.hasSuffix(".KS") || symbol.hasSuffix(".KQ") ? .KR : .US }
    public var ref: StockRef { StockRef(symbol: symbol, name: name, nameKr: name, market: market, naverCode: naverCode, yahooSymbol: yahoo) }
    public init(symbol: String, name: String, naverCode: String? = nil, yahoo: String? = nil) { self.symbol = symbol; self.name = name; self.naverCode = naverCode; self.yahoo = yahoo }
}

public enum AlertType: String, Codable, CaseIterable, Sendable {
    case buyPrice, sellPrice, supportBreak, resistBreak, stopLoss, surge, plunge, volumeSpike
    public var label: String {
        switch self {
        case .buyPrice: return "목표 매수가 도달 (이하)"; case .sellPrice: return "목표 매도가 도달 (이상)"
        case .supportBreak: return "지지선 이탈 (이하)"; case .resistBreak: return "저항선 돌파 (이상)"
        case .stopLoss: return "손절 기준가 도달 (이하)"; case .surge: return "급등 (등락률 이상)"
        case .plunge: return "급락 (등락률 이하, 양수로 입력)"; case .volumeSpike: return "거래량 급증 (20일 평균 대비 배수)"
        }
    }
    public var unit: String { switch self { case .surge, .plunge: return "%"; case .volumeSpike: return "배"; default: return "가격" } }
}

public struct AlertRule: Codable, Identifiable, Hashable, Sendable {
    public var id: Int64, symbol: String, name: String, type: AlertType, value: Double
    public var enabled: Bool = true, lastFired: Date = .distantPast
    public init(symbol: String, name: String, type: AlertType, value: Double) {
        id = Int64(Date().timeIntervalSince1970 * 1000); self.symbol = symbol; self.name = name; self.type = type; self.value = value
    }
}

/// 알림 조건 판정 (안드로이드 AlertLogic 과 동일)
public enum AlertLogic {
    public static func check(_ r: AlertRule, price: Double, changePct: Double?, volume: Double?, avgVol20: Double?, kr: Bool, marketOpen: Bool) -> String? {
        let p = Fmt.price(price, kr: kr), v = Fmt.price(r.value, kr: kr)
        switch r.type {
        case .buyPrice: return price <= r.value ? "목표 매수가 \(v) 도달 (현재 \(p))" : nil
        case .sellPrice: return price >= r.value ? "목표 매도가 \(v) 도달 (현재 \(p))" : nil
        case .supportBreak: return price < r.value ? "지지선 \(v) 이탈 (현재 \(p))" : nil
        case .resistBreak: return price > r.value ? "저항선 \(v) 돌파 (현재 \(p))" : nil
        case .stopLoss: return price <= r.value ? "⚠ 손절 기준가 \(v) 도달 (현재 \(p))" : nil
        case .surge: if marketOpen, let c = changePct, c >= r.value { return "급등 \(Fmt.pct(c)) (현재 \(p))" }; return nil
        case .plunge: if marketOpen, let c = changePct, c <= -r.value { return "급락 \(Fmt.pct(c)) (현재 \(p))" }; return nil
        case .volumeSpike:
            if marketOpen, let vol = volume, let a = avgVol20, a > 0, vol >= a * r.value { return "거래량 급증: 20일 평균의 \(Fmt.d(vol / a, 1))배 (현재 \(p))" }
            return nil
        }
    }
    /// 가격 도달형은 1회 알림 후 꺼짐, 급등락·거래량은 6시간 간격
    public static func isOneShot(_ t: AlertType) -> Bool { t != .surge && t != .plunge && t != .volumeSpike }
}

/// API 키 등 비밀값: iOS 키체인(기기 보안 영역)에만 저장 — 소스코드·저장소에 키 없음
public enum Keychain {
    static let service = "com.aistock.pro.secrets"
    public static func set(_ name: String, _ value: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: name]
        SecItemDelete(q as CFDictionary)
        if value.isEmpty { return }
        var add = q
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
    public static func get(_ name: String) -> String {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: name,
                                kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return "" }
        return String(decoding: d, as: UTF8.self)
    }
}

/// 앱 데이터 저장/복원 (UserDefaults JSON) — 앱 종료·재시작 후에도 유지
public final class Prefs: @unchecked Sendable {
    public static let shared = Prefs()
    let ud: UserDefaults
    public init(_ ud: UserDefaults = .standard) { self.ud = ud }

    func load<T: Decodable>(_ key: String, _ def: T) -> T {
        guard let d = ud.data(forKey: key), let v = try? JSONDecoder().decode(T.self, from: d) else { return def }
        return v
    }
    func save<T: Encodable>(_ key: String, _ v: T) { if let d = try? JSONEncoder().encode(v) { ud.set(d, forKey: key) } }

    public var watchlist: [WatchItem] { get { load("watchlist", []) } set { save("watchlist", newValue) } }
    public var alerts: [AlertRule] { get { load("alerts", []) } set { save("alerts", newValue) } }
    public var recent: [StockRef] { get { load("recent", []) } set { save("recent", Array(newValue.prefix(10))) } }

    public var alertsEnabled: Bool { get { ud.object(forKey: "alerts_enabled") as? Bool ?? true } set { ud.set(newValue, forKey: "alerts_enabled") } }
    public var includeWatchInBest: Bool { get { ud.object(forKey: "best_watch") as? Bool ?? true } set { ud.set(newValue, forKey: "best_watch") } }
    public var aiEndpoint: String { get { ud.string(forKey: "ai_endpoint") ?? "" } set { ud.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "ai_endpoint") } }
    public var aiModel: String { get { ud.string(forKey: "ai_model") ?? "" } set { ud.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "ai_model") } }
    public var finnhubKey: String { get { Keychain.get("finnhub") } set { Keychain.set("finnhub", newValue.trimmingCharacters(in: .whitespaces)) } }
    public var aiKey: String { get { Keychain.get("ai_key") } set { Keychain.set("ai_key", newValue.trimmingCharacters(in: .whitespaces)) } }
    public var aiConfigured: Bool { !aiEndpoint.isEmpty && !aiModel.isEmpty && !aiKey.isEmpty }

    public func addRecent(_ r: StockRef) { recent = [r] + recent.filter { $0.symbol != r.symbol } }
}
