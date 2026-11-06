import SwiftUI
import AIStockCore

/// 안드로이드 AppColors 와 동일 (상승=빨강, 하락=파랑: 국내 증권사 관례)
enum AppColors {
    static let bg = Color(hex: 0x0B0F17), card = Color(hex: 0x131A27), card2 = Color(hex: 0x1A2333), line = Color(hex: 0x263247)
    static let text = Color(hex: 0xE8ECF4), sub = Color(hex: 0x8E99AD)
    static let up = Color(hex: 0xF04452), down = Color(hex: 0x3B82F6), accent = Color(hex: 0x5B8CFF), gold = Color(hex: 0xFACC15)
    static let green = Color(hex: 0x22C55E), orange = Color(hex: 0xF59E0B), teal = Color(hex: 0x14B8A6), purple = Color(hex: 0xA78BFA)
    static let stop = Color(hex: 0xE11D48)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
}

func changeColor(_ v: Double?) -> Color {
    guard let v = v, !v.isNaN, v != 0 else { return AppColors.sub }
    return v > 0 ? AppColors.up : AppColors.down
}

func verdictColor(_ v: Verdict) -> Color {
    switch v {
    case .strongBuy: return AppColors.up; case .splitBuy: return AppColors.orange; case .hold: return AppColors.sub
    case .splitSell: return AppColors.down; case .avoid: return Color(hex: 0x9333EA); case .na: return AppColors.sub
    }
}

func scoreColor(_ s: Int) -> Color { s >= 2 ? AppColors.up : s == 1 ? AppColors.orange : s == 0 ? AppColors.sub : s == -1 ? AppColors.down : Color(hex: 0x9333EA) }

struct SectionCard<Content: View, Action: View>: View {
    let title: String
    @ViewBuilder var action: () -> Action
    @ViewBuilder var content: () -> Content
    init(_ title: String, @ViewBuilder action: @escaping () -> Action, @ViewBuilder content: @escaping () -> Content) {
        self.title = title; self.action = action; self.content = content
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title).font(.system(size: 17, weight: .bold)).foregroundColor(AppColors.text)
                Spacer()
                action()
            }
            content()
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.card)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, 12).padding(.vertical, 5)
    }
}

extension SectionCard where Action == EmptyView {
    init(_ title: String, @ViewBuilder content: @escaping () -> Content) { self.init(title, action: { EmptyView() }, content: content) }
}

struct KV: View {
    let k: String, v: String
    var color: Color = AppColors.text
    init(_ k: String, _ v: String, _ color: Color = AppColors.text) { self.k = k; self.v = v; self.color = color }
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(k).font(.system(size: 14)).foregroundColor(AppColors.sub)
                .frame(width: 124, alignment: .leading)
            Text(v).font(.system(size: 14)).foregroundColor(color)
                .frame(maxWidth: .infinity, alignment: .leading)
        }.padding(.vertical, 2)
    }
}

struct Pill: View {
    let text: String, color: Color
    init(_ text: String, _ color: Color) { self.text = text; self.color = color }
    var body: some View {
        Text(text).font(.system(size: 13, weight: .semibold)).foregroundColor(color).lineLimit(1)
            .padding(.horizontal, 8).padding(.vertical, 3).background(color.opacity(0.18)).clipShape(RoundedRectangle(cornerRadius: 6))
    }
}

struct Chip: View {
    let text: String, selected: Bool
    var enabled = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(text).font(.system(size: 14, weight: .semibold))
                .foregroundColor(!enabled ? AppColors.sub.opacity(0.5) : selected ? .white : AppColors.text)
                .padding(.horizontal, 12).frame(minHeight: 36)
                .background(!enabled ? AppColors.card2.opacity(0.4) : selected ? AppColors.accent : AppColors.card2)
                .clipShape(Capsule())
        }.disabled(!enabled).buttonStyle(.plain)
    }
}

struct ChipRow<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View { ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 6) { content() } } }
}

struct PrimaryButton: View {
    let title: String
    var color: Color = AppColors.accent
    var enabled = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 17, weight: .bold)).foregroundColor(.white).frame(maxWidth: .infinity, minHeight: 52)
                .background(enabled ? color : color.opacity(0.4)).clipShape(RoundedRectangle(cornerRadius: 14))
        }.disabled(!enabled).buttonStyle(.plain)
    }
}

struct LoadingBox: View {
    var text = "불러오는 중…"
    var body: some View { HStack(spacing: 12) { ProgressView(); Text(text).foregroundColor(AppColors.sub) }.frame(maxWidth: .infinity).padding(16) }
}

