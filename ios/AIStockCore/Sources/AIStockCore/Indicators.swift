import Foundation

// 안드로이드 analysis/Indicators.kt 와 동일한 계산 (값이 부족한 앞부분은 NaN)

public enum Indicators {

    public static func sma(_ v: [Double], _ n: Int) -> [Double] {
        var out = [Double](repeating: .nan, count: v.count)
        if n <= 0 || v.count < n { return out }
        var sum = 0.0
        for i in v.indices {
            sum += v[i]
            if i >= n { sum -= v[i - n] }
            if i >= n - 1 { out[i] = sum / Double(n) }
        }
        return out
    }

    public static func ema(_ v: [Double], _ n: Int) -> [Double] {
        var out = [Double](repeating: .nan, count: v.count)
        guard let start = v.firstIndex(where: { !$0.isNaN }), v.count - start >= n, n > 0 else { return out }
        let k = 2.0 / Double(n + 1)
        var prev = v[start..<(start + n)].reduce(0, +) / Double(n)
        out[start + n - 1] = prev
        var i = start + n
        while i < v.count { prev = v[i] * k + prev * (1 - k); out[i] = prev; i += 1 }
        return out
    }

    /// Wilder RSI
    public static func rsi(_ c: [Double], _ n: Int = 14) -> [Double] {
        var out = [Double](repeating: .nan, count: c.count)
        if c.count <= n { return out }
        var gain = 0.0, loss = 0.0
        for i in 1...n { let d = c[i] - c[i - 1]; if d > 0 { gain += d } else { loss -= d } }
        gain /= Double(n); loss /= Double(n)
        out[n] = loss == 0 ? 100 : 100 - 100 / (1 + gain / loss)
        var i = n + 1
        while i < c.count {
            let d = c[i] - c[i - 1]
            gain = (gain * Double(n - 1) + max(d, 0)) / Double(n)
            loss = (loss * Double(n - 1) + max(-d, 0)) / Double(n)
            out[i] = loss == 0 ? 100 : 100 - 100 / (1 + gain / loss)
            i += 1
        }
        return out
    }

    public struct Macd { public let macd: [Double]; public let signal: [Double]; public let hist: [Double] }

    public static func macd(_ c: [Double], fast: Int = 12, slow: Int = 26, sig: Int = 9) -> Macd {
        let f = ema(c, fast), s = ema(c, slow)
        let m = c.indices.map { (f[$0].isNaN || s[$0].isNaN) ? Double.nan : f[$0] - s[$0] }
        let signal = ema(m, sig)
        let hist = c.indices.map { (m[$0].isNaN || signal[$0].isNaN) ? Double.nan : m[$0] - signal[$0] }
        return Macd(macd: m, signal: signal, hist: hist)
    }

    public struct Bands { public let mid: [Double]; public let upper: [Double]; public let lower: [Double] }

    public static func bollinger(_ c: [Double], n: Int = 20, k: Double = 2) -> Bands {
        let mid = sma(c, n)
        var up = [Double](repeating: .nan, count: c.count), lo = up
        if c.count >= n {
            for i in (n - 1)..<c.count where !mid[i].isNaN {
                var ss = 0.0
                for j in (i - n + 1)...i { ss += (c[j] - mid[i]) * (c[j] - mid[i]) }
                let sd = (ss / Double(n)).squareRoot()
                up[i] = mid[i] + k * sd; lo[i] = mid[i] - k * sd
            }
        }
        return Bands(mid: mid, upper: up, lower: lo)
    }

    public struct Stoch { public let k: [Double]; public let d: [Double] }

    /// 슬로우 스토캐스틱 (14,3,3)
    public static func stochastic(_ h: [Double], _ l: [Double], _ c: [Double], n: Int = 14, smoothK: Int = 3, smoothD: Int = 3) -> Stoch {
        var raw = [Double](repeating: .nan, count: c.count)
        if c.count >= n {
            for i in (n - 1)..<c.count {
                var hh = -Double.infinity, ll = Double.infinity
                for j in (i - n + 1)...i { hh = max(hh, h[j]); ll = min(ll, l[j]) }
                raw[i] = hh - ll == 0 ? 50 : (c[i] - ll) / (hh - ll) * 100
            }
        }
        let k = smaNan(raw, smoothK)
        return Stoch(k: k, d: smaNan(k, smoothD))
    }

