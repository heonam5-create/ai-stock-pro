import SwiftUI
import AIStockCore

struct AnalysisView: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.scenePhase) var phase

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Header(subtitle: m.selected.map { "\($0.displayName) · \($0.symbol)" } ?? "종목을 검색하세요") {
                    if let r = m.selected {
                        Button { m.toggleWatch(r) } label: {
                            Image(systemName: m.isWatched(r.symbol) ? "heart.fill" : "heart").foregroundColor(m.isWatched(r.symbol) ? AppColors.up : AppColors.sub).frame(width: 44, height: 44)
                        }
                    }
                }
                SearchField()
                SearchResultsView()
                if let ref = m.selected { content(ref) } else {
                    EmptyText("   위 검색창에 종목명(한글/영문/초성), 6자리 코드, 티커를 입력하세요.")
                    Disclaimer()
                }
            }
        }
        .background(AppColors.bg)
        // 화면이 보이고 앱이 활성일 때만 자동 새로고침 (공급원 호출 제한 고려)
        .task(id: "\(m.selected?.symbol ?? "")|\(phase == .active)") {
            guard phase == .active, m.selected != nil else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(m.nextRefreshSeconds * 1_000_000_000))
                if Task.isCancelled { break }
                await m.refreshQuote()
            }
        }
    }

    @ViewBuilder func content(_ ref: StockRef) -> some View {
        let kr = ref.market == .KR
        SectionCard("현재가", action: {
            HStack {
                if m.refreshing { Text("갱신 중…").font(.system(size: 12)).foregroundColor(AppColors.sub) }
                Button { Task { await m.refreshQuote() } } label: { Image(systemName: "arrow.clockwise").foregroundColor(AppColors.accent).frame(width: 44, height: 44) }
                    .accessibilityLabel("시세 새로고침")
            }
        }) {
            switch m.quote {
            case .idle, .loading: LoadingBox(text: "시세 불러오는 중…")
            case .failure(let e): ErrorBox(message: e) { Task { await m.loadQuote() } }
            case .success(let q): QuoteBlock(q: q, nextSec: m.nextRefreshSeconds)
            }
        }

        SectionCard("차트") {
            ChipRow { ForEach(Interval.allCases, id: \.self) { i in Chip(text: i.label, selected: m.interval == i, enabled: m.unsupported[i] == nil) { m.selectInterval(i) } } }
            if let u = m.unsupported.values.first { Text("※ \(u) (비활성화됨)").font(.system(size: 12)).foregroundColor(AppColors.orange) }
            ChipRow {
                ForEach([5, 10, 20, 60, 120, 200], id: \.self) { n in
                    Chip(text: "MA\(n)", selected: m.overlays.ma.contains(n)) { if m.overlays.ma.contains(n) { m.overlays.ma.remove(n) } else { m.overlays.ma.insert(n) } }
                }
                Chip(text: "볼린저", selected: m.overlays.bb) { m.overlays.bb.toggle() }
                Chip(text: "VWAP", selected: m.overlays.vwap) { m.overlays.vwap.toggle() }
                Chip(text: "추세선", selected: m.overlays.trend) { m.overlays.trend.toggle() }
                Chip(text: "지지/저항", selected: m.overlays.levels) { m.overlays.levels.toggle() }
                Chip(text: "매매구간", selected: m.overlays.zones) { m.overlays.zones.toggle() }
            }
            ChipRow { ForEach(SubPanel.allCases, id: \.self) { p in Chip(text: p.rawValue, selected: m.subPanel == p) { m.subPanel = p } } }
            switch m.chart {
            case .idle, .loading: LoadingBox(text: "차트 불러오는 중…").frame(height: 400)
            case .failure(let e): ErrorBox(message: e) { Task { await m.loadChart() } }
            case .success(let cd):
                StockChartView(bars: cd.bars, intraday: cd.interval.intraday, zone: TimeZone(identifier: cd.meta.timezone) ?? MarketClock.seoul,
                               kr: kr, overlays: m.overlays, sub: m.subPanel, levels: m.chartLevels)
                    .id("\(ref.symbol)|\(cd.interval.rawValue)|\(cd.bars.count)")
                Text("두 손가락으로 확대·축소, 좌우로 밀어 이동, 탭하면 해당 봉 정보. " +
                     (m.chartLevels == nil ? "AI 분석 후 지지(청록)/저항(주황)/손절(빨강 점선)·매수구간(붉은 띠)·매도구간(푸른 띠)이 표시됩니다." : "청록=지지, 주황=저항, 빨강 점선=손절, 붉은 띠=매수구간, 푸른 띠=매도구간."))
                    .font(.system(size: 12)).foregroundColor(AppColors.sub)
                HStack { ForEach(m.overlays.ma.sorted(), id: \.self) { n in Text("MA\(n)").font(.system(size: 12)).foregroundColor(maColors[n] ?? .white) } }
                Text("데이터: \(cd.bars.count)개 봉 · \(cd.source) · 마지막 봉 \(MarketClock.format(cd.bars.last!.t, ref.market))").font(.system(size: 12)).foregroundColor(AppColors.sub)
            }
        }

        PrimaryButton(title: m.analysis.isLoading ? "AI 종합 분석 중…" : "AI 종합 분석", color: AppColors.up, enabled: !m.analysis.isLoading) { m.runAnalysis() }
            .padding(.horizontal, 12).padding(.vertical, 6)

        switch m.analysis {
        case .idle: EmptyText("   버튼을 누르면 일봉 2년·뉴스·일정을 종합해 10개 항목을 분석합니다.")
        case .loading: LoadingBox(text: "시세·뉴스 수집 및 분석 중… (5~15초)")
        case .failure(let e): ErrorBox(message: e) { m.runAnalysis() }
        case .success(let fa):
            PriceGuard(q: fa.quote)
            VerdictCard(r: fa.result, q: fa.quote, barsSource: fa.barsSource)
            FactorsCard(r: fa.result)
            if fa.quote.unreliable {
                SectionCard("매수·매도 전략") {
                    Text("⚠ 시세 신뢰도가 낮아(지연·오류·공급원 불일치) 매수가·목표가·손절가를 표시하지 않습니다. 시세를 새로고침한 뒤 다시 분석하세요.")
                        .font(.system(size: 14)).foregroundColor(AppColors.orange)
                    PrimaryButton(title: "다시 분석") { m.runAnalysis() }
                }
            } else if let s = fa.result.strategy { StrategyCard(s: s, r: fa.result, kr: kr) }
            LevelsCard(r: fa.result, kr: kr)
            if !fa.quote.unreliable { AlertShortcuts(r: fa.result, q: fa.quote) }
            ExternalAiCard()
            if let ne = fa.newsError { Text("   뉴스 조회 실패: \(ne)").font(.system(size: 13)).foregroundColor(AppColors.orange) }
            SectionCard("주요 일정") {
                if fa.events.isEmpty { EmptyText("확인된 일정이 없습니다.") }
                ForEach(Array(fa.events.prefix(5).enumerated()), id: \.offset) { _, e in KV("\(e.date) (D-\(e.daysLeft))", "\(e.title) · \(e.detail)") }
                if kr { Text("한국 기업 실적 발표일은 무료 API가 없어 표시하지 않습니다 (DART 전자공시 확인).").font(.system(size: 12)).foregroundColor(AppColors.sub) }
                else if m.prefs.finnhubKey.isEmpty { Text("설정에서 무료 Finnhub 키를 등록하면 미국 실적 발표일이 표시됩니다.").font(.system(size: 12)).foregroundColor(AppColors.sub) }
            }
        }

        SectionCard("종목 뉴스 (최근 7일 · 호재/악재 자동 분류)", action: {
            Button { Task { await m.loadNews(force: true) } } label: { Image(systemName: "arrow.clockwise").foregroundColor(AppColors.sub).frame(width: 44, height: 44) }
        }) {
            switch m.news {
            case .idle, .loading: LoadingBox()
            case .failure(let e): ErrorBox(message: e) { Task { await m.loadNews(force: true) } }
            case .success(let list):
                if list.isEmpty { EmptyText("최근 7일 내 관련 뉴스가 없습니다.") }
                let pos = list.filter { $0.sentiment == .positive }.count, neg = list.filter { $0.sentiment == .negative }.count
                if !list.isEmpty { Text("호재 \(pos) · 악재 \(neg) · 중립 \(list.count - pos - neg) (제목 키워드 자동 분류)").font(.system(size: 12)).foregroundColor(AppColors.sub) }
                ForEach(list.prefix(15)) { NewsRow(n: $0) }
                Text("출처: 네이버 금융 뉴스 · Google 뉴스 RSS").font(.system(size: 12)).foregroundColor(AppColors.sub)
            }
        }
        Disclaimer()
    }
}

