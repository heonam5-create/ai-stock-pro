import SwiftUI
import AIStockCore

func num(_ s: String) -> Double? { Double(s.replacingOccurrences(of: ",", with: "").trimmingCharacters(in: .whitespaces)) }
func str(_ d: Double?) -> String { guard let d = d, d != 0 else { return "" }; return d.truncatingRemainder(dividingBy: 1) == 0 ? String(Int64(d)) : String(d) }

struct WatchlistView: View {
    @EnvironmentObject var m: AppModel
    @State private var sub = 0
    @Environment(\.scenePhase) var phase

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Header(subtitle: "관심 종목 · 보유 주식 · 알림") {
                    Button { m.refreshWatch(); m.reloadAlerts() } label: { Image(systemName: "arrow.clockwise").foregroundColor(AppColors.sub).frame(width: 44, height: 44) }
                }
                Picker("", selection: $sub) { Text("관심·보유 종목 (\(m.watchlist.count))").tag(0); Text("주가 알림 (\(m.alerts.filter(\.enabled).count))").tag(1) }
                    .pickerStyle(.segmented).padding(.horizontal, 12).padding(.vertical, 8)
                if sub == 0 { WatchTab() } else { AlertTab() }
                Disclaimer()
            }
        }
        .background(AppColors.bg)
        .onAppear { m.refreshWatch(); m.reloadAlerts() }
        // 화면이 보이는 동안 30초마다 시세 갱신
        .task(id: phase == .active) {
            guard phase == .active else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                if Task.isCancelled { break }
                m.refreshWatch()
            }
        }
    }
}

struct WatchTab: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        Text("종목 추가").font(.system(size: 14)).foregroundColor(AppColors.sub).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16)
        SearchField(placeholder: "추가할 종목 검색 (한글·초성·코드·티커)")
        SearchResultsView { r in m.addWatch(r); m.clearSearch() }

        let held = m.watchlist.filter { $0.qty > 0 && $0.avgPrice > 0 }
        let totals: (Double, Double, Bool) = held.reduce((0, 0, false)) { acc, w in
            guard let q = m.watchQuotes[w.symbol] else { return acc }
            if w.market == .US && m.fx == nil { return (acc.0, acc.1, true) }
            let rate = w.market == .KR ? 1 : (m.fx ?? 0)
            return (acc.0 + q.price * w.qty * rate, acc.1 + w.avgPrice * w.qty * rate, acc.2)
        }
        if totals.1 > 0 {
            SectionCard("보유 주식 합계 (원화 환산)") {
                let pl = totals.0 - totals.1
                KV("총 평가금액", Fmt.won(totals.0)); KV("총 매수금액", Fmt.won(totals.1))
                KV("총 평가손익", "\(Fmt.won(pl)) (\(Fmt.pct(pl / totals.1 * 100)))", changeColor(pl))
                KV("적용 환율", m.fx.map { "1달러 = \(Fmt.won($0)) (현재 환율, 매수 시점 환율 아님)" } ?? "환율 조회 실패")
                if totals.2 { Text("환율을 불러오지 못해 미국 주식은 합계에서 제외되었습니다.").font(.system(size: 12)).foregroundColor(AppColors.orange) }
            }
        }
        if m.watchLoading && m.watchQuotes.isEmpty { LoadingBox(text: "관심 종목 시세 불러오는 중…") }
        if m.watchlist.isEmpty { SectionCard("관심 종목") { EmptyText("등록된 관심 종목이 없습니다. 위에서 검색해 추가하거나, 종목 분석 화면의 ♡ 버튼을 누르세요.") } }
        ForEach(m.watchlist) { w in WatchCard(w: w) }
    }
}

