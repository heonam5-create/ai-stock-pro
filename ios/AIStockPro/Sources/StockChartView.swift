import SwiftUI
import AIStockCore

enum SubPanel: String, CaseIterable { case volume = "거래량", rsi = "RSI", macd = "MACD", stoch = "스토캐스틱", atr = "ATR" }

struct ChartOverlays: Equatable {
    var ma: Set<Int> = [5, 20, 60, 120]
    var bb = false, vwap = false, levels = true, zones = true, trend = true
}

/// 차트에 그릴 분석 결과 (지지/저항, 매수/매도 구간, 손절선)
struct ChartLevels: Equatable {
    var supports: [Double], resistances: [Double]
    var buyHigh: Double?, buyLow: Double?, sellLow: Double?, sellHigh: Double?, stop: Double?
}

let maColors: [Int: Color] = [5: Color(hex: 0xFACC15), 10: Color(hex: 0xF472B6), 20: Color(hex: 0xF97316),
                              60: Color(hex: 0x22C55E), 120: Color(hex: 0xA78BFA), 200: Color(hex: 0x94A3B8)]

/// 두 손가락 확대·축소, 한 손가락 좌우 이동, 탭(십자선 정보) 캔들 차트
struct StockChartView: View {
    let bars: [Bar]
    let intraday: Bool
    let zone: TimeZone
    let kr: Bool
    let overlays: ChartOverlays
    let sub: SubPanel
    let levels: ChartLevels?

    @State private var visible: Double = 100
    @State private var baseVisible: Double = 100
    @State private var offset: Double = 0          // 오른쪽 끝에서 몇 봉 떨어졌는지
    @State private var baseOffset: Double = 0
    @State private var selected: Int? = nil
    @State private var ind: IndicatorSet? = nil

