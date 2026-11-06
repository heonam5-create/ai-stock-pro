import Foundation

/// 뉴스 제목 키워드 기반 호재/악재/중립 + 주제 분류 (안드로이드 NewsClassifier.kt 와 동일, 화면에 "자동 분류" 표시)
public enum NewsClassifier {
    public static let categories: [(String, [String])] = [
        ("기업 실적", ["실적", "영업이익", "매출", "순이익", "어닝", "잠정", "earnings", "revenue", "eps", "guidance", "profit", "quarter"]),
        ("기업 투자", ["투자", "증설", "설비", "공장", "인수", "합병", "m&a", "capex", "invest", "factory", "plant", "acquire", "acquisition"]),
        ("신규 계약", ["수주", "계약", "공급", "협약", "파트너십", "contract", "deal", "order", "partnership", "agreement"]),
        ("제품 출시", ["출시", "신제품", "공개", "선보", "launch", "unveil", "release", "debut"]),
        ("반도체", ["반도체", "hbm", "메모리", "dram", "d램", "낸드", "nand", "파운드리", "chip", "semiconductor", "gpu", "foundry"]),
        ("금리 정책", ["금리", "기준금리", "연준", "한은", "rate cut", "rate hike", "interest rate", "fed "]),
        ("CPI", ["cpi", "소비자물가", "물가", "inflation", "pce"]),
        ("FOMC", ["fomc", "파월", "powell", "점도표"]),
        ("고용지표", ["고용", "실업", "비농업", "payroll", "jobs report", "unemployment", "jobless"]),
        ("경영진 발언", ["ceo", "회장", "대표", "발언", "밝혀", "젠슨", "머스크", "says", "said", "comments"]),
        ("증자·분할", ["유상증자", "무상증자", "액면분할", "주식분할", "stock split", "offering", "dilution", "share sale"]),
        ("내부자 매매", ["내부자", "임원", "자사주", "대주주", "지분 매각", "insider", "buyback", "repurchase", "stake sale"]),
    ]
    static let positive: [String] = ["상승", "급등", "강세", "최대", "최고", "호실적", "흑자", "흑자전환", "수주", "상향", "돌파", "신고가", "승인", "성장",
        "개선", "증가", "기대", "호재", "반등", "순매수", "무상증자", "자사주 매입", "자사주 소각", "목표가 상향", "서프라이즈",
        "beat", "beats", "surge", "soar", "record", "upgrade", "raises", "rally", "jump", "gain", "strong", "approval",
        "buyback", "wins", "outperform", "bullish", "tops", "higher"]
    static let negative: [String] = ["하락", "급락", "약세", "적자", "감소", "하향", "부진", "우려", "소송", "리콜", "유상증자", "쇼크", "경고", "손실",
        "매도", "순매도", "악재", "제재", "조사", "압수수색", "횡령", "배임", "감산", "목표가 하향", "신저가", "불확실",
        "miss", "misses", "plunge", "fall", "falls", "drop", "downgrade", "cut", "lawsuit", "recall", "probe", "weak",
        "loss", "slump", "warning", "delay", "offering", "bearish", "lower", "tumble", "sink", "investigation"]

    public static func classify(_ item: NewsItem) -> NewsItem {
        let t = item.title.lowercased()
        var score = 0
        for w in positive where t.contains(w) { score += 1 }
        for w in negative where t.contains(w) { score -= 1 }
        if t.contains("적자 축소") || t.contains("손실 축소") { score += 2 }
        var out = item
        out.sentiment = score > 0 ? .positive : score < 0 ? .negative : .neutral
        out.categories = categories.filter { $0.1.contains { t.contains($0) } }.map(\.0)
        return out
    }
}