struct WatchCard: View {
    @EnvironmentObject var m: AppModel
    let w: WatchItem
    @State private var edit = false
    @State private var calc = false
    var body: some View {
        let kr = w.market == .KR, q = m.watchQuotes[w.symbol]
        SectionCard(w.name, action: {
            Button { m.removeWatch(w.symbol) } label: { Image(systemName: "trash").foregroundColor(AppColors.sub).frame(width: 44, height: 44) }
        }) {
            Text(w.symbol + (q.map { " · \($0.statusText)" } ?? "")).font(.system(size: 13)).foregroundColor(AppColors.sub).lineLimit(1)
            if let q = q {
                HStack(alignment: .lastTextBaseline) {
                    Text(Fmt.price(q.price, kr: kr)).font(.system(size: 24, weight: .bold)).foregroundColor(q.unreliable ? AppColors.sub : changeColor(q.changePct))
                    Text(Fmt.pct(q.changePct)).foregroundColor(changeColor(q.changePct))
                }
                Text("기준 \(MarketClock.format(q.marketTime, w.market)) · \(q.source)\(q.fallback ? " (지연 가능 / 대체 데이터 사용 중)" : "") · 갱신 \(MarketClock.formatKstSec(q.fetchedAt))")
                    .font(.system(size: 12)).foregroundColor(AppColors.sub)
                if let e = q.ext { Text("\(e.label)\(e.active ? "(진행 중)" : "(종료)") \(Fmt.price(e.price, kr: kr)) \(Fmt.pct(e.changePct))").font(.system(size: 13)).foregroundColor(changeColor(e.changePct)) }
                if let wn = q.warning { Text("⚠ \(wn)").font(.system(size: 12)).foregroundColor(AppColors.orange) }
            } else {
                let err = m.watchErrors[w.symbol]
                Text(err ?? (m.watchLoading ? "시세 불러오는 중…" : "시세 없음")).font(.system(size: 14)).foregroundColor(err != nil ? AppColors.orange : AppColors.sub)
            }
            if w.qty > 0 && w.avgPrice > 0 {
                KV("보유 수량", "\(Fmt.num(w.qty, kr: true))주"); KV("평균 매수가", Fmt.price(w.avgPrice, kr: kr))
                if let q = q {
                    let eval = q.price * w.qty, cost = w.avgPrice * w.qty, pl = eval - cost
                    KV("평가금액", Fmt.price(eval, kr: kr))
                    KV("평가손익", "\(Fmt.price(pl, kr: kr)) (\(Fmt.pct(pl / cost * 100)))", changeColor(pl))
                    if let e = q.ext { let pl2 = e.price * w.qty - cost; KV("\(e.label) 기준", "\(Fmt.price(pl2, kr: kr)) (\(Fmt.pct(pl2 / cost * 100))) · 참고용", changeColor(pl2)) }
                    if !kr, let fx = m.fx { KV("원화 환산 손익", "\(Fmt.won(pl * fx)) (현재 환율 \(Fmt.won(fx)))", changeColor(pl)) }
                    Text("평가 기준: \(q.statusText) 가격 (\(MarketClock.format(q.marketTime, w.market)))\(q.unreliable ? " · ⚠ 시세 신뢰도 낮음" : "")")
                        .font(.system(size: 12)).foregroundColor(q.unreliable ? AppColors.orange : AppColors.sub)
                }
            }
            if let t = w.targetPrice { KV("목표 매도가", Fmt.price(t, kr: kr) + (q.map { " (\(Fmt.pct((t / $0.price - 1) * 100)))" } ?? "")) }
            if let s = w.stopPrice { KV("손절가", Fmt.price(s, kr: kr) + (q.map { " (\(Fmt.pct((s / $0.price - 1) * 100)))" } ?? "")) }
            HStack(spacing: 6) {
                Button("분석") { m.open(w.ref) }.buttonStyle(.bordered).frame(maxWidth: .infinity)
                Button("보유 입력") { edit = true }.buttonStyle(.bordered).frame(maxWidth: .infinity)
                Button("계산기") { calc = true }.buttonStyle(.bordered).frame(maxWidth: .infinity)
            }.frame(minHeight: 44)
        }
        .sheet(isPresented: $edit) { EditHoldingSheet(w: w) { m.updateWatch($0); edit = false } }
        .sheet(isPresented: $calc) { CalcSheet(w: w, current: q?.price, kr: kr) }
    }
}

struct NumField: View {
    let label: String
    @Binding var text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.system(size: 13)).foregroundColor(.secondary)
            TextField(label, text: $text).keyboardType(.decimalPad).textFieldStyle(.roundedBorder).font(.system(size: 17))
        }
    }
}

