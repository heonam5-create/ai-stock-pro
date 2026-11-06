import SwiftUI
import AIStockCore

struct Best5View: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Header(subtitle: "한국·미국 추천 BEST 5 (실제 데이터 분석)") { EmptyView() }
                Picker("시장", selection: $m.bestMarket) { Text("한국").tag(Market.KR); Text("미국").tag(Market.US) }
                    .pickerStyle(.segmented).padding(.horizontal, 12).padding(.vertical, 8)
                let state = m.bestMarket == .KR ? m.bestKr : m.bestUs
                PrimaryButton(title: state.isLoading ? "분석 중…" : "\(m.bestMarket == .KR ? "한국" : "미국") BEST 5 분석 시작", color: AppColors.up, enabled: !state.isLoading) { m.runBest(m.bestMarket) }
                    .padding(.horizontal, 12)
                Text("① 네이버 시가총액 순위로 후보군 확보 → ② 거래대금·주가 필터 → ③ 거래대금 상위 80개 일봉 2년 분석 → ④ 상위 10개 뉴스 반영 재분석 → ⑤ 종합 점수 상위 5개. 소요 30초~2분.")
                    .font(.system(size: 12)).foregroundColor(AppColors.sub).padding(.horizontal, 16).padding(.vertical, 6)
                switch state {
                case .idle: EmptyText("   버튼을 눌러 분석을 시작하세요.")
                case .loading:
                    VStack(alignment: .leading) {
                        ProgressView(value: Double(m.bestProgress.0), total: Double(max(m.bestProgress.1, 1)))
                        Text(m.bestProgress.2.isEmpty ? "준비 중…" : m.bestProgress.2).font(.system(size: 13)).foregroundColor(AppColors.sub)
                    }.padding(16)
                case .failure(let e): ErrorBox(message: e) { m.runBest(m.bestMarket) }
                case .success(let res): BestResultView(res: res, market: m.bestMarket)
                }
                Disclaimer()
            }
        }.background(AppColors.bg)
    }
}

struct BestResultView: View {
    @EnvironmentObject var m: AppModel
    let res: Repository.BestResult, market: Market
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("분석 완료 \(MarketClock.formatKst(res.at)) · 후보군: \(res.scope) 중 \(res.candidates)종목 → 거래대금·주가 필터 통과 \(res.liquid)종목 → 실제 일봉 분석 \(res.analyzed)종목" +
                 " · 추천 제외(매수 금지·분할매도·분석 불가) \(res.excluded)종목" +
                 (res.staleExcluded > 0 ? " · 시세 지연·불일치로 제외 \(res.staleExcluded)종목" : "") +
                 (res.failed.isEmpty ? "" : " · 데이터 수집 실패 \(res.failed.count)종목(\(res.failed.prefix(5).joined(separator: ", ")))"))
                .font(.system(size: 12)).foregroundColor(AppColors.sub).padding(.horizontal, 16)
            Text("※ 분석하지 않은 종목(필터 탈락·상위 80개 밖)은 평가하지 않았습니다.").font(.system(size: 12)).foregroundColor(AppColors.sub).padding(.horizontal, 16)
            if res.picks.isEmpty {
                SectionCard("추천 결과") { EmptyText("현재 추천 조건(매수 판단 + 손익비)을 충족한 종목이 없습니다. 시장이 약세일 때는 추천하지 않는 것이 정상 동작입니다.") }
            } else {
                if res.picks.count < 5 { Text("   조건을 충족한 종목이 \(res.picks.count)개뿐이라 \(res.picks.count)개만 표시합니다.").font(.system(size: 13)).foregroundColor(AppColors.orange) }
                ForEach(Array(res.picks.enumerated()), id: \.offset) { i, rec in RecoCard(rank: i + 1, rec: rec, market: market) { m.open(rec.ref) } }
            }
        }
    }
}

struct RecoCard: View {
    let rank: Int, rec: Recommendation, market: Market
    let action: () -> Void
    @State private var expanded = false
    var body: some View {
        let kr = market == .KR, s = rec.result.strategy
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Text("\(rank)").font(.system(size: 17, weight: .bold)).foregroundColor(rank == 1 ? .black : AppColors.text)
                    .frame(width: 36, height: 36).background(rank == 1 ? AppColors.gold : AppColors.card2).clipShape(Circle())
                VStack(alignment: .leading) {
                    Text(rec.ref.displayName).font(.system(size: 18, weight: .bold)).foregroundColor(AppColors.text).lineLimit(1)
                    Text("\(rec.ref.symbol) · 현재가 \(Fmt.price(rec.price, kr: kr))").font(.system(size: 13)).foregroundColor(AppColors.sub).lineLimit(1)
                }
                Spacer()
                VStack(alignment: .trailing) {
                    Text("\(rec.total)점").font(.system(size: 19, weight: .heavy)).foregroundColor(AppColors.gold)
                    Pill(rec.result.verdict.label, verdictColor(rec.result.verdict))
                }
            }
            Text("추천 이유: \(rec.reason)").font(.system(size: 14)).foregroundColor(AppColors.text)
            if let s = s {
                KV("1차 매수가", "\(Fmt.price(s.buys[0].price, kr: kr)) (\(Fmt.pct(s.buys[0].diffPct)))", AppColors.up)
                KV("1차 목표가", "\(Fmt.price(s.targets[0].price, kr: kr)) (\(Fmt.pct(s.targets[0].diffPct)))", AppColors.down)
                KV("손절가", "\(Fmt.price(s.stop.price, kr: kr)) (\(Fmt.pct(s.stop.diffPct)))", AppColors.stop)
            }
            KV("위험 등급", rec.riskGrade, rec.riskGrade == "낮음" ? AppColors.green : rec.riskGrade == "보통" ? AppColors.gold : AppColors.orange)
            KV("분석 기준 시각", MarketClock.format(rec.dataTime, market))
            Button(expanded ? "평가항목 접기" : "9개 평가항목 점수 보기") { expanded.toggle() }.font(.system(size: 14))
            if expanded {
                ForEach(rec.parts.indices, id: \.self) { i in
                    HStack {
                        Text(rec.parts[i].0).font(.system(size: 13)).foregroundColor(AppColors.sub)
                        Spacer()
                        ProgressView(value: Double(rec.parts[i].1), total: 10).frame(width: 110)
                        Text(" \(rec.parts[i].1)").font(.system(size: 13)).foregroundColor(AppColors.text).frame(width: 28)
                    }
                }
            }
            Button(action: action) { Text("상세 분석 보기 ›").font(.system(size: 14, weight: .semibold)).foregroundColor(AppColors.accent).frame(minHeight: 40) }
        }
        .padding(14).background(AppColors.card).clipShape(RoundedRectangle(cornerRadius: 16)).padding(.horizontal, 12).padding(.vertical, 5)
    }
}
