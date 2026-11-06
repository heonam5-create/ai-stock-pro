import Foundation

/// 시세 신선도·교차검증·일봉 정렬 규칙 — 안드로이드 QuoteLogic.kt 와 동일 규칙
public enum QuoteLogic {

    /// 정규장 중: 체결 시각이 (지연+15분)보다 오래되면 STALE. 공급원이 지연 0분을 명시한 경우만 REALTIME.
    /// 장 마감: 5일 이상 지났으면 STALE, 아니면 CLOSED.
    public static func freshness(open: Bool, tradedAt: Int64, nowSec: Int64, delayMin: Int?, sourceRealtime: Bool) -> Freshness {
        let age = nowSec - tradedAt
        if open {
            let allowed = Int64(((delayMin ?? 20) + 15) * 60)
            if age > allowed { return .stale }
            return sourceRealtime && (delayMin ?? 1) == 0 ? .realtime : .delayed
        }
        return age > 5 * 86400 ? .stale : .closed
    }

    /// 정규장 진행 여부 = 공급원 상태 OPEN 이면서 정규장 시간표(한국 09:00~15:30, 미국 09:30~16:00 현지) 안
    public static func regularOpen(naverStatusOpen: Bool, market: Market, at: Date) -> Bool {
        naverStatusOpen && MarketClock.isOpen(market, now: at)
    }

    public static func delayText(_ source: String, _ delayMin: Int?, kr: Bool, fallback: Bool = false) -> String {
        let base: String
        if let d = delayMin { base = d == 0 ? "\(source) · 지연 0분(공급원 표기 기준)" : "\(source) · 약 \(d)분 지연" }
        else { base = "\(source) · " + (kr ? "최대 20분 지연 가능" : "수 분~15분 지연 가능") }
        return fallback ? base + " · 지연 가능 / 대체 데이터 사용 중" : base
    }

    public static func statusText(_ f: Freshness, _ session: Session, open: Bool, ageMin: Int64) -> String {
        switch f {
        case .realtime: return "실시간 · \(session.label)"
        case .delayed: return "지연 시세 · \(session.label)"
        case .closed: return "장 마감 · 정규장 종가"
        case .stale: return open ? "⚠ 시세 지연 (\(ageMin)분 전 체결가)" : "⚠ 오래된 데이터 (\(ageMin / 1440)일 전)"
        case .error: return "⚠ 데이터 오류 · 마지막 성공 데이터"
        }
    }

    /// 기준 시각이 다르면 비교 생략. 가격이 달라도 높은/낮은 쪽을 고르지 않고 기본 공급원 값 유지 + 경고
    public static func crossCheck(primaryPrice: Double, primaryTime: Int64, primaryOpen: Bool,
                                  source: String, otherPrice: Double?, otherTime: Int64?, maxTimeGapSec: Int64 = 300) -> CrossCheck {
        guard let op = otherPrice, op > 0, let ot = otherTime, ot > 0 else {
            return CrossCheck(source: source, price: otherPrice, time: otherTime, diffPct: nil, mismatch: false, message: "\(source) 응답 없음 — 교차검증 불가")
        }
        let gap = abs(primaryTime - ot)
        let diff = (op / primaryPrice - 1) * 100
        let gapOk = primaryOpen ? gap <= maxTimeGapSec : gap <= 6 * 3600
        if !gapOk {
            return CrossCheck(source: source, price: op, time: ot, diffPct: diff, mismatch: false, message: "\(source) 기준 시각이 \(gap / 60)분 달라 비교 생략 (지연 시세 차이)")
        }
        let limit = primaryOpen ? 1.0 : 0.3
        if abs(diff) > limit {
            return CrossCheck(source: source, price: op, time: ot, diffPct: diff, mismatch: true,
                              message: "⚠ 시세 불일치: \(source) \(Fmt.d(op)) (\(String(format: "%+.2f", diff))%) — 원인 확인 전까지 매매 판단 보류")
        }
        return CrossCheck(source: source, price: op, time: ot, diffPct: diff, mismatch: false, message: "\(source) 와 일치 (차이 \(String(format: "%+.2f", diff))%)")
    }

    /// 일봉 마지막 봉을 시세 스냅샷과 일치 (같은 날: 종가 교체 / 새 거래일: 봉 추가 / 일봉이 더 최신: 그대로)
    public static func alignDaily(_ bars: [Bar], price: Double, time: Int64, zone: TimeZone,
                                  open: Double? = nil, high: Double? = nil, low: Double? = nil, volume: Double? = nil) -> [Bar] {
        guard let last = bars.last, price > 0, time > 0 else { return bars }
        let qd = MarketClock.day(time, zone), ld = MarketClock.day(last.t, zone)
        if qd == ld {
            var b = last
            b.c = price; b.h = max(last.h, price, high ?? price); b.l = min(last.l, price, low ?? price)
            if let v = volume, v > 0 { b.v = v }
            return Array(bars.dropLast()) + [b]
        }
        if qd > ld {
            let o = (open ?? 0) > 0 ? open! : price
            return bars + [Bar(t: time, o: o, h: max(o, price, high ?? price), l: min(o, price, low ?? price), c: price, v: volume ?? 0)]
        }
        return bars
    }

    /// 자동 새로고침 주기(초): 장중/시간외 진행 중이면 한국 10초·미국 15초, 마감이면 60초
    public static func refreshSeconds(_ q: Quote) -> Double {
        let live = q.marketOpen || q.ext?.active == true
        return !live ? 60 : (q.ref.market == .KR ? 10 : 15)
    }
}