struct EditHoldingSheet: View {
    let w: WatchItem
    let onSave: (WatchItem) -> Void
    @State var qty = ""
    @State var avg = ""
    @State var target = ""
    @State var stop = ""
    @Environment(\.dismiss) var dismiss
    var body: some View {
        NavigationStack {
            Form {
                NumField(label: "보유 수량 (주)", text: $qty)
                NumField(label: "평균 매수가 (\(w.market == .KR ? "원" : "달러"))", text: $avg)
                NumField(label: "목표 매도가 (선택)", text: $target)
                NumField(label: "손절가 (선택)", text: $stop)
            }
            .navigationTitle("\(w.name) 보유 정보").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("취소") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("저장") { var n = w; n.qty = num(qty) ?? 0; n.avgPrice = num(avg) ?? 0; n.targetPrice = num(target); n.stopPrice = num(stop); onSave(n) }
                }
            }
        }
        .onAppear { qty = str(w.qty); avg = str(w.avgPrice); target = str(w.targetPrice); stop = str(w.stopPrice) }
    }
}

/// 추가 매수 평균단가(분할매수) / 분할 매도 수익 계산기
struct CalcSheet: View {
    let w: WatchItem, current: Double?, kr: Bool
    @State var mode = 0
    @State var q1 = ""
    @State var p1 = ""
    @State var q2 = ""
    @State var p2 = ""
    @Environment(\.dismiss) var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Picker("", selection: $mode) { Text("분할매수 평단").tag(0); Text("분할매도 수익").tag(1) }.pickerStyle(.segmented)
                NumField(label: "현재 보유 수량", text: $q1)
                NumField(label: "현재 평균단가", text: $p1)
                NumField(label: mode == 0 ? "추가 매수 수량" : "매도 수량", text: $q2)
                NumField(label: mode == 0 ? "추가 매수 가격" : "매도 가격", text: $p2)
                Section("결과") {
                    if let a = num(q1), let b = num(p1), let c = num(q2), let d = num(p2), c > 0, b > 0 {
                        if mode == 0 {
                            let nq = a + c, na = (a * b + c * d) / nq
                            KV("매수 후 수량", "\(Fmt.num(nq, kr: true))주"); KV("새 평균단가", Fmt.price(na, kr: kr), AppColors.gold)
                            KV("평단 변화", Fmt.pct((na / b - 1) * 100)); KV("추가 투입금", Fmt.price(c * d, kr: kr))
                        } else if c > a {
                            Text("매도 수량이 보유 수량보다 많습니다.").foregroundColor(AppColors.orange)
                        } else {
                            let profit = (d - b) * c
                            KV("실현 손익", Fmt.price(profit, kr: kr), changeColor(profit)); KV("수익률", Fmt.pct((d / b - 1) * 100), changeColor(profit))
                            KV("매도 금액", Fmt.price(c * d, kr: kr)); KV("잔여 수량", "\(Fmt.num(a - c, kr: true))주 (평단 \(Fmt.price(b, kr: kr)) 유지)")
                            Text("※ 세금·수수료 제외 금액").font(.system(size: 12)).foregroundColor(.secondary)
                        }
                    } else { Text("숫자를 모두 입력하면 결과가 표시됩니다.").foregroundColor(.secondary) }
                }
            }
            .navigationTitle("\(w.name) 계산기").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("닫기") { dismiss() } } }
        }
        .onAppear { q1 = str(w.qty); p1 = str(w.avgPrice); p2 = current.map { kr ? String(Int64($0)) : String(format: "%.2f", $0) } ?? "" }
    }
}

