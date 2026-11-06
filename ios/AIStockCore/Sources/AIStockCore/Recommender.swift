import Foundation

/// BEST 5 추천 점수 (실제 데이터로 계산된 AnalysisResult 를 9개 항목으로 환산) — 안드로이드 Recommender.kt 와 동일
public struct Recommendation: Identifiable, Sendable {
    public var id: String { ref.symbol }
    public let ref: StockRef, price: Double, result: AnalysisResult, total: Int
    public let parts: [(String, Int)], reason: String, riskGrade: String, dataTime: Int64
}

public enum Recommender {
    static func f(_ s: Int) -> Int { Int((Double(s + 2) * 2.5).rounded()) }

    public static func riskGrade(_ atrPct: Double, _ v: Verdict) -> String {
        if v == .avoid { return "매우 높음" }
        if atrPct.isNaN { return "-" }
        return atrPct < 2.5 ? "낮음" : atrPct < 4 ? "보통" : atrPct < 6 ? "높음" : "매우 높음"
    }

    /// 추천 대상이 아니면 nil (분석 불가·매수 금지·분할매도)
    public static func score(_ ref: StockRef, _ r: AnalysisResult, dataTime: Int64) -> Recommendation? {
        if r.verdict == .na || r.verdict == .avoid || r.verdict == .splitSell { return nil }
        guard let st = r.strategy else { return nil }
        var fx: [Int: Factor] = [:]; r.factors.forEach { fx[$0.no] = $0 }
        let b1 = st.buys[0].price
        let dist = (r.price - b1) / r.price * 100
        let g = r.ret120
        let growth = g.isNaN ? 5 : g > 40 ? 10 : g > 20 ? 8 : g > 8 ? 6 : g > 0 ? 5 : g > -10 ? 3 : 1
        let rr = st.riskReward ?? 0
        let parts: [(String, Int)] = [
            ("상승 추세", f(fx[1]?.score ?? 0)),
            ("거래량 증가", f(fx[5]?.score ?? 0)),
            ("기술적 지표", Int((Double(f(fx[2]?.score ?? 0) + f(fx[7]?.score ?? 0)) / 2).rounded())),
            ("성장성(120일 주가 성장률)", growth),
            ("호재·악재", fx[9]?.available == true ? f(fx[9]?.score ?? 0) : 5),
            ("변동성 안정", f(fx[8]?.score ?? 0)),
            ("지지선과의 거리", dist < 2 ? 10 : dist < 4 ? 7 : dist < 7 ? 4 : 2),
            ("손익비", rr >= 2.5 ? 10 : rr >= 2 ? 8 : rr >= 1.5 ? 6 : rr >= 1 ? 4 : 1),
            ("단기 매매 적합성", f(fx[3]?.score ?? 0)),
        ]
        let weights = [1.4, 0.9, 1.1, 0.9, 0.8, 0.7, 1.0, 1.1, 1.0]
        let sum = zip(parts, weights).map { Double($0.0.1) * $0.1 }.reduce(0, +)
        let total = Int((sum / (10 * weights.reduce(0, +)) * 100).rounded())
        let top = parts.sorted { $0.1 > $1.1 }.prefix(3).map { "\($0.0) \($0.1)/10" }.joined(separator: ", ")
        let reason = "\(r.verdict.label) · \(top). \(r.factors.first?.status ?? ""), 손익비 \(Fmt.d(rr)):1"
        return Recommendation(ref: ref, price: r.price, result: r, total: total, parts: parts, reason: reason,
                              riskGrade: riskGrade(r.atrPct, r.verdict), dataTime: dataTime)
    }

    public static func rank(_ list: [Recommendation], n: Int = 5) -> [Recommendation] {
        Array(list.sorted { $0.total != $1.total ? $0.total > $1.total : $0.result.score > $1.result.score }.prefix(n))
    }
}
