// AI 종합 분석 엔진 — 안드로이드 AnalysisEngine.kt / iOS AnalysisEngine.swift 와 동일 규칙 (무료 로컬 엔진)
import { Ind, indicatorSet, avg } from './indicators.js';
import { Fmt } from './fmt.js';
import { Clock } from './clock.js';

export const VERDICT = {
  strongBuy: '적극 매수', splitBuy: '분할매수', hold: '관망', splitSell: '분할매도', avoid: '매수 금지 / 위험', na: '분석 불가',
};
export const MIN_BARS = 120;
const clamp = (v, lo = -2, hi = 2) => Math.min(Math.max(v, lo), hi);
const isNaN_ = Number.isNaN;

function na(msg, price) {
  return { verdict: 'na', score: 0, summary: msg, reasons: [], risks: [], factors: [], strategy: null, supports: [], resistances: [], price, atr: NaN, atrPct: NaN, rsi: NaN, ret120: NaN, warnings: [msg] };
}

function merge(sorted, tol) {
  const out = [];
  for (const s of sorted) {
    const last = out[out.length - 1];
    if (last && Math.abs(last.price - s.price) <= tol) { if (!last.basis.includes(s.basis)) out[out.length - 1] = { price: last.price, basis: last.basis + ' + ' + s.basis }; }
    else out.push(s);
  }
  return out;
}

const touches = (bars, level, below) => bars.slice(-20).filter((b) => (below ? b.l <= level : b.h >= level)).length;