struct AlertTab: View {
    @EnvironmentObject var m: AppModel
    @State private var adding = false
    @State private var checking: String?
    var body: some View {
        Group { content }.sheet(isPresented: $adding) { AddAlertSheet() }
    }
    @ViewBuilder var content: some View {
        SectionCard("알림 동작 안내 (iPhone)") {
            Text("• 앱을 열어 둔 동안은 시세 새로고침 때마다 즉시 확인합니다.\n• 앱을 닫으면 iOS 백그라운드 새로고침으로 확인하며, 실행 시점은 iOS가 정합니다 (보통 15분~수 시간, 저전력 모드·사용 패턴에 따라 실행되지 않을 수 있음).\n• 24시간 실시간 감시가 필요하면 서버 감시 + 푸시(APNs)가 필요하며 이 앱에는 포함되어 있지 않습니다 (설치 설명서 참고).\n• 가격 도달형 알림은 한 번 울리면 자동으로 꺼집니다. 급등락·거래량 알림은 장중에만, 6시간 간격으로 울립니다.\n• 지연·불일치 시세로는 알림을 보내지 않습니다.")
                .font(.system(size: 13)).foregroundColor(AppColors.sub)
            HStack {
                Button("+ 알림 추가") { adding = true }.buttonStyle(.bordered).frame(maxWidth: .infinity, minHeight: 46)
                Button("지금 확인") { Task { checking = "확인 중…"; let n = await AlertMonitor.checkNow(); checking = "확인 완료 · 알림 \(n)건"; m.reloadAlerts() } }
                    .buttonStyle(.bordered).frame(maxWidth: .infinity, minHeight: 46)
            }
            if let c = checking { Text(c).font(.system(size: 13)).foregroundColor(AppColors.sub) }
        }
        if m.alerts.isEmpty { SectionCard("등록된 알림") { EmptyText("등록된 알림이 없습니다. 종목 분석 후 '이 가격으로 알림 설정' 버튼으로도 추가할 수 있습니다.") } }
        ForEach(m.alerts) { a in
            let kr = a.symbol.hasSuffix(".KS") || a.symbol.hasSuffix(".KQ")
            SectionCard(a.name, action: {
                HStack {
                    Toggle("", isOn: Binding(get: { a.enabled }, set: { _ in m.toggleAlert(a.id) })).labelsHidden()
                    Button { m.removeAlert(a.id) } label: { Image(systemName: "trash").foregroundColor(AppColors.sub).frame(width: 44, height: 44) }
                }
            }) {
                Text(a.type.label).foregroundColor(AppColors.text)
                HStack {
                    Pill(a.type.unit == "가격" ? Fmt.price(a.value, kr: kr) : "\(Fmt.d(a.value, 1))\(a.type.unit)", AppColors.gold)
                    Text(a.lastFired > .distantPast ? "마지막 알림 \(MarketClock.formatKstSec(a.lastFired))" : a.enabled ? "감시 중" : "꺼짐").font(.system(size: 13)).foregroundColor(AppColors.sub)
                }
            }
        }
    }
}

struct AddAlertSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) var dismiss
    @State var sym: String = ""
    @State var type: AlertType = .buyPrice
    @State var value = ""
    var body: some View {
        let cands: [(String, String)] = {
            var out: [(String, String)] = []
            if let s = m.selected { out.append((s.symbol, s.displayName)) }
            for w in m.watchlist where !out.contains(where: { $0.0 == w.symbol }) { out.append((w.symbol, w.name)) }
            return out
        }()
        NavigationStack {
            Form {
                if cands.isEmpty { Text("먼저 관심 종목을 등록하거나 종목을 분석하세요.").foregroundColor(AppColors.orange) }
                Picker("종목", selection: $sym) { ForEach(cands, id: \.0) { Text($0.1).tag($0.0) } }
                Picker("조건", selection: $type) { ForEach(AlertType.allCases, id: \.self) { Text($0.label).tag($0) } }.pickerStyle(.inline)
                NumField(label: type.unit == "%" ? "등락률 기준 (%) 예: 5" : type.unit == "배" ? "평균 대비 배수 예: 2" : "가격", text: $value)
                if let q = m.watchQuotes[sym] ?? (m.quote.value?.ref.symbol == sym ? m.quote.value : nil) {
                    Text("현재가 \(Fmt.price(q.price, kr: q.ref.market == .KR))").foregroundColor(.secondary)
                }
            }
            .navigationTitle("주가 알림 추가").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("취소") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("추가") {
                        if let v = num(value), let c = cands.first(where: { $0.0 == sym }) { m.addAlert(c.0, c.1, type, v); dismiss() }
                    }.disabled(num(value) == nil || sym.isEmpty)
                }
            }
        }
        .onAppear { if sym.isEmpty { sym = cands.first?.0 ?? "" } }
    }
}