    public static func smaNan(_ v: [Double], _ n: Int) -> [Double] {
        var out = [Double](repeating: .nan, count: v.count)
        for i in v.indices where i >= n - 1 {
            var s = 0.0, ok = true
            for j in (i - n + 1)...i { if v[j].isNaN { ok = false; break }; s += v[j] }
            if ok { out[i] = s / Double(n) }
        }
        return out
    }

    public static func atr(_ h: [Double], _ l: [Double], _ c: [Double], _ n: Int = 14) -> [Double] {
        var out = [Double](repeating: .nan, count: c.count)
        if c.count <= n { return out }
        let tr: [Double] = c.indices.map { i in
            i == 0 ? h[0] - l[0] : max(h[i] - l[i], max(abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1])))
        }
        var a = tr[1...n].reduce(0, +) / Double(n)
        out[n] = a
        var i = n + 1
        while i < c.count { a = (a * Double(n - 1) + tr[i]) / Double(n); out[i] = a; i += 1 }
        return out
    }

    /// 분봉: 하루 단위 VWAP / 일봉 이상: 최근 n개 이동 VWAP
    public static func vwap(_ bars: [Bar], intraday: Bool, zone: TimeZone = TimeZone(identifier: "UTC")!, rollingN: Int = 20) -> [Double] {
        var out = [Double](repeating: .nan, count: bars.count)
        if intraday {
            var cal = Calendar(identifier: .gregorian); cal.timeZone = zone
            var pv = 0.0, vol = 0.0, day = -1
            for (i, b) in bars.enumerated() {
                let d = cal.ordinality(of: .day, in: .era, for: Date(timeIntervalSince1970: TimeInterval(b.t))) ?? 0
                if d != day { pv = 0; vol = 0; day = d }
                let tp = (b.h + b.l + b.c) / 3
                pv += tp * b.v; vol += b.v
                out[i] = vol > 0 ? pv / vol : tp
            }
        } else if bars.count >= rollingN {
            for i in (rollingN - 1)..<bars.count {
                var pv = 0.0, vol = 0.0
                for j in (i - rollingN + 1)...i { let b = bars[j]; pv += (b.h + b.l + b.c) / 3 * b.v; vol += b.v }
                out[i] = vol > 0 ? pv / vol : bars[i].c
            }
        }
        return out
    }

    public static func pivots(_ h: [Double], _ l: [Double], left: Int = 3, right: Int = 3) -> (highs: [Int], lows: [Int]) {
        var highs: [Int] = [], lows: [Int] = []
        if h.count < left + right + 1 { return (highs, lows) }
        for i in left..<(h.count - right) {
            var isH = true, isL = true
            for j in (i - left)...(i + right) where j != i {
                if h[j] > h[i] { isH = false }
                if l[j] < l[i] { isL = false }
            }
            if isH { highs.append(i) }
            if isL { lows.append(i) }
        }
        return (highs, lows)
    }

    public struct Level { public let price: Double; public let touches: Int }

    /// 고점/저점을 ATR 폭으로 묶어 지지·저항 가격대 산출
    public static func srLevels(_ bars: [Bar], atr: Double, lookback: Int = 160) -> [Level] {
        if bars.count < 20 || atr.isNaN || atr <= 0 { return [] }
        let sub = Array(bars.suffix(lookback))
        let h = sub.map(\.h), l = sub.map(\.l)
        let p = pivots(h, l, left: 3, right: 3)
        let pts = (p.highs.map { h[$0] } + p.lows.map { l[$0] }).sorted()
        if pts.isEmpty { return [] }
        var clusters: [[Double]] = []
        for x in pts {
            if let last = clusters.last, x - avg(last) <= atr * 0.6 { clusters[clusters.count - 1].append(x) }
            else { clusters.append([x]) }
        }
        return clusters.map { Level(price: avg($0), touches: $0.count) }
    }

    public struct VolNode { public let low: Double; public let high: Double; public let volume: Double; public var mid: Double { (low + high) / 2 } }

    public static func volumeProfile(_ bars: [Bar], bins: Int = 24, lookback: Int = 120) -> [VolNode] {
        let sub = Array(bars.suffix(lookback))
        if sub.count < 10 { return [] }
        let lo = sub.map(\.l).min()!, hi = sub.map(\.h).max()!
        if hi <= lo { return [] }
        let step = (hi - lo) / Double(bins)
        var vol = [Double](repeating: 0, count: bins)
        for b in sub {
            let tp = (b.h + b.l + b.c) / 3
            let idx = min(max(Int((tp - lo) / step), 0), bins - 1)
            vol[idx] += b.v
        }
        return (0..<bins).map { VolNode(low: lo + Double($0) * step, high: lo + Double($0 + 1) * step, volume: vol[$0]) }
    }

    public static func slope(_ v: [Double]) -> Double {
        let n = v.count
        if n < 2 { return 0 }
        let xm = Double(n - 1) / 2, ym = avg(v)
        var num = 0.0, den = 0.0
        for i in 0..<n { num += (Double(i) - xm) * (v[i] - ym); den += (Double(i) - xm) * (Double(i) - xm) }
        return den == 0 ? 0 : num / den
    }

    public struct TrendLine {
        public let i1: Int, p1: Double, i2: Int, p2: Double, up: Bool
        public func value(at i: Int) -> Double { p1 + (p2 - p1) * Double(i - i1) / Double(i2 - i1) }
    }

    public static func trendLines(_ bars: [Bar], lookback: Int = 120) -> [TrendLine] {
        if bars.count < 20 { return [] }
        let offset = max(0, bars.count - lookback)
        let sub = Array(bars[offset...])
        let h = sub.map(\.h), l = sub.map(\.l)
        let p = pivots(h, l, left: 4, right: 4)
        var out: [TrendLine] = []
        if p.lows.count >= 2 {
            let a = p.lows[p.lows.count - 2], b = p.lows[p.lows.count - 1]
            if l[b] > l[a] { out.append(TrendLine(i1: a + offset, p1: l[a], i2: b + offset, p2: l[b], up: true)) }
        }
        if p.highs.count >= 2 {
            let a = p.highs[p.highs.count - 2], b = p.highs[p.highs.count - 1]
            if h[b] < h[a] { out.append(TrendLine(i1: a + offset, p1: h[a], i2: b + offset, p2: h[b], up: false)) }
        }
        return out
    }

    /// 한국 주식 호가 단위 (2023.1 개편 기준)
    public static func krTick(_ p: Double) -> Double {
        switch p {
        case ..<2_000: return 1
        case ..<5_000: return 5
        case ..<20_000: return 10
        case ..<50_000: return 50
        case ..<200_000: return 100
        case ..<500_000: return 500
        default: return 1_000
        }
    }

    public static func roundPrice(_ p: Double, kr: Bool) -> Double {
        if p.isNaN || p <= 0 { return p }
        if kr { let t = krTick(p); return (p / t).rounded() * t }
        return (p * 100).rounded() / 100
    }

    static func avg(_ v: [Double]) -> Double { v.isEmpty ? .nan : v.reduce(0, +) / Double(v.count) }
}

