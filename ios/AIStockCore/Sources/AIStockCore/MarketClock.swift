import Foundation

/// 장 운영 시간 판단 + 시각 표시 (공휴일 미반영 → 화면에 안내)
public enum MarketClock {
    public static let seoul = TimeZone(identifier: "Asia/Seoul")!
    public static let newYork = TimeZone(identifier: "America/New_York")!

    static func cal(_ tz: TimeZone) -> Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = tz; return c }

    public static func isOpen(_ market: Market, now: Date = Date()) -> Bool {
        let tz = market == .KR ? seoul : newYork
        let c = cal(tz).dateComponents([.weekday, .hour, .minute], from: now)
        guard let wd = c.weekday, wd != 1, wd != 7 else { return false }
        let m = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        return market == .KR ? (m >= 540 && m < 930) : (m >= 570 && m < 960)
    }

    public static func fmt(_ tz: TimeZone, _ pattern: String = "yyyy-MM-dd HH:mm") -> DateFormatter {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = tz; f.dateFormat = pattern; return f
    }

    public static func format(_ epochSec: Int64, _ market: Market) -> String {
        let d = Date(timeIntervalSince1970: TimeInterval(epochSec))
        let k = fmt(seoul).string(from: d)
        return market == .US ? "\(k) (한국) / \(fmt(newYork).string(from: d)) (뉴욕)" : "\(k) (한국)"
    }

    public static func formatKst(_ epochSec: Int64) -> String { fmt(seoul).string(from: Date(timeIntervalSince1970: TimeInterval(epochSec))) }
    public static func formatKstSec(_ d: Date) -> String { fmt(seoul, "MM-dd HH:mm:ss").string(from: d) }

    public static func relative(_ epochSec: Int64, now: Date = Date()) -> String {
        let diff = Int64(now.timeIntervalSince1970) - epochSec
        if diff < 60 { return "방금" }
        if diff < 3600 { return "\(diff / 60)분 전" }
        if diff < 86400 { return "\(diff / 3600)시간 전" }
        return "\(diff / 86400)일 전"
    }

    /// yyyy-MM-dd (지정 시간대)
    public static func day(_ epochSec: Int64, _ tz: TimeZone) -> String { fmt(tz, "yyyy-MM-dd").string(from: Date(timeIntervalSince1970: TimeInterval(epochSec))) }
}

/// 주요 경제 일정 (FOMC: 미 연준 공표 일정) — 안드로이드 Events.kt 와 동일
public enum Events {
    static let fomc = ["2026-01-28", "2026-03-18", "2026-04-29", "2026-06-17", "2026-07-29", "2026-09-16", "2026-10-28", "2026-12-09",
                       "2027-01-27", "2027-03-17", "2027-04-28", "2027-06-09", "2027-07-28", "2027-09-15", "2027-10-27", "2027-12-08"]

    public static func upcomingMacro(now: Date = Date(), limit: Int = 3) -> [EventItem] {
        let f = MarketClock.fmt(MarketClock.seoul, "yyyy-MM-dd")
        let today = f.date(from: f.string(from: now))!
        let out = fomc.compactMap { s -> EventItem? in
            guard let d = f.date(from: s) else { return nil }
            let days = Int((d.timeIntervalSince(today) / 86400).rounded())
            return days >= -1 ? EventItem(date: s, title: "FOMC 금리 결정", detail: "뉴욕 오후 2시 발표 (한국 다음날 새벽) · 결과에 따라 변동성 확대 가능", daysLeft: days) : nil
        }.prefix(limit)
        return out.isEmpty ? [EventItem(date: "-", title: "FOMC 일정 갱신 필요", detail: "내장 일정이 끝났습니다. 앱 업데이트가 필요합니다.", daysLeft: 999)] : Array(out)
    }
}
