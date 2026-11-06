import Foundation

/// 숫자 표시 형식 (원화 / 달러) — 안드로이드 Fmt.kt 와 동일
public enum Fmt {
    static func f(_ v: Double, _ digits: Int, group: Bool = true) -> String {
        let nf = NumberFormatter()
        nf.locale = Locale(identifier: "en_US")
        nf.numberStyle = .decimal
        nf.usesGroupingSeparator = group
        nf.minimumFractionDigits = digits; nf.maximumFractionDigits = digits
        return nf.string(from: NSNumber(value: v)) ?? String(format: "%.\(digits)f", v)
    }

    public static func price(_ p: Double?, kr: Bool) -> String {
        guard let p = p, !p.isNaN else { return "-" }
        if kr { return f(p, 0) + "원" }
        return abs(p) < 1 ? "$" + String(format: "%.4f", p) : (p < 0 ? "-$" + f(-p, 2) : "$" + f(p, 2))
    }

    public static func num(_ p: Double?, kr: Bool) -> String {
        guard let p = p, !p.isNaN else { return "-" }
        return kr ? f(p, 0) : f(p, 2)
    }

    public static func won(_ p: Double?) -> String { guard let p = p, !p.isNaN else { return "-" }; return f(p, 0) + "원" }

    public static func pct(_ p: Double?, sign: Bool = true) -> String {
        guard let p = p, !p.isNaN else { return "-" }
        return sign ? String(format: "%+.2f%%", p) : String(format: "%.2f%%", p)
    }

    public static func d(_ v: Double, _ digits: Int = 2) -> String { v.isNaN ? "-" : String(format: "%.\(digits)f", v) }

    public static func volume(_ v: Double?, kr: Bool) -> String {
        guard let v = v, !v.isNaN else { return "-" }
        if kr {
            if v >= 1e8 { return String(format: "%.2f억주", v / 1e8) }
            if v >= 1e4 { return f(v / 1e4, 0) + "만주" }
            return f(v, 0) + "주"
        }
        if v >= 1e9 { return String(format: "%.2fB", v / 1e9) }
        if v >= 1e6 { return String(format: "%.2fM", v / 1e6) }
        if v >= 1e3 { return String(format: "%.1fK", v / 1e3) }
        return f(v, 0)
    }

    public static func marketCap(_ v: Double?, kr: Bool) -> String {
        guard let v = v, !v.isNaN, v > 0 else { return "-" }
        if kr { return v >= 1e12 ? f(v / 1e12, 1) + "조원" : f(v / 1e8, 0) + "억원" }
        if v >= 1e12 { return String(format: "$%.2fT", v / 1e12) }
        if v >= 1e9 { return String(format: "$%.1fB", v / 1e9) }
        return String(format: "$%.0fM", v / 1e6)
    }
}
