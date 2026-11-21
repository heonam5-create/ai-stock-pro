// 뉴스 제목 키워드 기반 호재/악재/중립 + 주제 분류 (안드로이드·iOS 와 동일, 화면에 "자동 분류" 표시)
export const CATEGORIES = [
  ["기업 실적", ["실적", "영업이익", "매출", "순이익", "어닝", "잠정", "earnings", "revenue", "eps", "guidance", "profit", "quarter"]],
  ["기업 투자", ["투자", "증설", "설비", "공장", "인수", "합병", "m&a", "capex", "invest", "factory", "plant", "acquire", "acquisition"]],
  ["신규 계약", ["수주", "계약", "공급", "협약", "파트너십", "contract", "deal", "order", "partnership", "agreement"]],
  ["제품 출시", ["출시", "신제품", "공개", "선보", "launch", "unveil", "release", "debut"]],
  ["반도체", ["반도체", "hbm", "메모리", "dram", "d램", "낸드", "nand", "파운드리", "chip", "semiconductor", "gpu", "foundry"]],
  ["금리 정책", ["금리", "기준금리", "연준", "한은", "rate cut", "rate hike", "interest rate", "fed "]],
  ["CPI", ["cpi", "소비자물가", "물가", "inflation", "pce"]],
  ["FOMC", ["fomc", "파월", "powell", "점도표"]],
  ["고용지표", ["고용", "실업", "비농업", "payroll", "jobs report", "unemployment", "jobless"]],
  ["경영진 발언", ["ceo", "회장", "대표", "발언", "밝혀", "젠슨", "머스크", "says", "said", "comments"]],
  ["증자·분할", ["유상증자", "무상증자", "액면분할", "주식분할", "stock split", "offering", "dilution", "share sale"]],
  ["내부자 매매", ["내부자", "임원", "자사주", "대주주", "지분 매각", "insider", "buyback", "repurchase", "stake sale"]],
];
const POS = ["상승", "급등", "강세", "최대", "최고", "호실적", "흑자", "흑자전환", "수주", "상향", "돌파", "신고가", "승인", "성장",
        "개선", "증가", "기대", "호재", "반등", "순매수", "무상증자", "자사주 매입", "자사주 소각", "목표가 상향", "서프라이즈",
        "beat", "beats", "surge", "soar", "record", "upgrade", "raises", "rally", "jump", "gain", "strong", "approval",
        "buyback", "wins", "outperform", "bullish", "tops", "higher"];
const NEG = ["하락", "급락", "약세", "적자", "감소", "하향", "부진", "우려", "소송", "리콜", "유상증자", "쇼크", "경고", "손실",
        "매도", "순매도", "악재", "제재", "조사", "압수수색", "횡령", "배임", "감산", "목표가 하향", "신저가", "불확실",
        "miss", "misses", "plunge", "fall", "falls", "drop", "downgrade", "cut", "lawsuit", "recall", "probe", "weak",
        "loss", "slump", "warning", "delay", "offering", "bearish", "lower", "tumble", "sink", "investigation"];
export function classify(item) {
  const t = item.title.toLowerCase();
  let s = 0;
  for (const w of POS) if (t.includes(w)) s++;
  for (const w of NEG) if (t.includes(w)) s--;
  if (t.includes('적자 축소') || t.includes('손실 축소')) s += 2;
  return { ...item, sentiment: s > 0 ? 'positive' : s < 0 ? 'negative' : 'neutral', categories: CATEGORIES.filter(([, k]) => k.some((w) => t.includes(w))).map(([n]) => n) };
}
export const SENT_LABEL = { positive: '호재', negative: '악재', neutral: '중립' };
/** 7일 이내 · 중복 제거 · 최신순 · 분류 */
export function finishNews(list, nowSec = Math.floor(Date.now() / 1000)) {
  const seen = new Set();
  return list.filter((n) => nowSec - n.publishedAt >= -3600 && nowSec - n.publishedAt <= 7 * 86400)
    .filter((n) => { const k = n.title.slice(0, 40); if (seen.has(k)) return false; seen.add(k); return true; })
    .sort((a, b) => b.publishedAt - a.publishedAt).slice(0, 40).map(classify);
}