/** inp: { bars, price, isKr, news (null=미조회), events (null=미조회), nowSec } */
export function analyze(inp) {
  const { bars, price } = inp;
  const nowSec = inp.nowSec ?? Math.floor(Date.now() / 1000);
  if (isNaN_(price) || !(price > 0)) return na('현재가 데이터가 없어 분석할 수 없습니다.', price);
  if (bars.length < MIN_BARS) return na(`일봉 데이터가 ${bars.length}개뿐이라 분석할 수 없습니다 (최소 ${MIN_BARS}거래일 필요 · 신규 상장 종목 등).`, price);
  const kr = inp.isKr;
  const ind = indicatorSet(bars, false, 'UTC');
  const i = bars.length - 1, c = ind.c, atr = ind.atr[i];
  if (isNaN_(atr) || atr <= 0) return na('변동성(ATR) 계산에 필요한 데이터가 부족합니다.', price);
  const warnings = [];
  const p = (x) => Fmt.price(x, kr);

  if (nowSec - bars[i].t > 10 * 86400) warnings.push('마지막 일봉이 10일 이상 지난 데이터입니다. 거래정지·데이터 지연 여부를 확인하세요.');
  for (let j = Math.max(1, bars.length - 250); j < bars.length; j++) {
    const r = bars[j].c / bars[j - 1].c;
    if (r > 2.2 || r < 0.45) { warnings.push(`${Clock.fmt(bars[j].t).slice(0, 10)} 에 하루 ±50% 이상 가격 급변이 있습니다 (분할·병합 미반영 가능성). 지표 해석에 주의하세요.`); break; }
  }
  const ma5 = ind.ma[5][i], ma20 = ind.ma[20][i], ma60 = ind.ma[60][i], ma120 = ind.ma[120][i], ma200 = ind.ma[200][i];
  const ma20Prev = ind.ma[20][i - 5], ma60Prev = ind.ma[60][i - 20], ma120Prev = ind.ma[120][i - 20] ?? NaN;
  const rsi = ind.rsi[i], atrPct = (atr / price) * 100;
  const ret20 = (price / c[i - 20] - 1) * 100, ret60 = (price / c[i - 60] - 1) * 100, ret120 = (price / c[i - 119] - 1) * 100;

  // 지지/저항 후보
  const levels = Ind.srLevels(bars, atr), vp = Ind.volumeProfile(bars);
  const vpAvg = vp.length ? avg(vp.map((x) => x.volume)) : 0;
  const last20 = bars.slice(-20), hi20 = Math.max(...last20.map((b) => b.h)), lo20 = Math.min(...last20.map((b) => b.l));
  const hi52 = Math.max(...bars.slice(-252).map((b) => b.h));
  const vwap20 = ind.vwap[i], bbU = ind.bb.upper[i], bbL = ind.bb.lower[i];
  const supCand = [], resCand = [];
  const add = (px, basis) => {
    if (isNaN_(px) || px <= 0) return;
    if (px < price * 0.998 && px > price - 6 * atr) supCand.push({ price: px, basis });
    else if (px > price * 1.002 && px < price + 8 * atr) resCand.push({ price: px, basis });
  };
  levels.forEach((l) => add(l.price, l.touches >= 2 ? `과거 고·저점 밀집(${l.touches}회)` : '직전 고·저점'));
  add(ma20, '20일 이동평균선'); add(ma60, '60일 이동평균선'); add(ma120, '120일 이동평균선');
  if (!isNaN_(ma200)) add(ma200, '200일 이동평균선');
  add(bbL, '볼린저밴드 하단'); add(bbU, '볼린저밴드 상단'); add(vwap20, '20일 VWAP(평균 거래단가)');
  vp.filter((x) => x.volume > vpAvg * 1.3).forEach((x) => add(x.mid, '매물대(거래 집중 가격대)'));
  add(lo20, '최근 20일 최저가'); add(hi20, '최근 20일 최고가'); add(hi52, '52주(1년) 최고가');
  const supports = merge(supCand.sort((a, b) => b.price - a.price), atr * 0.25);
  const resistances = merge(resCand.sort((a, b) => a.price - b.price), atr * 0.25);
  const nearSup = supports[0], nearRes = resistances[0];
  const factors = [];
  const F = (no, title, status, detail, score, weight, available = true) => factors.push({ no, title, status, detail, score, weight, available });

  // 1. 현재 추세
  {
    const slope20 = (ma20 / ma20Prev - 1) * 100;
    const aligned = ma5 > ma20 && ma20 > ma60 && ma60 > ma120, reverse = ma5 < ma20 && ma20 < ma60 && ma60 < ma120;
    const s = aligned && price > ma20 && slope20 > 0 ? 2 : price > ma20 && price > ma60 && slope20 > 0 ? 1 : reverse && price < ma20 && slope20 < 0 ? -2 : price < ma20 && price < ma60 ? -1 : 0;
    const st = ['강한 하락 추세', '하락 추세', '횡보·중립', '상승 추세', '강한 상승 추세'][s + 2];
    const arr = aligned ? '이동평균 정배열' : reverse ? '이동평균 역배열' : '이동평균 혼조';
    F(1, '현재 추세', st, `현재가 ${p(price)} 기준 20일선(${p(ma20)}) ${price > ma20 ? '위' : '아래'}, 60일선(${p(ma60)}) ${price > ma60 ? '위' : '아래'}, 120일선 ${price > ma120 ? '위' : '아래'}${!isNaN_(ma200) ? ', 200일선 ' + (price > ma200 ? '위' : '아래') : ''}. ${arr}, 20일선 5일 기울기 ${Fmt.pct(slope20)}.`, s, 8);
  }
  // 2. 모멘텀
  {
    const h = ind.macd.hist, hNow = h[i], hPrev = h[i - 3];
    let pts = 0;
    if (ret20 > 5) pts++; else if (ret20 < -5) pts--;
    if (hNow > 0 && hNow > hPrev) pts++; else if (hNow < 0 && hNow < hPrev) pts--;
    if (ret60 > 15) pts++; else if (ret60 < -15) pts--;
    const s = clamp(pts);
    const st = s >= 2 ? '상승 모멘텀 강함' : s === 1 ? '상승 모멘텀' : s === 0 ? '모멘텀 약함' : s === -1 ? '하락 모멘텀' : '하락 모멘텀 강함';
    F(2, '상승·하락 모멘텀', st, `20일 수익률 ${Fmt.pct(ret20)}, 60일 ${Fmt.pct(ret60)}. MACD 히스토그램 ${hNow >= 0 ? '양(+)' : '음(-)'}, 3일 전 대비 ${hNow > hPrev ? '확대' : '축소'} (MACD ${Fmt.d(ind.macd.macd[i])} / 시그널 ${Fmt.d(ind.macd.signal[i])}).`, s, 6);
  }
  // 3. 단기 매수 적합성
  const ext20 = (price - ma20) / atr;
  {
    const k = ind.stoch.k, dd = ind.stoch.d;
    let pts = 0; const notes = [];
    const ri = Math.round(rsi);
    if (rsi >= 40 && rsi <= 65) { pts++; notes.push(`RSI ${ri} 적정 구간`); } else if (rsi > 72) { pts--; notes.push(`RSI ${ri} 과열`); } else notes.push(`RSI ${ri}`);
    const distSup = nearSup ? (price - nearSup.price) / atr : NaN;
    if (!isNaN_(distSup) && distSup <= 1.2) { pts++; notes.push(`지지선(${p(nearSup.price)})과 ATR ${Fmt.d(distSup, 1)}배 거리로 근접`); }
    if (ext20 > 2.5) { pts--; notes.push(`20일선 대비 ATR ${Fmt.d(ext20, 1)}배 이격 → 추격매수 위험`); }
    if (k[i] > dd[i] && k[i - 1] <= dd[i - 1] && k[i] < 40) { pts++; notes.push('스토캐스틱 침체권 골든크로스'); }
    if (k[i] < dd[i] && k[i - 1] >= dd[i - 1] && k[i] > 70) { pts--; notes.push('스토캐스틱 과열권 데드크로스'); }
    const s = clamp(pts);
    F(3, '단기 매수 적합성', s >= 2 ? '단기 매수 적합' : s === 1 ? '조건부 적합(눌림 대기)' : s === 0 ? '중립' : s === -1 ? '단기 매수 부적합' : '단기 매수 매우 부적합', notes.join(', ') + '.', s, 5);
  }
  // 4. 중기 보유
  {
    let pts = 0;
    const slope60 = (ma60 / ma60Prev - 1) * 100;
    pts += price > ma60 ? 1 : -1; pts += slope60 > 0 ? 1 : -1;
    if (!isNaN_(ma120Prev) && ma120 > ma120Prev && price > ma120) pts++;
    if (!isNaN_(ma200) && price < ma200) pts--;
    const s = clamp(pts);
    F(4, '중기 보유 적합성', s >= 2 ? '중기 보유 적합' : s === 1 ? '중기 보유 무난' : s === 0 ? '중립' : s === -1 ? '중기 보유 주의' : '중기 보유 부적합',
      `60일선 20일 기울기 ${Fmt.pct(slope60)}, 120일 수익률 ${Fmt.pct(ret120)}${!isNaN_(ma200) ? `, 200일선 ${price > ma200 ? '위(장기 상승 구조)' : '아래(장기 약세 구조)'}` : ', 200일선 계산 불가(데이터 부족)'}.`, s, 6);
  }
  // 5. 거래량
  {
    const v = ind.v, a5 = avg(v.slice(i - 5, i)), a20 = avg(v.slice(i - 25, i - 5));
    const ratio = a20 > 0 ? a5 / a20 : NaN;
    let upV = 0, dnV = 0; for (let j = i - 20; j < i; j++) { if (c[j] >= c[j - 1]) upV += v[j]; else dnV += v[j]; }
    const ud = dnV > 0 ? upV / dnV : 2;
    let pts = 0; const up5 = c[i - 1] > c[i - 6];
    if (!isNaN_(ratio) && ratio > 1.4) pts += up5 ? 1 : -1;
    if (ud > 1.3) pts++; else if (ud < 0.75) pts--;
    const s = clamp(pts);
    F(5, '거래량 변화', s >= 1 ? '매수세 우위 거래량' : s === 0 ? '거래량 평이' : '매도세 우위 거래량',
      a20 <= 0 ? '거래량 데이터가 부족합니다.' : `최근 5일 평균 거래량이 이전 20일 대비 ${Fmt.d(ratio)}배, 20일간 상승일/하락일 거래량 비율 ${Fmt.d(ud)}.`, s, 4, a20 > 0);
  }
  // 6. 매물대
  {
    let pts = 0;
    const poc = vp.reduce((a, b) => (!a || b.volume > a.volume ? b : a), null);
    const total = vp.reduce((a, b) => a + b.volume, 0);
    const over = vp.filter((x) => x.low >= price && x.low <= price * 1.1).reduce((a, b) => a + b.volume, 0);
    const overRatio = total > 0 ? over / total : 0;
    if (poc) pts += price > poc.mid ? 1 : -1;
    if (overRatio > 0.25) pts--;
    if (nearRes && (nearRes.price - price) / atr < 0.5) pts--;
    if (nearSup && (price - nearSup.price) / atr < 1) pts++;
    const s = clamp(pts);
    F(6, '매물대 및 지지·저항', s >= 1 ? '매물 부담 적음' : s === 0 ? '매물 중립' : '상단 매물 부담',
      `${poc ? `최대 매물대 ${p(poc.mid)} ${price > poc.mid ? '위(지지 역할)' : '아래(저항 역할)'}. ` : ''}현재가 위 +10% 구간 매물 비중 ${Fmt.d(overRatio * 100, 0)}%. 가까운 지지 ${nearSup ? p(nearSup.price) : '없음'}, 가까운 저항 ${nearRes ? p(nearRes.price) : '없음'}.`, s, 4, !!poc);
  }
  // 7. 과매수·과매도
  {
    const pb = bbU > bbL ? (price - bbL) / (bbU - bbL) : 0.5, k = ind.stoch.k[i], ts = factors[0].score;
    let s, st;
    if (rsi > 78 || pb > 1.1) { s = -2; st = '강한 과매수'; }
    else if (rsi > 70 || pb > 1) { s = -1; st = '과매수'; }
    else if (rsi < 30 || pb < 0) { s = ts >= 0 ? 1 : 0; st = ts >= 0 ? '과매도(반등 가능)' : '과매도(하락추세 중 · 반등 신호 아님)'; }
    else { s = 0; st = '중립'; }
    F(7, '과매수·과매도', st, `RSI ${Fmt.d(rsi, 1)}, 스토캐스틱 %K ${Fmt.d(k, 1)}, 볼린저 %B ${Fmt.d(pb)} (0 미만 과매도 · 1 초과 과매수).`, s, 4);
  }
  // 8. 변동성
  {
    let gaps = 0; for (let j = i - 59; j <= i; j++) if (Math.abs(bars[j].o / c[j - 1] - 1) > 0.03) gaps++;
    let pts = atrPct > 6 ? -2 : atrPct > 4 ? -1 : atrPct < 2 ? 1 : 0; if (gaps >= 4) pts--;
    F(8, '변동성 및 갭 위험', atrPct > 6 ? '변동성 매우 높음' : atrPct > 4 ? '변동성 높음' : atrPct < 2 ? '변동성 낮음' : '변동성 보통',
      `하루 평균 변동폭(ATR) ${p(atr)} = 주가의 ${Fmt.d(atrPct)}%. 최근 60일 중 3% 이상 갭 발생 ${gaps}회.`, clamp(pts), 3);
  }
  // 9. 뉴스
  if (inp.news) {
    const recent = inp.news.filter((n) => nowSec - n.publishedAt <= 7 * 86400);
    if (!recent.length) F(9, '최신 뉴스 영향', '최근 7일 뉴스 없음', '최근 7일 내 관련 뉴스가 없습니다 (중립 처리).', 0, 3);
    else {
      let net = 0; for (const n of recent) { const w = nowSec - n.publishedAt <= 72 * 3600 ? 2 : 1; net += n.sentiment === 'positive' ? w : n.sentiment === 'negative' ? -w : 0; }
      const pos = recent.filter((n) => n.sentiment === 'positive').length, neg = recent.filter((n) => n.sentiment === 'negative').length;
      const s = net >= 4 ? 2 : net >= 1.5 ? 1 : net <= -4 ? -2 : net <= -1.5 ? -1 : 0;
      const top = recent.filter((n) => n.sentiment !== 'neutral').slice(0, 2).map((n) => `「${n.title.slice(0, 40)}」`).join(' / ');
      F(9, '최신 뉴스 영향', s > 0 ? '호재 우위' : s < 0 ? '악재 우위' : '중립', `최근 7일 ${recent.length}건 중 호재 ${pos}건, 악재 ${neg}건 (제목 키워드 자동 분류). ${top}`, s, 3);
    }
  } else F(9, '최신 뉴스 영향', '뉴스 미조회', '뉴스를 불러오지 못해 이 항목은 점수에서 제외했습니다.', 0, 3, false);
  // 10. 일정
  if (inp.events) {
    let s = 0; const notes = [];
    for (const e of inp.events.filter((e) => e.daysLeft >= 0 && e.daysLeft <= 7)) {
      if (e.title.includes('실적')) { s--; notes.push(`${e.title} D-${e.daysLeft} → 발표 전후 갭 위험`); }
      if (e.title.includes('FOMC') && e.daysLeft <= 3) { s--; notes.push(`FOMC D-${e.daysLeft} → 시장 변동성 확대 가능`); }
    }
    if (!notes.length) notes.push('7일 이내 확인된 주요 일정 없음' + (kr ? ' (한국 기업 실적일은 무료 데이터 미제공 → DART 공시 확인)' : ''));
    F(10, '실적 발표 등 주요 일정', s < 0 ? '일정 리스크 있음' : '일정 리스크 낮음', notes.join(' · '), clamp(s, -2, 0), 2);
  } else F(10, '실적 발표 등 주요 일정', '일정 미조회', '일정 데이터를 불러오지 못했습니다.', 0, 2, false);

  // 종합
  const av = factors.filter((f) => f.available);
  const maxAbs = av.reduce((a, f) => a + f.weight, 0) * 2, raw = av.reduce((a, f) => a + f.weight * f.score, 0);
  const score = Math.min(Math.max(Math.round(50 + (raw / maxAbs) * 50), 0), 100);
  let verdict = score >= 72 ? 'strongBuy' : score >= 58 ? 'splitBuy' : score >= 43 ? 'hold' : score >= 32 ? 'splitSell' : 'avoid';
  const tS = factors[0].score, mS = factors[1].score;
  if (tS <= -2 && mS <= -1) { verdict = 'avoid'; warnings.push("강한 하락 추세 + 하락 모멘텀으로 '매수 금지' 판정"); }
  if (atrPct > 8 && tS < 0 && verdict !== 'avoid') { verdict = 'avoid'; warnings.push('하락 추세 중 일 변동폭 8% 초과로 위험 판정'); }
  if (verdict === 'strongBuy' && (rsi > 75 || ext20 > 3)) { verdict = 'splitBuy'; warnings.push("단기 과열(RSI·이격)로 '적극 매수' → '분할매수'로 낮춤"); }
  if (verdict === 'strongBuy' && factors[9].score <= -1) { verdict = 'splitBuy'; warnings.push("임박한 주요 일정으로 '적극 매수' → '분할매수'로 낮춤"); }
  const strategy = buildStrategy(price, atr, kr, supports, resistances, bars, ma60, ma120, verdict);
  const rr = strategy.riskReward ?? 0;
  if ((verdict === 'strongBuy' || verdict === 'splitBuy') && rr < 0.8) { verdict = verdict === 'strongBuy' ? 'splitBuy' : 'hold'; warnings.push(`손익비 ${Fmt.d(rr)}:1 로 불리하여 판단을 한 단계 낮춤`); }
  const ranked = [...av].sort((a, b) => b.weight * b.score - a.weight * a.score);
  const reasons = ranked.filter((f) => f.score > 0).slice(0, 3).map((f) => `${f.title}: ${f.status} — ${f.detail}`);
  const risks = [...ranked].reverse().filter((f) => f.score < 0).slice(0, 3).map((f) => `${f.title}: ${f.status} — ${f.detail}`);
  let summary = `${VERDICT[verdict]} (종합 ${score}점/100). ${factors[0].status}, ${factors[1].status}, ${factors[6].status}. `;
  if (strategy.riskReward != null) summary += `계획 평균단가 기준 손익비 ${Fmt.d(strategy.riskReward)}:1. `;
  if (verdict === 'avoid') summary += '신규 매수는 보류하고 추세 회복(20일선 회복·거래량 동반 반등)을 확인하세요.';
  return { verdict, score, summary, reasons, risks, factors, strategy, supports: supports.slice(0, 5), resistances: resistances.slice(0, 5), price, atr, atrPct, rsi, ret120, warnings };
}