func freshColor(_ q: Quote) -> Color {
    switch q.freshness { case .realtime: return AppColors.green; case .delayed: return AppColors.accent; case .closed: return AppColors.sub; case .stale, .error: return AppColors.orange }
}

struct QuoteBlock: View {
    let q: Quote
    let nextSec: Double
    var body: some View {
        let kr = q.ref.market == .KR
        VStack(alignment: .leading, spacing: 6) {
            if let w = q.warning { Text("⚠ \(w)").font(.system(size: 14, weight: .semibold)).foregroundColor(AppColors.orange) }
            HStack(alignment: .lastTextBaseline, spacing: 10) {
                Text(Fmt.price(q.price, kr: kr)).font(.system(size: 30, weight: .bold)).foregroundColor(q.unreliable ? AppColors.sub : changeColor(q.changePct))
                    .minimumScaleFactor(0.6).lineLimit(1)
                Text("\(q.change.map { ($0 > 0 ? "▲" : $0 < 0 ? "▼" : "") + Fmt.num(abs($0), kr: kr) } ?? "-")  \(Fmt.pct(q.changePct))")
                    .font(.system(size: 16)).foregroundColor(changeColor(q.changePct))
            }
            ChipRow {
                Pill(q.freshness.label, freshColor(q))
                if q.freshness != .closed { Pill(q.session.label, q.marketOpen ? AppColors.green : AppColors.sub) }
                Pill(q.source, AppColors.accent)
                if q.fallback { Pill("지연 가능 / 대체 데이터 사용 중", AppColors.orange) }
            }
            if let e = q.ext {
                KV("\(e.label) (\(e.active ? "진행 중" : "종료"))", "\(Fmt.price(e.price, kr: kr))  \(Fmt.pct(e.changePct))\n\(MarketClock.format(e.time, q.ref.market))", changeColor(e.changePct))
            }
            if !kr {
                Text("※ 국내 증권사 '주간거래'(미국 야간 대체거래소) 가격은 무료 공급원이 제공하지 않아 표시하지 않습니다. 증권사 앱 가격과 다를 수 있습니다.")
                    .font(.system(size: 12)).foregroundColor(AppColors.sub)
            }
            KV("체결 기준 시각", MarketClock.format(q.marketTime, q.ref.market))
            KV("마지막 갱신", "\(MarketClock.formatKstSec(q.fetchedAt)) · 자동 \(Int(nextSec))초")
            KV("데이터 제공", q.delayText)
            if let c = q.check { KV("교차 검증", c.message, c.mismatch ? AppColors.orange : AppColors.text) }
            KV("종목명", q.ref.displayName + (q.ref.nameKr != nil && q.ref.name != q.ref.nameKr ? " (\(q.ref.name))" : ""))
            KV("티커 · 거래소", "\(q.ref.symbol) · \(q.ref.exchange.isEmpty ? "-" : q.ref.exchange)")
            KV("거래량", Fmt.volume(q.volume, kr: kr))
            KV("시가총액", q.marketCap.map { Fmt.marketCap($0, kr: kr) + " (\(q.marketCapSource ?? "-"))" } ?? "미제공")
            KV("당일 고가 / 저가", "\(Fmt.price(q.dayHigh, kr: kr)) / \(Fmt.price(q.dayLow, kr: kr))")
            KV("52주 최고 / 최저", "\(Fmt.price(q.high52, kr: kr)) / \(Fmt.price(q.low52, kr: kr))")
            KV("전일 종가", Fmt.price(q.prevClose, kr: kr))
            Text("공휴일은 장 운영 판단에 반영되지 않습니다. '실시간'은 공급원이 지연 0분으로 표기하고 체결 시각이 최신일 때만 표시합니다.")
                .font(.system(size: 12)).foregroundColor(AppColors.sub)
        }
    }
}

