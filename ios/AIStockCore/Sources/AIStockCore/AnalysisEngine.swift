import Foundation

// 안드로이드 analysis/AnalysisEngine.kt 를 그대로 옮긴 규칙 기반 다요인 분석 엔진.
// 모든 결론은 실제 시세에서 계산된 근거와 함께 반환되며, 데이터가 부족하면 '분석 불가'.

public enum Verdict: String, Sendable {
    case strongBuy, splitBuy, hold, splitSell, avoid, na
    public var label: String {
        switch self {
        case .strongBuy: return "적극 매수"; case .splitBuy: return "분할매수"; case .hold: return "관망"
        case .splitSell: return "분할매도"; case .avoid: return "매수 금지 / 위험"; case .na: return "분석 불가"
        }
    }
}

public struct Factor: Identifiable, Sendable {
    public var id: Int { no }
    public let no: Int, title: String, status: String, detail: String, score: Int, weight: Int, available: Bool
}

public struct PriceLevel: Identifiable, Sendable {
    public var id: String { label }
    public let label: String, price: Double, diffPct: Double, basis: String, action: String, extra: String
}

public struct Strategy: Sendable {
    public let buys: [PriceLevel], noBuyFrom: Double?, noBuyText: String
    public let targets: [PriceLevel], stop: PriceLevel, trendBreak: PriceLevel, riskReward: Double?
}

public struct SRPoint: Sendable { public let price: Double; public let basis: String }

public struct AnalysisResult: Sendable {
    public let verdict: Verdict, score: Int, summary: String, reasons: [String], risks: [String]
    public let factors: [Factor], strategy: Strategy?, supports: [SRPoint], resistances: [SRPoint]
    public let price: Double, atr: Double, atrPct: Double, rsi: Double, ret120: Double, warnings: [String]
}

public struct AnalysisInput: Sendable {
    public var bars: [Bar], price: Double, isKr: Bool, news: [NewsItem]?, events: [EventItem]?, nowSec: Int64
    public init(bars: [Bar], price: Double, isKr: Bool, news: [NewsItem]?, events: [EventItem]?, nowSec: Int64 = Int64(Date().timeIntervalSince1970)) {
        self.bars = bars; self.price = price; self.isKr = isKr; self.news = news; self.events = events; self.nowSec = nowSec
    }
}

public enum AnalysisEngine {
    public static let minBars = 120

    static func na(_ msg: String, _ price: Double) -> AnalysisResult {
        AnalysisResult(verdict: .na, score: 0, summary: msg, reasons: [], risks: [], factors: [], strategy: nil,
                       supports: [], resistances: [], price: price, atr: .nan, atrPct: .nan, rsi: .nan, ret120: .nan, warnings: [msg])
    }

    static func clamp(_ v: Int, _ lo: Int = -2, _ hi: Int = 2) -> Int { min(max(v, lo), hi) }