function buildStrategy(price, atr, kr, sup, res, bars, ma60, ma120, verdict) {
  const r = (x) => Ind.roundPrice(x, kr), diff = (x) => (x / price - 1) * 100;
  const first = (arr, f, def) => arr.find(f) ?? def;
  // 1차 매수가는 현재가에서 ATR 1.2배 이내 (체결 가능성 낮은 깊은 가격만 반복 추천하지 않음)
  const b1s = first(sup, (s) => s.price >= price - 1.2 * atr, { price: price - 0.5 * atr, basis: '근접 지지선 없음 → 평균 변동폭(ATR)의 0.5배 눌림목' }); const b1 = r(b1s.price);
  const b2s = first(sup, (s) => s.price <= b1 - 0.4 * atr && s.price >= price - 3 * atr, { price: b1 - atr, basis: '1차 매수가 − ATR 1배' }); const b2 = r(b2s.price);
  const b3s = first(sup, (s) => s.price <= b2 - 0.4 * atr && s.price >= price - 5 * atr, { price: b2 - 1.2 * atr, basis: '2차 매수가 − ATR 1.2배' }); const b3 = r(b3s.price);
  const stopS = sup.find((s) => s.price < b3 - 0.2 * atr && s.price >= b3 - 1.5 * atr);
  const stop = r(stopS ? stopS.price - 0.3 * atr : b3 - atr);
  const stopBasis = stopS ? `${stopS.basis} 아래 (ATR 0.3배 여유)` : '3차 매수가 − ATR 1배 (변동폭 기준)';
  let tbP, tbB;
  if (!Number.isNaN(ma60) && ma60 < price) { tbP = ma60; tbB = '60일 이동평균선 (중기 추세 기준선)'; }
  else if (!Number.isNaN(ma120) && ma120 < price) { tbP = ma120; tbB = '120일 이동평균선 (장기 추세 기준선)'; }
  else { tbP = Math.min(...bars.slice(-60).map((b) => b.l)); tbB = '최근 60일 최저가 (이탈 시 하락 추세 연장)'; }
  const tb = r(tbP);
  const t1s = first(res, (s) => s.price >= price + 0.6 * atr, { price: price + 1.5 * atr, basis: '상단 저항 없음 → ATR 1.5배' }); const t1 = r(t1s.price);
  const t2s = first(res, (s) => s.price >= t1 + 0.5 * atr, { price: t1 + 1.5 * atr, basis: '1차 목표가 + ATR 1.5배' }); const t2 = r(t2s.price);
  const t3s = first(res, (s) => s.price >= t2 + 0.5 * atr, { price: t2 + 2 * atr, basis: '2차 목표가 + ATR 2배' }); const t3 = r(t3s.price);
  const fromB1 = (x) => `1차 매수가 기준 ${Fmt.pct((x / b1 - 1) * 100)}`;
  const L = (label, pr, basis, action, extra = '') => ({ label, price: pr, diffPct: diff(pr), basis, action, extra });
  const buys = [
    L('1차 매수가', b1, b1s.basis, '계획 물량의 30~40% 매수. 종가가 이 가격 위에서 지지되는지 확인.', `최근 20거래일 중 ${touches(bars, b1, true)}일 장중 도달`),
    L('2차 매수가', b2, b2s.basis, '추가 하락 시 30% 추가. 거래량 터진 급락이면 하루 관망 후 진행.', `최근 20거래일 중 ${touches(bars, b2, true)}일 장중 도달`),
    L('3차 매수가', b3, b3s.basis, '마지막 30%. 이후 손절가 이탈 시 계획대로 정리.', `최근 20거래일 중 ${touches(bars, b3, true)}일 장중 도달`),
  ];
  const targets = [
    L('1차 목표가', t1, t1s.basis, '보유 물량 30% 매도 후 손절가를 매수 평균가로 올림.', fromB1(t1)),
    L('2차 목표가', t2, t2s.basis, '30~40% 추가 매도, 나머지는 추세 추종.', fromB1(t2)),
    L('3차 목표가', t3, t3s.basis, '잔여 물량 정리 또는 직전 저점 기준 추적 손절.', fromB1(t3)),
  ];
  const ma20last = Ind.sma(bars.map((b) => b.c), 20).at(-1);
  const chase = Math.min(res[0] ? res[0].price - 0.25 * atr : Infinity, ma20last + 2.5 * atr);
  const noBuyFrom = r(Math.max(chase, price));
  const noBuyText = verdict === 'avoid' ? "현재 판단이 '매수 금지'이므로 모든 가격대에서 신규 매수를 보류하세요."
    : chase <= price ? '현재가가 이미 추격매수 금지 구간입니다 (저항선 근접 또는 20일선 대비 과도한 이격). 눌림을 기다리세요.'
      : `${Fmt.price(noBuyFrom, kr)} 이상은 저항선 근접·과열 구간으로 추격매수 금지.`;
  const avgEntry = b1 * 0.4 + b2 * 0.3 + b3 * 0.3;
  return { buys, noBuyFrom, noBuyText, targets, stop: L('손절 기준가', stop, stopBasis, '종가 기준 이탈 시 손절 (손실 제한 우선).', fromB1(stop)),
    trendBreak: L('추세 붕괴 기준가', tb, tbB, '종가 이탈 시 중기 보유 판단 철회, 비중 축소.'), riskReward: avgEntry > stop ? (t2 - avgEntry) / (avgEntry - stop) : null };
}