struct PriceGuard: View {
    @EnvironmentObject var m: AppModel
    let q: Quote
    var body: some View {
        let msg: String? = q.unreliable ? "분석 당시 시세가 신뢰할 수 없는 상태였습니다 (\(q.statusText)). 매수·매도 판단에 사용하지 마세요." : m.analysisDrift
        if let msg = msg {
            SectionCard("⚠ 시세 확인 필요") {
                Text(msg).font(.system(size: 14)).foregroundColor(AppColors.orange)
                PrimaryButton(title: "최신 시세로 다시 분석") { m.runAnalysis() }
            }
        }
    }
}

struct VerdictCard: View {
    let r: AnalysisResult, q: Quote, barsSource: String
    var body: some View {
        SectionCard("AI 종합 판단") {
            let c = verdictColor(r.verdict)
            HStack(spacing: 12) {
                Text(r.verdict.label).font(.system(size: 23, weight: .heavy)).foregroundColor(c)
                    .padding(.horizontal, 14).padding(.vertical, 8).background(c.opacity(0.18)).clipShape(RoundedRectangle(cornerRadius: 12))
                if r.verdict != .na {
                    VStack(alignment: .leading) {
                        Text("종합 점수 \(r.score) / 100").foregroundColor(AppColors.text)
                        ProgressView(value: Double(r.score), total: 100).tint(c)
                    }
                }
            }
            Text(r.summary).font(.system(size: 15)).foregroundColor(AppColors.text)
            if !r.reasons.isEmpty {
                Text("판단 근거").font(.system(size: 15, weight: .bold)).foregroundColor(AppColors.up)
                ForEach(r.reasons, id: \.self) { Text("• \($0)").font(.system(size: 14)).foregroundColor(AppColors.text) }
            }
            if !r.risks.isEmpty {
                Text("위험 요인").font(.system(size: 15, weight: .bold)).foregroundColor(AppColors.down)
                ForEach(r.risks, id: \.self) { Text("• \($0)").font(.system(size: 14)).foregroundColor(AppColors.text) }
            }
            ForEach(r.warnings, id: \.self) { Text("⚠ \($0)").font(.system(size: 13)).foregroundColor(AppColors.orange) }
            Text("분석 기준: 일봉(\(barsSource)) · 정규장 가격 \(Fmt.price(q.price, kr: q.ref.market == .KR)) · \(q.statusText) · \(q.source) · \(MarketClock.format(q.marketTime, q.ref.market))")
                .font(.system(size: 12)).foregroundColor(AppColors.sub)
        }
    }
}