    public static func analyze(_ inp: AnalysisInput) -> AnalysisResult {
        let bars = inp.bars, price = inp.price
        if price.isNaN || price <= 0 { return na("현재가 데이터가 없어 분석할 수 없습니다.", price) }
        if bars.count < minBars {
            return na("일봉 데이터가 \(bars.count)개뿐이라 분석할 수 없습니다 (최소 \(minBars)거래일 필요 · 신규 상장 종목 등).", price)
        }
        let kr = inp.isKr
        let ind = IndicatorSet(bars, intraday: false, zone: TimeZone(identifier: "UTC")!)
        let i = bars.count - 1
        let c = ind.c
        let atr = ind.atr[i]
        if atr.isNaN || atr <= 0 { return na("변동성(ATR) 계산에 필요한 데이터가 부족합니다.", price) }
        var warnings: [String] = []
        let p = { (x: Double) in Fmt.price(x, kr: kr) }

        if inp.nowSec - bars.last!.t > 10 * 86400 { warnings.append("마지막 일봉이 10일 이상 지난 데이터입니다. 거래정지·데이터 지연 여부를 확인하세요.") }
        for j in max(1, bars.count - 250)..<bars.count {
            let r = bars[j].c / bars[j - 1].c
            if r > 2.2 || r < 0.45 {
                let day = String(MarketClock.formatKst(bars[j].t).prefix(10))
                warnings.append("\(day) 에 하루 ±50% 이상 가격 급변이 있습니다 (분할·병합 미반영 가능성). 지표 해석에 주의하세요.")
                break
            }
        }

        let ma5 = ind.ma[5]![i], ma20 = ind.ma[20]![i], ma60 = ind.ma[60]![i]
        let ma120 = ind.ma[120]![i], ma200 = ind.ma[200]![i]
        let ma20Prev = ind.ma[20]![i - 5], ma60Prev = ind.ma[60]![i - 20]
        let ma120Prev = i - 20 >= 0 ? ind.ma[120]![i - 20] : Double.nan
        let rsi = ind.rsi[i]
        let atrPct = atr / price * 100
        let ret20 = (price / c[i - 20] - 1) * 100
        let ret60 = (price / c[i - 60] - 1) * 100
        let ret120 = (price / c[i - 119] - 1) * 100

        // ─── 지지/저항 후보 ───
        let levels = Indicators.srLevels(bars, atr: atr)
        let vp = Indicators.volumeProfile(bars)
        let vpAvg = vp.isEmpty ? 0 : vp.map(\.volume).reduce(0, +) / Double(vp.count)
        let last20 = bars.suffix(20)
        let hi20 = last20.map(\.h).max()!, lo20 = last20.map(\.l).min()!
        let hi52 = bars.suffix(252).map(\.h).max()!
        let vwap20 = ind.vwap[i]
        let bbU = ind.bb.upper[i], bbL = ind.bb.lower[i]

        var supCand: [SRPoint] = [], resCand: [SRPoint] = []
        func add(_ px: Double, _ basis: String) {
            if px.isNaN || px <= 0 { return }
            if px < price * 0.998 && px > price - 6 * atr { supCand.append(SRPoint(price: px, basis: basis)) }
            else if px > price * 1.002 && px < price + 8 * atr { resCand.append(SRPoint(price: px, basis: basis)) }
        }
        levels.forEach { add($0.price, $0.touches >= 2 ? "과거 고·저점 밀집(\($0.touches)회)" : "직전 고·저점") }
        add(ma20, "20일 이동평균선"); add(ma60, "60일 이동평균선"); add(ma120, "120일 이동평균선")
        if !ma200.isNaN { add(ma200, "200일 이동평균선") }
        add(bbL, "볼린저밴드 하단"); add(bbU, "볼린저밴드 상단"); add(vwap20, "20일 VWAP(평균 거래단가)")
        vp.filter { $0.volume > vpAvg * 1.3 }.forEach { add($0.mid, "매물대(거래 집중 가격대)") }
        add(lo20, "최근 20일 최저가"); add(hi20, "최근 20일 최고가"); add(hi52, "52주(1년) 최고가")

        let supports = merge(supCand.sorted { $0.price > $1.price }, atr * 0.25)
        let resistances = merge(resCand.sorted { $0.price < $1.price }, atr * 0.25)
        let nearSup = supports.first, nearRes = resistances.first

        var factors: [Factor] = []

        // 1. 현재 추세
        do {
            let slope20 = (ma20 / ma20Prev - 1) * 100
            let aligned = ma5 > ma20 && ma20 > ma60 && ma60 > ma120
            let reverse = ma5 < ma20 && ma20 < ma60 && ma60 < ma120
            let s: Int
            if aligned && price > ma20 && slope20 > 0 { s = 2 }
            else if price > ma20 && price > ma60 && slope20 > 0 { s = 1 }
            else if reverse && price < ma20 && slope20 < 0 { s = -2 }
            else if price < ma20 && price < ma60 { s = -1 }
            else { s = 0 }
            let st = ["강한 하락 추세", "하락 추세", "횡보·중립", "상승 추세", "강한 상승 추세"][s + 2]
            let arr = aligned ? "이동평균 정배열" : reverse ? "이동평균 역배열" : "이동평균 혼조"
            let d = "현재가 \(p(price)) 기준 20일선(\(p(ma20))) \(price > ma20 ? "위" : "아래"), 60일선(\(p(ma60))) \(price > ma60 ? "위" : "아래"), " +
                "120일선 \(price > ma120 ? "위" : "아래")\(!ma200.isNaN ? ", 200일선 " + (price > ma200 ? "위" : "아래") : ""). " +
                "\(arr), 20일선 5일 기울기 \(Fmt.pct(slope20))."
            factors.append(Factor(no: 1, title: "현재 추세", status: st, detail: d, score: s, weight: 8, available: true))
        }
        // 2. 모멘텀
        do {
            let h = ind.macd.hist, hNow = h[i], hPrev = h[i - 3]
            var pts = 0
            if ret20 > 5 { pts += 1 } else if ret20 < -5 { pts -= 1 }
            if hNow > 0 && hNow > hPrev { pts += 1 } else if hNow < 0 && hNow < hPrev { pts -= 1 }
            if ret60 > 15 { pts += 1 } else if ret60 < -15 { pts -= 1 }
            let s = clamp(pts)
            let st = s >= 2 ? "상승 모멘텀 강함" : s == 1 ? "상승 모멘텀" : s == 0 ? "모멘텀 약함" : s == -1 ? "하락 모멘텀" : "하락 모멘텀 강함"
            let d = "20일 수익률 \(Fmt.pct(ret20)), 60일 \(Fmt.pct(ret60)). MACD 히스토그램 \(hNow >= 0 ? "양(+)" : "음(-)"), " +
                "3일 전 대비 \(hNow > hPrev ? "확대" : "축소") (MACD \(Fmt.d(ind.macd.macd[i])) / 시그널 \(Fmt.d(ind.macd.signal[i])))."
            factors.append(Factor(no: 2, title: "상승·하락 모멘텀", status: st, detail: d, score: s, weight: 6, available: true))
        }
        // 3. 단기 매수 적합성
        let ext20 = (price - ma20) / atr
        do {
            let k = ind.stoch.k, dd = ind.stoch.d
            var pts = 0
            var notes: [String] = []
            let rsiI = Int(rsi.rounded())
            if rsi >= 40 && rsi <= 65 { pts += 1; notes.append("RSI \(rsiI) 적정 구간") }
            else if rsi > 72 { pts -= 1; notes.append("RSI \(rsiI) 과열") }
            else { notes.append("RSI \(rsiI)") }
            let distSup = nearSup.map { (price - $0.price) / atr } ?? .nan
            if !distSup.isNaN && distSup <= 1.2 { pts += 1; notes.append("지지선(\(p(nearSup!.price)))과 ATR \(Fmt.d(distSup, 1))배 거리로 근접") }
            if ext20 > 2.5 { pts -= 1; notes.append("20일선 대비 ATR \(Fmt.d(ext20, 1))배 이격 → 추격매수 위험") }
            if k[i] > dd[i] && k[i - 1] <= dd[i - 1] && k[i] < 40 { pts += 1; notes.append("스토캐스틱 침체권 골든크로스") }
            if k[i] < dd[i] && k[i - 1] >= dd[i - 1] && k[i] > 70 { pts -= 1; notes.append("스토캐스틱 과열권 데드크로스") }
            let s = clamp(pts)
            let st = s >= 2 ? "단기 매수 적합" : s == 1 ? "조건부 적합(눌림 대기)" : s == 0 ? "중립" : s == -1 ? "단기 매수 부적합" : "단기 매수 매우 부적합"
            factors.append(Factor(no: 3, title: "단기 매수 적합성", status: st, detail: notes.joined(separator: ", ") + ".", score: s, weight: 5, available: true))
        }
        // 4. 중기 보유
        do {
            var pts = 0
            let slope60 = (ma60 / ma60Prev - 1) * 100
            pts += price > ma60 ? 1 : -1
            pts += slope60 > 0 ? 1 : -1
            if !ma120Prev.isNaN && ma120 > ma120Prev && price > ma120 { pts += 1 }
            if !ma200.isNaN && price < ma200 { pts -= 1 }
            let s = clamp(pts)
            let st = s >= 2 ? "중기 보유 적합" : s == 1 ? "중기 보유 무난" : s == 0 ? "중립" : s == -1 ? "중기 보유 주의" : "중기 보유 부적합"
            let d = "60일선 20일 기울기 \(Fmt.pct(slope60)), 120일 수익률 \(Fmt.pct(ret120))" +
                (!ma200.isNaN ? ", 200일선 \(price > ma200 ? "위(장기 상승 구조)" : "아래(장기 약세 구조)")" : ", 200일선 계산 불가(데이터 부족)") + "."
            factors.append(Factor(no: 4, title: "중기 보유 적합성", status: st, detail: d, score: s, weight: 6, available: true))
        }
        // 5. 거래량
        do {
            let v = ind.v
            let avg5 = Indicators.avg(Array(v[(i - 5)..<i]))
            let avg20 = Indicators.avg(Array(v[(i - 25)..<(i - 5)]))
            let ratio = avg20 > 0 ? avg5 / avg20 : Double.nan
            var upV = 0.0, dnV = 0.0
            for j in (i - 20)..<i { if c[j] >= c[j - 1] { upV += v[j] } else { dnV += v[j] } }
            let ud = dnV > 0 ? upV / dnV : 2.0
            var pts = 0
            let up5 = c[i - 1] > c[i - 6]
            if !ratio.isNaN && ratio > 1.4 { pts += up5 ? 1 : -1 }
            if ud > 1.3 { pts += 1 } else if ud < 0.75 { pts -= 1 }
            let s = clamp(pts)
            let st = s >= 1 ? "매수세 우위 거래량" : s == 0 ? "거래량 평이" : "매도세 우위 거래량"
            let d = avg20 <= 0 ? "거래량 데이터가 부족합니다." :
                "최근 5일 평균 거래량이 이전 20일 대비 \(Fmt.d(ratio))배, 20일간 상승일/하락일 거래량 비율 \(Fmt.d(ud))."
            factors.append(Factor(no: 5, title: "거래량 변화", status: st, detail: d, score: s, weight: 4, available: avg20 > 0))
        }
        // 6. 매물대
        do {
            var pts = 0
            let poc = vp.max { $0.volume < $1.volume }
            let total = vp.map(\.volume).reduce(0, +)
            let over = vp.filter { $0.low >= price && $0.low <= price * 1.10 }.map(\.volume).reduce(0, +)
            let overRatio = total > 0 ? over / total : 0
            if let poc = poc { pts += price > poc.mid ? 1 : -1 }
            if overRatio > 0.25 { pts -= 1 }
            if let r = nearRes, (r.price - price) / atr < 0.5 { pts -= 1 }
            if let s0 = nearSup, (price - s0.price) / atr < 1.0 { pts += 1 }
            let s = clamp(pts)
            let st = s >= 1 ? "매물 부담 적음" : s == 0 ? "매물 중립" : "상단 매물 부담"
            let d = (poc.map { "최대 매물대 \(p($0.mid)) \(price > $0.mid ? "위(지지 역할)" : "아래(저항 역할)"). " } ?? "") +
                "현재가 위 +10% 구간 매물 비중 \(Fmt.d(overRatio * 100, 0))%. " +
                "가까운 지지 \(nearSup.map { p($0.price) } ?? "없음"), 가까운 저항 \(nearRes.map { p($0.price) } ?? "없음")."
            factors.append(Factor(no: 6, title: "매물대 및 지지·저항", status: st, detail: d, score: s, weight: 4, available: poc != nil))
        }
        // 7. 과매수·과매도
        do {
            let pb = bbU > bbL ? (price - bbL) / (bbU - bbL) : 0.5
            let k = ind.stoch.k[i]
            let trendScore = factors[0].score
            let s: Int, st: String
            if rsi > 78 || pb > 1.1 { s = -2; st = "강한 과매수" }
            else if rsi > 70 || pb > 1.0 { s = -1; st = "과매수" }
            else if rsi < 30 || pb < 0 { s = trendScore >= 0 ? 1 : 0; st = trendScore >= 0 ? "과매도(반등 가능)" : "과매도(하락추세 중 · 반등 신호 아님)" }
            else { s = 0; st = "중립" }
            factors.append(Factor(no: 7, title: "과매수·과매도", status: st,
                detail: "RSI \(Fmt.d(rsi, 1)), 스토캐스틱 %K \(Fmt.d(k, 1)), 볼린저 %B \(Fmt.d(pb)) (0 미만 과매도 · 1 초과 과매수).", score: s, weight: 4, available: true))
        }
        // 8. 변동성
        do {
            var gaps = 0
            for j in (i - 59)...i where abs(bars[j].o / c[j - 1] - 1) > 0.03 { gaps += 1 }
            var pts = atrPct > 6 ? -2 : atrPct > 4 ? -1 : atrPct < 2 ? 1 : 0
            if gaps >= 4 { pts -= 1 }
            let s = clamp(pts)
            let st = atrPct > 6 ? "변동성 매우 높음" : atrPct > 4 ? "변동성 높음" : atrPct < 2 ? "변동성 낮음" : "변동성 보통"
            factors.append(Factor(no: 8, title: "변동성 및 갭 위험", status: st,
                detail: "하루 평균 변동폭(ATR) \(p(atr)) = 주가의 \(Fmt.d(atrPct))%. 최근 60일 중 3% 이상 갭 발생 \(gaps)회.", score: s, weight: 3, available: true))
        }
        // 9. 뉴스
        if let news = inp.news {
            let recent = news.filter { inp.nowSec - $0.publishedAt <= 7 * 86400 }
            if recent.isEmpty {
                factors.append(Factor(no: 9, title: "최신 뉴스 영향", status: "최근 7일 뉴스 없음", detail: "최근 7일 내 관련 뉴스가 없습니다 (중립 처리).", score: 0, weight: 3, available: true))
            } else {
                var net = 0.0
                for n in recent {
                    let w = inp.nowSec - n.publishedAt <= 72 * 3600 ? 2.0 : 1.0
                    net += n.sentiment == .positive ? w : n.sentiment == .negative ? -w : 0
                }
                let pos = recent.filter { $0.sentiment == .positive }.count, neg = recent.filter { $0.sentiment == .negative }.count
                let s = net >= 4 ? 2 : net >= 1.5 ? 1 : net <= -4 ? -2 : net <= -1.5 ? -1 : 0
                let st = s > 0 ? "호재 우위" : s < 0 ? "악재 우위" : "중립"
                let top = recent.filter { $0.sentiment != .neutral }.prefix(2).map { "「\(String($0.title.prefix(40)))」" }.joined(separator: " / ")
                factors.append(Factor(no: 9, title: "최신 뉴스 영향", status: st,
                    detail: "최근 7일 \(recent.count)건 중 호재 \(pos)건, 악재 \(neg)건 (제목 키워드 자동 분류). \(top)", score: s, weight: 3, available: true))
            }
        } else {
            factors.append(Factor(no: 9, title: "최신 뉴스 영향", status: "뉴스 미조회", detail: "뉴스를 불러오지 못해 이 항목은 점수에서 제외했습니다.", score: 0, weight: 3, available: false))
        }
        // 10. 일정
        if let ev = inp.events {
            let soon = ev.filter { $0.daysLeft >= 0 && $0.daysLeft <= 7 }
            var s = 0
            var notes: [String] = []
            for e in soon {
                if e.title.contains("실적") { s -= 1; notes.append("\(e.title) D-\(e.daysLeft) → 발표 전후 갭 위험") }
                if e.title.contains("FOMC") && e.daysLeft <= 3 { s -= 1; notes.append("FOMC D-\(e.daysLeft) → 시장 변동성 확대 가능") }
            }
            if notes.isEmpty { notes.append("7일 이내 확인된 주요 일정 없음" + (kr ? " (한국 기업 실적일은 무료 데이터 미제공 → DART 공시 확인)" : "")) }
            factors.append(Factor(no: 10, title: "실적 발표 등 주요 일정", status: s < 0 ? "일정 리스크 있음" : "일정 리스크 낮음",
                detail: notes.joined(separator: " · "), score: clamp(s, -2, 0), weight: 2, available: true))
        } else {
            factors.append(Factor(no: 10, title: "실적 발표 등 주요 일정", status: "일정 미조회", detail: "일정 데이터를 불러오지 못했습니다.", score: 0, weight: 2, available: false))
        }

        // ─── 종합 점수 ───
        let avail = factors.filter(\.available)
        let maxAbs = Double(avail.map(\.weight).reduce(0, +)) * 2
        let raw = Double(avail.map { $0.weight * $0.score }.reduce(0, +))
        let score = min(max(Int((50 + raw / maxAbs * 50).rounded()), 0), 100)

        var verdict: Verdict = score >= 72 ? .strongBuy : score >= 58 ? .splitBuy : score >= 43 ? .hold : score >= 32 ? .splitSell : .avoid
        let trendS = factors[0].score, momS = factors[1].score
        if trendS <= -2 && momS <= -1 { verdict = .avoid; warnings.append("강한 하락 추세 + 하락 모멘텀으로 '매수 금지' 판정") }
        if atrPct > 8 && trendS < 0 && verdict != .avoid { verdict = .avoid; warnings.append("하락 추세 중 일 변동폭 8% 초과로 위험 판정") }
        if verdict == .strongBuy && (rsi > 75 || ext20 > 3) { verdict = .splitBuy; warnings.append("단기 과열(RSI·이격)로 '적극 매수' → '분할매수'로 낮춤") }
        if verdict == .strongBuy && factors[9].score <= -1 { verdict = .splitBuy; warnings.append("임박한 주요 일정으로 '적극 매수' → '분할매수'로 낮춤") }

        let strategy = buildStrategy(price: price, atr: atr, kr: kr, sup: supports, res: resistances, bars: bars, ma60: ma60, ma120: ma120, verdict: verdict)
        let rrNow = strategy.riskReward ?? 0
        if (verdict == .strongBuy || verdict == .splitBuy) && rrNow < 0.8 {
            verdict = verdict == .strongBuy ? .splitBuy : .hold
            warnings.append("손익비 \(Fmt.d(rrNow)):1 로 불리하여 판단을 한 단계 낮춤")
        }

        let ranked = avail.sorted { $0.weight * $0.score > $1.weight * $1.score }
        let reasons = ranked.filter { $0.score > 0 }.prefix(3).map { "\($0.title): \($0.status) — \($0.detail)" }
        let risks = ranked.reversed().filter { $0.score < 0 }.prefix(3).map { "\($0.title): \($0.status) — \($0.detail)" }

        var summary = "\(verdict.label) (종합 \(score)점/100). \(factors[0].status), \(factors[1].status), \(factors[6].status). "
        if let rr = strategy.riskReward { summary += "계획 평균단가 기준 손익비 \(Fmt.d(rr)):1. " }
        if verdict == .avoid { summary += "신규 매수는 보류하고 추세 회복(20일선 회복·거래량 동반 반등)을 확인하세요." }

        return AnalysisResult(verdict: verdict, score: score, summary: summary, reasons: Array(reasons), risks: Array(risks),
                              factors: factors, strategy: strategy, supports: Array(supports.prefix(5)), resistances: Array(resistances.prefix(5)),
                              price: price, atr: atr, atrPct: atrPct, rsi: rsi, ret120: ret120, warnings: warnings)
    }

