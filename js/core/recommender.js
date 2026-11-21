// BEST 5 추천 점수 — 안드로이드 Recommender.kt 와 동일
import { Fmt } from './fmt.js';
import { VERDICT } from './analysis.js';
const f = (s) => Math.round((s + 2) * 2.5);
export function riskGrade(atrPct, v) {
  if (v === 'avoid') return '매우 높음'; if (Number.isNaN(atrPct)) return '-';
  return atrPct < 2.5 ? '낮음' : atrPct < 4 ? '보통' : atrPct < 6 ? '높음' : '매우 높음';
}
export function score(ref, r, dataTime) {
  if (['na', 'avoid', 'splitSell'].includes(r.verdict) || !r.strategy) return null;
  const fx = {}; r.factors.forEach((x) => (fx[x.no] = x));
  const st = r.strategy, b1 = st.buys[0].price, dist = ((r.price - b1) / r.price) * 100, g = r.ret120;
  const growth = Number.isNaN(g) ? 5 : g > 40 ? 10 : g > 20 ? 8 : g > 8 ? 6 : g > 0 ? 5 : g > -10 ? 3 : 1;
  const rr = st.riskReward ?? 0;
  const parts = [
    ['상승 추세', f(fx[1]?.score ?? 0)], ['거래량 증가', f(fx[5]?.score ?? 0)],
    ['기술적 지표', Math.round((f(fx[2]?.score ?? 0) + f(fx[7]?.score ?? 0)) / 2)], ['성장성(120일 주가 성장률)', growth],
    ['호재·악재', fx[9]?.available ? f(fx[9].score) : 5], ['변동성 안정', f(fx[8]?.score ?? 0)],
    ['지지선과의 거리', dist < 2 ? 10 : dist < 4 ? 7 : dist < 7 ? 4 : 2], ['손익비', rr >= 2.5 ? 10 : rr >= 2 ? 8 : rr >= 1.5 ? 6 : rr >= 1 ? 4 : 1],
    ['단기 매매 적합성', f(fx[3]?.score ?? 0)],
  ];
  const w = [1.4, 0.9, 1.1, 0.9, 0.8, 0.7, 1.0, 1.1, 1.0], ws = w.reduce((a, b) => a + b, 0);
  const total = Math.round((parts.reduce((a, p, i) => a + p[1] * w[i], 0) / (10 * ws)) * 100);
  const top = [...parts].sort((a, b) => b[1] - a[1]).slice(0, 3).map((p) => `${p[0]} ${p[1]}/10`).join(', ');
  return { ref, price: r.price, result: r, total, parts, reason: `${VERDICT[r.verdict]} · ${top}. ${r.factors[0]?.status ?? ''}, 손익비 ${Fmt.d(rr)}:1`, riskGrade: riskGrade(r.atrPct, r.verdict), dataTime };
}
export const rank = (list, n = 5) => [...list].sort((a, b) => b.total - a.total || b.result.score - a.result.score).slice(0, n);