/// 차트·분석 공통 지표 묶음
public final class IndicatorSet {
    public let bars: [Bar]
    public let c: [Double], h: [Double], l: [Double], v: [Double]
    public let ma: [Int: [Double]]
    public let volMa20: [Double]
    public let rsi: [Double]
    public let macd: Indicators.Macd
    public let bb: Indicators.Bands
    public let stoch: Indicators.Stoch
    public let atr: [Double]
    public let vwap: [Double]
    public let trendLines: [Indicators.TrendLine]

    public init(_ bars: [Bar], intraday: Bool, zone: TimeZone) {
        self.bars = bars
        c = bars.map(\.c); h = bars.map(\.h); l = bars.map(\.l); v = bars.map(\.v)
        var m: [Int: [Double]] = [:]
        for n in [5, 10, 20, 60, 120, 200] { m[n] = Indicators.sma(c, n) }
        ma = m
        volMa20 = Indicators.sma(v, 20)
        rsi = Indicators.rsi(c, 14)
        macd = Indicators.macd(c)
        bb = Indicators.bollinger(c)
        stoch = Indicators.stochastic(h, l, c)
        atr = Indicators.atr(h, l, c, 14)
        vwap = Indicators.vwap(bars, intraday: intraday, zone: zone)
        trendLines = Indicators.trendLines(bars)
    }
}