struct FactorsCard: View {
    let r: AnalysisResult
    var body: some View {
        if !r.factors.isEmpty {
            SectionCard("10개 항목 상세 분석") {
                ForEach(r.factors) { f in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text("\(f.no). \(f.title)").font(.system(size: 15, weight: .semibold)).foregroundColor(AppColors.text).lineLimit(1)
                            Spacer()
                            Pill(f.status, f.available ? scoreColor(f.score) : AppColors.sub)
                        }
                        Text(f.detail).font(.system(size: 13)).foregroundColor(AppColors.sub)
                    }.padding(.vertical, 4)
                }
            }
        }
    }
}

struct LevelRow: View {
    let lv: PriceLevel, kr: Bool, color: Color
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(lv.label).font(.system(size: 15, weight: .semibold)).foregroundColor(color)
                Spacer()
                Text("\(Fmt.price(lv.price, kr: kr))  \(Fmt.pct(lv.diffPct))").font(.system(size: 15, weight: .bold)).foregroundColor(AppColors.text)
            }
            Text("근거: \(lv.basis)").font(.system(size: 13)).foregroundColor(AppColors.sub)
            Text(lv.action).font(.system(size: 13)).foregroundColor(AppColors.text)
            if !lv.extra.isEmpty { Text(lv.extra).font(.system(size: 12)).foregroundColor(AppColors.sub) }
        }.padding(.vertical, 5)
    }
}

struct StrategyCard: View {
    let s: Strategy, r: AnalysisResult, kr: Bool
    var body: some View {
        SectionCard("매수·매도 전략 (자동 계산)") {
            Text("현재가 대비 차이(%)는 오른쪽에 표시. 매수가는 실제 지지 근거가 있는 가격 중 현재가에서 가까운 순으로 선정합니다.").font(.system(size: 12)).foregroundColor(AppColors.sub)
            Text("매수 전략").font(.system(size: 16, weight: .bold)).foregroundColor(AppColors.up)
            ForEach(s.buys) { LevelRow(lv: $0, kr: kr, color: AppColors.up) }
            Text("매수 금지 가격대: \(s.noBuyText)").font(.system(size: 14)).foregroundColor(AppColors.text)
                .padding(10).frame(maxWidth: .infinity, alignment: .leading).background(AppColors.stop.opacity(0.2)).clipShape(RoundedRectangle(cornerRadius: 8))
            Text("매도 전략").font(.system(size: 16, weight: .bold)).foregroundColor(AppColors.down)
            ForEach(s.targets) { LevelRow(lv: $0, kr: kr, color: AppColors.down) }
            LevelRow(lv: s.stop, kr: kr, color: AppColors.stop)
            LevelRow(lv: s.trendBreak, kr: kr, color: AppColors.purple)
            Text("손익비(계획 평균단가→2차 목표가 / 손절까지): \(s.riskReward.map { Fmt.d($0) + " : 1" } ?? "-") · ATR \(Fmt.price(r.atr, kr: kr)) (\(Fmt.d(r.atrPct))%)")
                .font(.system(size: 14)).foregroundColor(AppColors.text)
            Text("※ 제시 가격은 과거 데이터 기반 계산값이며 체결·수익을 보장하지 않습니다.").font(.system(size: 12)).foregroundColor(AppColors.sub)
        }
    }
}