    static func merge(_ sorted: [SRPoint], _ tol: Double) -> [SRPoint] {
        var out: [SRPoint] = []
        for s in sorted {
            if let last = out.last, abs(last.price - s.price) <= tol {
                if !last.basis.contains(s.basis) { out[out.count - 1] = SRPoint(price: last.price, basis: last.basis + " + " + s.basis) }
            } else { out.append(s) }
        }
        return out
    }

    static func touches(_ bars: [Bar], _ level: Double, below: Bool) -> Int {
        bars.suffix(20).filter { below ? $0.l <= level : $0.h >= level }.count
    }

    static func buildStrategy(price: Double, atr: Double, kr: Bool, sup: [SRPoint], res: [SRPoint],
                              bars: [Bar], ma60: Double, ma120: Double, verdict: Verdict) -> Strategy {
        let r = { (x: Double) in Indicators.roundPrice(x, kr: kr) }
        let diff = { (x: Double) in (x / price - 1) * 100 }

        // 매수가 (1차는 현재가에서 ATR 1.2배 이내 → 체결 가능성 낮은 깊은 매수가만 반복 추천하지 않음)
        let b1src = sup.first { $0.price >= price - 1.2 * atr } ?? SRPoint(price: price - 0.5 * atr, basis: "근접 지지선 없음 → 평균 변동폭(ATR)의 0.5배 눌림목")
        let b1 = r(b1src.price)
        let b2src = sup.first { $0.price <= b1 - 0.4 * atr && $0.price >= price - 3 * atr } ?? SRPoint(price: b1 - 1.0 * atr, basis: "1차 매수가 − ATR 1배")
        let b2 = r(b2src.price)
        let b3src = sup.first { $0.price <= b2 - 0.4 * atr && $0.price >= price - 5 * atr } ?? SRPoint(price: b2 - 1.2 * atr, basis: "2차 매수가 − ATR 1.2배")
        let b3 = r(b3src.price)

        let stopSrc = sup.first { $0.price < b3 - 0.2 * atr && $0.price >= b3 - 1.5 * atr }
        let stop = r(stopSrc.map { $0.price - 0.3 * atr } ?? b3 - 1.0 * atr)
        let stopBasis = stopSrc.map { "\($0.basis) 아래 (ATR 0.3배 여유)" } ?? "3차 매수가 − ATR 1배 (변동폭 기준)"

        let tbPrice: Double, tbBasis: String
        if !ma60.isNaN && ma60 < price { tbPrice = ma60; tbBasis = "60일 이동평균선 (중기 추세 기준선)" }
        else if !ma120.isNaN && ma120 < price { tbPrice = ma120; tbBasis = "120일 이동평균선 (장기 추세 기준선)" }
        else { tbPrice = bars.suffix(60).map(\.l).min()!; tbBasis = "최근 60일 최저가 (이탈 시 하락 추세 연장)" }
        let tb = r(tbPrice)

        let t1src = res.first { $0.price >= price + 0.6 * atr } ?? SRPoint(price: price + 1.5 * atr, basis: "상단 저항 없음 → ATR 1.5배")
        let t1 = r(t1src.price)
        let t2src = res.first { $0.price >= t1 + 0.5 * atr } ?? SRPoint(price: t1 + 1.5 * atr, basis: "1차 목표가 + ATR 1.5배")
        let t2 = r(t2src.price)
        let t3src = res.first { $0.price >= t2 + 0.5 * atr } ?? SRPoint(price: t2 + 2.0 * atr, basis: "2차 목표가 + ATR 2배")
        let t3 = r(t3src.price)

        func fromB1(_ x: Double) -> String { "1차 매수가 기준 \(Fmt.pct((x / b1 - 1) * 100))" }

        let buys = [
            PriceLevel(label: "1차 매수가", price: b1, diffPct: diff(b1), basis: b1src.basis, action: "계획 물량의 30~40% 매수. 종가가 이 가격 위에서 지지되는지 확인.",
                       extra: "최근 20거래일 중 \(touches(bars, b1, below: true))일 장중 도달"),
            PriceLevel(label: "2차 매수가", price: b2, diffPct: diff(b2), basis: b2src.basis, action: "추가 하락 시 30% 추가. 거래량 터진 급락이면 하루 관망 후 진행.",
                       extra: "최근 20거래일 중 \(touches(bars, b2, below: true))일 장중 도달"),
            PriceLevel(label: "3차 매수가", price: b3, diffPct: diff(b3), basis: b3src.basis, action: "마지막 30%. 이후 손절가 이탈 시 계획대로 정리.",
                       extra: "최근 20거래일 중 \(touches(bars, b3, below: true))일 장중 도달"),
        ]
        let targets = [
            PriceLevel(label: "1차 목표가", price: t1, diffPct: diff(t1), basis: t1src.basis, action: "보유 물량 30% 매도 후 손절가를 매수 평균가로 올림.", extra: fromB1(t1)),
            PriceLevel(label: "2차 목표가", price: t2, diffPct: diff(t2), basis: t2src.basis, action: "30~40% 추가 매도, 나머지는 추세 추종.", extra: fromB1(t2)),
            PriceLevel(label: "3차 목표가", price: t3, diffPct: diff(t3), basis: t3src.basis, action: "잔여 물량 정리 또는 직전 저점 기준 추적 손절.", extra: fromB1(t3)),
        ]
        let stopLv = PriceLevel(label: "손절 기준가", price: stop, diffPct: diff(stop), basis: stopBasis, action: "종가 기준 이탈 시 손절 (손실 제한 우선).", extra: fromB1(stop))
        let tbLv = PriceLevel(label: "추세 붕괴 기준가", price: tb, diffPct: diff(tb), basis: tbBasis, action: "종가 이탈 시 중기 보유 판단 철회, 비중 축소.", extra: "")

        let ma20last = Indicators.sma(bars.map(\.c), 20).last ?? .nan
        let chase = min(res.first.map { $0.price - 0.25 * atr } ?? Double.greatestFiniteMagnitude, ma20last + 2.5 * atr)
        let noBuyFrom = r(max(chase, price))
        let noBuyText: String
        if verdict == .avoid { noBuyText = "현재 판단이 '매수 금지'이므로 모든 가격대에서 신규 매수를 보류하세요." }
        else if chase <= price { noBuyText = "현재가가 이미 추격매수 금지 구간입니다 (저항선 근접 또는 20일선 대비 과도한 이격). 눌림을 기다리세요." }
        else { noBuyText = "\(Fmt.price(noBuyFrom, kr: kr)) 이상은 저항선 근접·과열 구간으로 추격매수 금지." }
        let avgEntry = b1 * 0.4 + b2 * 0.3 + b3 * 0.3
        let rr: Double? = avgEntry > stop ? (t2 - avgEntry) / (avgEntry - stop) : nil
        return Strategy(buys: buys, noBuyFrom: noBuyFrom, noBuyText: noBuyText, targets: targets, stop: stopLv, trendBreak: tbLv, riskReward: rr)
    }
}
