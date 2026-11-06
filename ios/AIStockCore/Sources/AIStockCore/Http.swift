import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// 네트워크 도우미: 시간 제한, 429·5xx 재시도(지수 대기), 한국어 오류 메시지
public enum Http {
    static let ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

    public struct HttpError: LocalizedError, Sendable {
        public let code: Int; public let body: String
        public var errorDescription: String? { "HTTP \(code)" }
    }

    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 15
        c.timeoutIntervalForResource = 60
        c.requestCachePolicy = .reloadIgnoringLocalCacheData   // 시세는 항상 새로 받음
        c.urlCache = nil
        return URLSession(configuration: c)
    }()

    public static func get(_ url: String, headers: [String: String] = [:], retries: Int = 2) async throws -> Data {
        try await request(url, method: "GET", body: nil, headers: headers, retries: retries, timeout: 15)
    }

    public static func getString(_ url: String, headers: [String: String] = [:], retries: Int = 2, encoding: String.Encoding = .utf8) async throws -> String {
        let d = try await get(url, headers: headers, retries: retries)
        return String(data: d, encoding: encoding) ?? String(decoding: d, as: UTF8.self)
    }

    public static func post(_ url: String, json: [String: Any], headers: [String: String], timeout: TimeInterval = 60) async throws -> Data {
        let body = try JSONSerialization.data(withJSONObject: json)
        return try await request(url, method: "POST", body: body, headers: headers.merging(["Content-Type": "application/json; charset=utf-8"]) { $1 }, retries: 1, timeout: timeout)
    }

    static func request(_ url: String, method: String, body: Data?, headers: [String: String], retries: Int, timeout: TimeInterval) async throws -> Data {
        guard let u = URL(string: url) else { throw DataError("잘못된 주소입니다.") }
        var last: Error?
        for attempt in 0...retries {
            var r = URLRequest(url: u, timeoutInterval: timeout)
            r.httpMethod = method
            r.setValue(ua, forHTTPHeaderField: "User-Agent")
            r.setValue("application/json, application/xml, text/xml, */*", forHTTPHeaderField: "Accept")
            r.setValue("ko-KR,ko;q=0.9,en;q=0.8", forHTTPHeaderField: "Accept-Language")
            headers.forEach { r.setValue($0.value, forHTTPHeaderField: $0.key) }
            r.httpBody = body
            do {
                let (data, resp) = try await session.data(for: r)
                let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
                if (200..<300).contains(code) { return data }
                let e = HttpError(code: code, body: String(decoding: data.prefix(300), as: UTF8.self))
                if code == 429 || code >= 500 { last = e } else { throw e }
            } catch let e as HttpError {
                if e.code == 429 || e.code >= 500 { last = e } else { throw e }
            } catch {
                if (error as? URLError)?.code == .cancelled { throw error }
                last = error
            }
            if attempt < retries { try await Task.sleep(nanoseconds: UInt64(800_000_000 * (attempt + 1) * (attempt + 1))) }
        }
        throw last ?? DataError("알 수 없는 네트워크 오류")
    }

    /// 사용자에게 보여줄 한국어 오류 문장
    public static func friendly(_ e: Error) -> String {
        if let d = e as? DataError { return d.message }
        if let h = e as? HttpError {
            switch h.code {
            case 429: return "데이터 제공처의 무료 호출 한도에 잠시 걸렸습니다. 1~2분 뒤 다시 시도하세요."
            case 401, 403: return "데이터 제공처가 요청을 거부했습니다 (API 키 확인 필요 또는 일시 차단)."
            case 404: return "해당 종목 데이터를 찾을 수 없습니다."
            default: return "데이터 서버 오류(\(h.code)). 잠시 후 다시 시도하세요."
            }
        }
        if let u = e as? URLError {
            switch u.code {
            case .notConnectedToInternet, .networkConnectionLost: return "인터넷에 연결되어 있지 않습니다. 와이파이/데이터 연결을 확인하세요."
            case .timedOut: return "서버 응답이 늦습니다. 잠시 후 다시 시도하세요."
            default: return "네트워크 오류가 발생했습니다 (\(u.code.rawValue))."
            }
        }
        return "처리 중 오류가 발생했습니다: \(e.localizedDescription)"
    }
}

/// 느슨한 JSON 접근 도우미 (org.json 의 optString/optDouble 과 비슷하게)
public struct J {
    public let v: Any?
    public init(_ v: Any?) { self.v = v }
    public static func parse(_ d: Data) throws -> J {
        do { return J(try JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed])) }
        catch { throw DataError("응답 형식을 해석하지 못했습니다.") }
    }
    public subscript(_ k: String) -> J { J((v as? [String: Any])?[k]) }
    public subscript(_ i: Int) -> J { guard let a = v as? [Any], i >= 0, i < a.count else { return J(nil) }; return J(a[i]) }
    public var array: [J] { (v as? [Any])?.map { J($0) } ?? [] }
    public var isNull: Bool { v == nil || v is NSNull }
    public var string: String? {
        if let s = v as? String { return s }
        if let n = v as? NSNumber { return n.stringValue }
        return nil
    }
    public var double: Double? {
        if let n = v as? NSNumber { let d = n.doubleValue; return d.isNaN ? nil : d }
        if let s = v as? String { return Double(s.replacingOccurrences(of: ",", with: "").replacingOccurrences(of: "+", with: "").trimmingCharacters(in: .whitespaces)) }
        return nil
    }
    public var int: Int? { double.map { Int($0) } }
    public var int64: Int64? { double.map { Int64($0) } }
    public var bool: Bool? { (v as? NSNumber)?.boolValue }
}