struct LevelsCard: View {
    let r: AnalysisResult, kr: Bool
    var body: some View {
        if !(r.supports.isEmpty && r.resistances.isEmpty) {
            SectionCard("지지선 · 저항선") {
                Text("저항선 (위)").font(.system(size: 15, weight: .bold)).foregroundColor(AppColors.orange)
                if r.resistances.isEmpty { EmptyText("가까운 저항선 없음 (신고가 영역)") }
                ForEach(r.resistances.indices, id: \.self) { i in let p = r.resistances[i]; KV(Fmt.price(p.price, kr: kr) + "  " + Fmt.pct((p.price / r.price - 1) * 100), p.basis) }
                Text("지지선 (아래)").font(.system(size: 15, weight: .bold)).foregroundColor(AppColors.teal)
                if r.supports.isEmpty { EmptyText("가까운 지지선 없음") }
                ForEach(r.supports.indices, id: \.self) { i in let p = r.supports[i]; KV(Fmt.price(p.price, kr: kr) + "  " + Fmt.pct((p.price / r.price - 1) * 100), p.basis) }
            }
        }
    }
}

struct AlertShortcuts: View {
    @EnvironmentObject var m: AppModel
    let r: AnalysisResult, q: Quote
    @State private var done: Set<String> = []
    var body: some View {
        if let s = r.strategy {
            let kr = q.ref.market == .KR
            let items: [(String, AlertType, Double)] = [("1차 매수가", .buyPrice, s.buys[0].price), ("1차 목표가", .sellPrice, s.targets[0].price), ("손절가", .stopLoss, s.stop.price)]
                + (r.supports.first.map { [("지지선 이탈", AlertType.supportBreak, $0.price)] } ?? [])
                + (r.resistances.first.map { [("저항선 돌파", AlertType.resistBreak, $0.price)] } ?? [])
            SectionCard("이 가격으로 알림 설정") {
                ForEach(items, id: \.0) { it in
                    HStack {
                        Text("\(it.0) \(Fmt.price(it.2, kr: kr))").foregroundColor(AppColors.text)
                        Spacer()
                        Button(done.contains(it.0) ? "등록됨" : "알림 등록") {
                            m.addAlert(q.ref.symbol, q.ref.displayName, it.1, it.2); done.insert(it.0)
                        }.buttonStyle(.bordered).disabled(done.contains(it.0))
                    }.frame(minHeight: 44)
                }
                Text("iOS 정책상 백그라운드 확인은 시스템이 정한 간격(보통 15분 이상, 보장되지 않음)으로 실행됩니다. 앱을 열어 두면 즉시 확인합니다.")
                    .font(.system(size: 12)).foregroundColor(AppColors.sub)
            }
        }
    }
}

struct ExternalAiCard: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        SectionCard("외부 AI 의견 (선택)") {
            if !m.prefs.aiConfigured {
                Text("설정에서 OpenAI 호환 API 주소·모델·키를 입력하면 외부 AI 의견을 받을 수 있습니다. 기본 분석은 앱 내장 엔진이 무료로 수행합니다.")
                    .font(.system(size: 13)).foregroundColor(AppColors.sub)
            } else {
                switch m.ai {
                case .idle: PrimaryButton(title: "외부 AI 의견 받기") { m.runExternalAi() }
                case .loading: LoadingBox(text: "외부 AI 응답 대기 중…")
                case .failure(let e): ErrorBox(message: e) { m.runExternalAi() }
                case .success(let t): Text(t).font(.system(size: 14)).foregroundColor(AppColors.text)
                }
                Text("외부 AI는 시세·뉴스를 직접 조회하지 않으며, 앱이 계산한 위 분석 결과만 전달받아 의견을 씁니다.").font(.system(size: 12)).foregroundColor(AppColors.sub)
            }
        }
    }
}
