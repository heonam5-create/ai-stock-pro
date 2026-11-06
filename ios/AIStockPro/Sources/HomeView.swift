import SwiftUI
import AIStockCore

struct HomeView: View {
    @EnvironmentObject var m: AppModel
    let quick = ["005930.KS", "000660.KS", "035420.KS", "042700.KS", "NVDA", "MU", "SNDK", "TSLA", "AAPL"]

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Header(subtitle: "한국·미국 주식 시세 · AI 분석") {
                    Button { m.loadHome(force: true) } label: { Image(systemName: "arrow.clockwise").foregroundColor(AppColors.sub).frame(width: 44, height: 44) }
                }
                SearchField()
                SearchResultsView()
                if m.query.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("빠른 검색").font(.system(size: 14)).foregroundColor(AppColors.sub)
                        ChipRow {
                            ForEach(quick, id: \.self) { s in
                                if let e = StockDirectory.shared.bySymbol(s) { Chip(text: e.nameKr.isEmpty ? s : e.nameKr, selected: false) { m.open(e.ref) } }
                            }
                        }
                    }.padding(.horizontal, 16).padding(.vertical, 4)
                }

                SectionCard("시장 지수 · 환율") {
                    switch m.indices {
                    case .idle, .loading: LoadingBox()
                    case .failure(let e): ErrorBox(message: e) { m.loadHome(force: true) }
                    case .success(let list):
                        ForEach(list) { iq in
                            HStack {
                                Text(iq.name).foregroundColor(AppColors.text)
                                Spacer()
                                Text(Fmt.num(iq.price, kr: false)).font(.system(size: 16, weight: .semibold)).foregroundColor(AppColors.text)
                                Text(Fmt.pct(iq.changePct)).foregroundColor(changeColor(iq.changePct)).frame(width: 78, alignment: .trailing)
                            }.padding(.vertical, 3)
                        }
                        Text("출처: \(Set(list.map(\.source)).sorted().joined(separator: ", ")) · 기준 \(list.first.map { MarketClock.formatKst($0.time) } ?? "-")")
                            .font(.system(size: 12)).foregroundColor(AppColors.sub)
                    }
                }

                if !m.recent.isEmpty {
                    SectionCard("최근 본 종목") { ForEach(m.recent.prefix(5)) { r in StockRow(ref: r) { m.open(r) } } }
                }

                SectionCard("주요 경제 일정") {
                    ForEach(Events.upcomingMacro(), id: \.date) { e in
                        HStack(alignment: .top) {
                            Pill(e.daysLeft >= 0 && e.daysLeft < 999 ? "D-\(e.daysLeft)" : "-", AppColors.gold)
                            VStack(alignment: .leading) {
                                Text("\(e.date)  \(e.title)").foregroundColor(AppColors.text).font(.system(size: 15))
                                Text(e.detail).foregroundColor(AppColors.sub).font(.system(size: 13))
                            }
                        }
                    }
                    Text("CPI·고용지표 발표일은 무료 API가 없어 아래 경제 뉴스로 확인하세요.").font(.system(size: 12)).foregroundColor(AppColors.sub)
                }

                SectionCard("경제·금리·반도체 뉴스 (최근 7일)") {
                    switch m.macro {
                    case .idle, .loading: LoadingBox()
                    case .failure(let e): ErrorBox(message: e) { m.loadHome(force: true) }
                    case .success(let list):
                        if list.isEmpty { EmptyText("최근 7일 내 관련 뉴스가 없습니다.") }
                        ForEach(list.prefix(10)) { NewsRow(n: $0) }
                    }
                }
                Disclaimer()
            }
        }
        .background(AppColors.bg)
        .onAppear { m.loadHome() }
    }
}