    let axisW: CGFloat = 64

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width - axisW
            Canvas { ctx, size in draw(ctx: &ctx, size: size) }
                .contentShape(Rectangle())
                .gesture(
                    MagnificationGesture()
                        .onChanged { s in visible = min(max(baseVisible / s, min(15, Double(bars.count))), Double(bars.count)) }
                        .onEnded { _ in baseVisible = visible }
                        .simultaneously(with:
                            DragGesture(minimumDistance: 10)
                                .onChanged { g in
                                    guard abs(g.translation.width) > abs(g.translation.height) else { return }
                                    let bw = w / visible
                                    offset = min(max(baseOffset + g.translation.width / bw, 0), max(0, Double(bars.count) - visible))
                                }
                                .onEnded { _ in baseOffset = offset })
                )
                .onTapGesture { loc in
                    let bw = w / visible
                    let start = startIndex
                    let i = start + Int(loc.x / bw)
                    selected = (i >= 0 && i < bars.count && loc.x < w) ? (selected == i ? nil : i) : nil
                }
        }
        .frame(height: 400)
        .onAppear { setup() }
        .onChange(of: bars.count) { _ in setup() }
    }

    func setup() {
        ind = IndicatorSet(bars, intraday: intraday, zone: zone)
        visible = Double(min(intraday ? 120 : 100, bars.count)); baseVisible = visible
        offset = 0; baseOffset = 0; selected = nil
    }

    var startIndex: Int { max(0, bars.count - Int(visible.rounded()) - Int(offset.rounded())) }
    var endIndex: Int { min(bars.count, startIndex + Int(visible.rounded())) }

    func dateText(_ t: Int64) -> String { MarketClock.fmt(zone, intraday ? "MM/dd HH:mm" : "yy/MM/dd").string(from: Date(timeIntervalSince1970: TimeInterval(t))) }

    func draw(ctx: inout GraphicsContext, size: CGSize) {
        guard let ind = ind, bars.count >= 2 else { return }
        let s = startIndex, e = endIndex
        if e - s < 2 { return }
        let w = size.width - axisW
        let mainH = size.height * 0.68, subTop = mainH + 18, subH = size.height - subTop - 14
        let bw = w / CGFloat(e - s)

        // 가격 범위
        var hi = -Double.infinity, lo = Double.infinity
        for i in s..<e { hi = max(hi, bars[i].h); lo = min(lo, bars[i].l) }
        if overlays.bb { for i in s..<e where !ind.bb.upper[i].isNaN { hi = max(hi, ind.bb.upper[i]); lo = min(lo, ind.bb.lower[i]) } }
        if let lv = levels, overlays.levels || overlays.zones {
            for p in (lv.supports + lv.resistances + [lv.stop, lv.buyLow, lv.sellHigh].compactMap { $0 }) where p > lo * 0.85 && p < hi * 1.15 { hi = max(hi, p); lo = min(lo, p) }
        }
        let pad = (hi - lo) * 0.06; hi += pad; lo -= pad
        func y(_ p: Double) -> CGFloat { CGFloat((hi - p) / (hi - lo)) * mainH }
        func x(_ i: Int) -> CGFloat { (CGFloat(i - s) + 0.5) * bw }

        // 격자·가격축
        for k in 0...4 {
            let p = lo + (hi - lo) * Double(k) / 4
            let yy = y(p)
            ctx.stroke(Path { $0.move(to: CGPoint(x: 0, y: yy)); $0.addLine(to: CGPoint(x: w, y: yy)) }, with: .color(AppColors.line), lineWidth: 0.5)
            ctx.draw(Text(Fmt.num(p, kr: kr)).font(.system(size: 10)).foregroundColor(AppColors.sub), at: CGPoint(x: w + 4, y: yy), anchor: .leading)
        }

        // 매수/매도 구간
        if let lv = levels, overlays.zones {
            if let a = lv.buyHigh, let b = lv.buyLow { ctx.fill(Path(CGRect(x: 0, y: y(a), width: w, height: max(1, y(b) - y(a)))), with: .color(AppColors.up.opacity(0.12))) }
            if let a = lv.sellHigh, let b = lv.sellLow { ctx.fill(Path(CGRect(x: 0, y: y(a), width: w, height: max(1, y(b) - y(a)))), with: .color(AppColors.down.opacity(0.12))) }
        }
        // 지지·저항·손절
        if let lv = levels, overlays.levels {
            let dash = StrokeStyle(lineWidth: 1.5, dash: [6, 4])
            for p in lv.supports { ctx.stroke(hline(y(p), w), with: .color(AppColors.teal), style: dash) }
            for p in lv.resistances { ctx.stroke(hline(y(p), w), with: .color(AppColors.orange), style: dash) }
            if let st = lv.stop { ctx.stroke(hline(y(st), w), with: .color(AppColors.stop), style: StrokeStyle(lineWidth: 1.5, dash: [3, 3])) }
        }
        // 볼린저
        if overlays.bb {
            for (arr, op) in [(ind.bb.upper, 0.7), (ind.bb.mid, 0.4), (ind.bb.lower, 0.7)] { ctx.stroke(series(arr, s, e, x, y), with: .color(AppColors.purple.opacity(op)), lineWidth: 1) }
        }
        // 캔들
        for i in s..<e {
            let b = bars[i]
            let up = b.c >= b.o
            let col = up ? AppColors.up : AppColors.down
            ctx.stroke(Path { $0.move(to: CGPoint(x: x(i), y: y(b.h))); $0.addLine(to: CGPoint(x: x(i), y: y(b.l))) }, with: .color(col), lineWidth: 1)
            let top = y(max(b.o, b.c)), bot = y(min(b.o, b.c))
            ctx.fill(Path(CGRect(x: x(i) - bw * 0.35, y: top, width: max(1, bw * 0.7), height: max(1, bot - top))), with: .color(col))
        }
        // 이동평균
        for m in overlays.ma.sorted() { if let arr = ind.ma[m] { ctx.stroke(series(arr, s, e, x, y), with: .color(maColors[m] ?? .white), lineWidth: 1.2) } }
        if overlays.vwap { ctx.stroke(series(ind.vwap, s, e, x, y), with: .color(Color(hex: 0x38BDF8)), style: StrokeStyle(lineWidth: 1.2, dash: [4, 3])) }
        // 추세선
        if overlays.trend {
            for t in ind.trendLines {
                let a = max(t.i1, s), b = e - 1
                if b <= a { continue }
                ctx.stroke(Path { $0.move(to: CGPoint(x: x(a), y: y(t.value(at: a)))); $0.addLine(to: CGPoint(x: x(b), y: y(t.value(at: b)))) },
                           with: .color(t.up ? AppColors.green : AppColors.stop), lineWidth: 1.4)
            }
        }
        // 날짜
        for k in [s, (s + e) / 2, e - 1] {
            ctx.draw(Text(dateText(bars[k].t)).font(.system(size: 10)).foregroundColor(AppColors.sub), at: CGPoint(x: min(max(x(k), 30), w - 30), y: mainH + 8))
        }

        // 보조 지표
        drawSub(&ctx, ind, s, e, x, top: subTop, h: subH, w: w)

        // 십자선
        if let i = selected, i >= s, i < e {
            let b = bars[i]
            ctx.stroke(Path { $0.move(to: CGPoint(x: x(i), y: 0)); $0.addLine(to: CGPoint(x: x(i), y: size.height)) }, with: .color(.white.opacity(0.5)), lineWidth: 0.8)
            let chg = i > 0 ? (b.c / bars[i - 1].c - 1) * 100 : Double.nan
            let info = "\(dateText(b.t))  시 \(Fmt.num(b.o, kr: kr)) 고 \(Fmt.num(b.h, kr: kr)) 저 \(Fmt.num(b.l, kr: kr)) 종 \(Fmt.num(b.c, kr: kr)) \(Fmt.pct(chg))"
            let tr = ctx.resolve(Text(info).font(.system(size: 11, weight: .bold)).foregroundColor(.white))
            let sz = tr.measure(in: CGSize(width: w, height: 30))
            ctx.fill(Path(roundedRect: CGRect(x: 4, y: 4, width: sz.width + 10, height: sz.height + 6), cornerRadius: 6), with: .color(.black.opacity(0.7)))
            ctx.draw(tr, at: CGPoint(x: 9, y: 7), anchor: .topLeading)
        }
    }

    func hline(_ yy: CGFloat, _ w: CGFloat) -> Path { Path { $0.move(to: CGPoint(x: 0, y: yy)); $0.addLine(to: CGPoint(x: w, y: yy)) } }

    func series(_ arr: [Double], _ s: Int, _ e: Int, _ x: (Int) -> CGFloat, _ y: (Double) -> CGFloat) -> Path {
        var p = Path(); var started = false
        for i in s..<e where i < arr.count {
            let v = arr[i]
            if v.isNaN { started = false; continue }
            let pt = CGPoint(x: x(i), y: y(v))
            if started { p.addLine(to: pt) } else { p.move(to: pt); started = true }
        }
        return p
    }

    func drawSub(_ ctx: inout GraphicsContext, _ ind: IndicatorSet, _ s: Int, _ e: Int, _ x: (Int) -> CGFloat, top: CGFloat, h: CGFloat, w: CGFloat) {
        ctx.stroke(Path { $0.move(to: CGPoint(x: 0, y: top)); $0.addLine(to: CGPoint(x: w, y: top)) }, with: .color(AppColors.line), lineWidth: 0.5)
        let bw = w / CGFloat(e - s)
        func range(_ arrs: [[Double]], fixed: (Double, Double)? = nil) -> (Double, Double) {
            if let f = fixed { return f }
            var a = -Double.infinity, b = Double.infinity
            for arr in arrs { for i in s..<e where !arr[i].isNaN { a = max(a, arr[i]); b = min(b, arr[i]) } }
            if !a.isFinite { return (1, 0) }
            return (a == b ? a + 1 : a, b)
        }
        func yy(_ v: Double, _ r: (Double, Double)) -> CGFloat { top + CGFloat((r.0 - v) / (r.0 - r.1)) * h }
        var label = sub.rawValue
        switch sub {
        case .volume:
            let r = range([ind.v]); let rr = (r.0, 0.0)
            for i in s..<e {
                let b = bars[i]
                ctx.fill(Path(CGRect(x: x(i) - bw * 0.35, y: yy(b.v, rr), width: max(1, bw * 0.7), height: max(0, top + h - yy(b.v, rr)))),
                         with: .color((b.c >= b.o ? AppColors.up : AppColors.down).opacity(0.7)))
            }
            ctx.stroke(series(ind.volMa20, s, e, x, { yy($0, rr) }), with: .color(AppColors.gold), lineWidth: 1)
            label += " (20일 평균)"
        case .rsi:
            let r = (100.0, 0.0)
            for lv in [70.0, 30.0] { ctx.stroke(hline(yy(lv, r), w), with: .color(AppColors.sub.opacity(0.5)), style: StrokeStyle(lineWidth: 0.8, dash: [3, 3])) }
            ctx.stroke(series(ind.rsi, s, e, x, { yy($0, r) }), with: .color(AppColors.purple), lineWidth: 1.3)
            if let last = ind.rsi[e - 1].isNaN ? nil : ind.rsi[e - 1] { label += " \(Fmt.d(last, 1))" }
        case .macd:
            let r = range([ind.macd.macd, ind.macd.signal, ind.macd.hist])
            for i in s..<e where !ind.macd.hist[i].isNaN {
                let v = ind.macd.hist[i], z = yy(0, r), v2 = yy(v, r)
                ctx.fill(Path(CGRect(x: x(i) - bw * 0.35, y: min(z, v2), width: max(1, bw * 0.7), height: abs(z - v2))), with: .color((v >= 0 ? AppColors.up : AppColors.down).opacity(0.6)))
            }
            ctx.stroke(series(ind.macd.macd, s, e, x, { yy($0, r) }), with: .color(AppColors.gold), lineWidth: 1.2)
            ctx.stroke(series(ind.macd.signal, s, e, x, { yy($0, r) }), with: .color(AppColors.accent), lineWidth: 1.2)
        case .stoch:
            let r = (100.0, 0.0)
            for lv in [80.0, 20.0] { ctx.stroke(hline(yy(lv, r), w), with: .color(AppColors.sub.opacity(0.5)), style: StrokeStyle(lineWidth: 0.8, dash: [3, 3])) }
            ctx.stroke(series(ind.stoch.k, s, e, x, { yy($0, r) }), with: .color(AppColors.gold), lineWidth: 1.2)
            ctx.stroke(series(ind.stoch.d, s, e, x, { yy($0, r) }), with: .color(AppColors.accent), lineWidth: 1.2)
        case .atr:
            let r = range([ind.atr])
            ctx.stroke(series(ind.atr, s, e, x, { yy($0, r) }), with: .color(AppColors.teal), lineWidth: 1.3)
        }
        ctx.draw(Text(label).font(.system(size: 10)).foregroundColor(AppColors.sub), at: CGPoint(x: 4, y: top + 2), anchor: .topLeading)
    }
}