struct ErrorBox: View {
    let message: String
    var retry: (() -> Void)?
    var body: some View {
        VStack(spacing: 8) {
            Label(message, systemImage: "exclamationmark.triangle.fill").font(.system(size: 14)).foregroundColor(AppColors.orange)
            if let r = retry { Button("다시 시도", action: r).buttonStyle(.bordered) }
        }.frame(maxWidth: .infinity).padding(8)
    }
}

struct EmptyText: View {
    let text: String
    init(_ t: String) { text = t }
    var body: some View { Text(text).font(.system(size: 14)).foregroundColor(AppColors.sub).padding(.vertical, 6) }
}

struct Disclaimer: View {
    var body: some View {
        Text("※ 본 앱의 분석·추천은 투자 권유가 아닌 참고용 정보입니다. 무료 공개 데이터는 지연·누락될 수 있으며, 투자 판단과 결과의 책임은 본인에게 있습니다.")
            .font(.system(size: 12)).foregroundColor(AppColors.sub).padding(.horizontal, 16).padding(.vertical, 8)
    }
}

struct SearchField: View {
    @EnvironmentObject var m: AppModel
    var placeholder = "종목명·초성·6자리 코드·티커 (예: 삼성전자, ㅅㅅㅈㅈ, 042700, NVDA)"
    var body: some View {
        HStack {
            Image(systemName: "magnifyingglass").foregroundColor(AppColors.sub)
            TextField(placeholder, text: Binding(get: { m.query }, set: { m.onQuery($0) }))
                .foregroundColor(AppColors.text).autocorrectionDisabled().textInputAutocapitalization(.never).submitLabel(.search)
            if m.searching { ProgressView().scaleEffect(0.8) }
            if !m.query.isEmpty { Button { m.clearSearch() } label: { Image(systemName: "xmark.circle.fill").foregroundColor(AppColors.sub) } }
        }
        .padding(12).background(AppColors.card2).clipShape(RoundedRectangle(cornerRadius: 12)).padding(.horizontal, 12).padding(.vertical, 6)
    }
}

struct StockRow: View {
    let ref: StockRef
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(ref.displayName).font(.system(size: 16, weight: .semibold)).foregroundColor(AppColors.text).lineLimit(1)
                    Text("\(ref.symbol) · \(ref.exchange.isEmpty ? (ref.market == .KR ? "한국" : "미국") : ref.exchange)\(ref.nameKr != nil && ref.name != ref.nameKr ? " · \(ref.name)" : "")")
                        .font(.system(size: 12)).foregroundColor(AppColors.sub).lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundColor(AppColors.sub)
            }.frame(minHeight: 48).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

struct SearchResultsView: View {
    @EnvironmentObject var m: AppModel
    var onPick: ((StockRef) -> Void)?
    var body: some View {
        if !m.query.trimmingCharacters(in: .whitespaces).isEmpty {
            SectionCard("검색 결과 (\(m.searchResults.count))") {
                if m.searchResults.isEmpty && !m.searching { EmptyText("일치하는 종목이 없습니다. 초성·코드·영문 티커로도 검색해 보세요.") }
                ForEach(m.searchResults.prefix(20)) { r in
                    StockRow(ref: r) { if let p = onPick { p(r) } else { m.open(r) } }
                    Divider().background(AppColors.line)
                }
            }
        }
    }
}

struct NewsRow: View {
    let n: NewsItem
    @Environment(\.openURL) var openURL
    var body: some View {
        Button { if let u = URL(string: n.link) { openURL(u) } } label: {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 4) {
                    Pill(n.sentiment.label, n.sentiment == .positive ? AppColors.up : n.sentiment == .negative ? AppColors.down : AppColors.sub)
                    ForEach(n.categories.prefix(2), id: \.self) { Pill($0, AppColors.purple) }
                }
                Text(n.title).font(.system(size: 15)).foregroundColor(AppColors.text).lineLimit(3).multilineTextAlignment(.leading)
                Text("\(n.source) · \(MarketClock.relative(n.publishedAt)) (\(MarketClock.formatKst(n.publishedAt)))\(n.relatedName.map { " · 관련: \($0)" } ?? "")")
                    .font(.system(size: 12)).foregroundColor(AppColors.sub).lineLimit(2)
            }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 6).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

struct Header<Trailing: View>: View {
    let subtitle: String
    @ViewBuilder var trailing: () -> Trailing
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("AI 주식 분석센터 PRO").font(.system(size: 22, weight: .bold)).foregroundColor(AppColors.text).lineLimit(1)
                Text(subtitle).font(.system(size: 13)).foregroundColor(AppColors.sub).lineLimit(1)
            }
            Spacer()
            trailing()
        }.padding(.horizontal, 16).padding(.top, 8)
    }
}
