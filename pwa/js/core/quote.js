// 시세 신선도·교차검증·일봉 정렬·네이버 응답 파싱 — 안드로이드·iOS QuoteLogic/NaverSource 와 동일 규칙
import { Clock } from './clock.js';
import { Fmt } from './fmt.js';

export const FRESH = { realtime: '실시간', delayed: '지연 시세', snapshot: '지연 (스냅샷)', closed: '장 마감', stale: '⚠ 시세 지연', error: '⚠ 데이터 오류' };
export const SESSION = { pre: '프리마켓', regular: '정규장', post: '애프터마켓', nxt: 'NXT 대체거래소', closed: '장 마감', unknown: '세션 확인 불가' };

/** 정규장 중: 체결이 (지연+15분)보다 오래되면 stale. 공급원이 지연 0분 명시할 때만 realtime. 마감: 5일 초과 stale */
export function freshness(open, tradedAt, nowSec, delayMin, sourceRealtime) {
  const age = nowSec - tradedAt;
  if (open) {
    if (age > ((delayMin ?? 20) + 15) * 60) return 'stale';
    return sourceRealtime && (delayMin ?? 1) === 0 ? 'realtime' : 'delayed';
  }
  return age > 5 * 86400 ? 'stale' : 'closed';
}
/** 정규장 = 공급원 OPEN 이면서 시간표 안 (네이버는 NXT·시간외 중에도 OPEN 을 줄 수 있음) */
export const regularOpen = (statusOpen, market, nowMs = Date.now()) => statusOpen && Clock.isOpen(market, nowMs);

export function statusText(f, session, open, ageMin) {
  switch (f) {
    case 'realtime': return `실시간 · ${SESSION[session]}`;
    case 'delayed': return `지연 시세 · ${SESSION[session]}`;
    case 'snapshot': return `지연 (스냅샷 ${ageMin}분 전 수집)`;
    case 'closed': return '장 마감 · 정규장 종가';
    case 'stale': return open ? `⚠ 시세 지연 (${ageMin}분 전 체결가)` : `⚠ 오래된 데이터 (${Math.floor(ageMin / 1440)}일 전)`;
    default: return '⚠ 데이터 오류 · 마지막 성공 데이터';
  }
}

export function crossCheck(pp, pt, open, source, op, ot, maxGap = 300) {
  if (!(op > 0) || !(ot > 0)) return { source, mismatch: false, diffPct: null, message: `${source} 응답 없음 — 교차검증 불가` };
  const gap = Math.abs(pt - ot), diff = (op / pp - 1) * 100;
  if (!(open ? gap <= maxGap : gap <= 6 * 3600)) return { source, mismatch: false, diffPct: diff, message: `${source} 기준 시각이 ${Math.floor(gap / 60)}분 달라 비교 생략 (지연 시세 차이)` };
  if (Math.abs(diff) > (open ? 1 : 0.3)) return { source, mismatch: true, diffPct: diff, message: `⚠ 시세 불일치: ${source} ${Fmt.d(op)} (${diff >= 0 ? '+' : ''}${diff.toFixed(2)}%) — 원인 확인 전까지 매매 판단 보류` };
  return { source, mismatch: false, diffPct: diff, message: `${source} 와 일치 (차이 ${diff >= 0 ? '+' : ''}${diff.toFixed(2)}%)` };
}

export function alignDaily(bars, price, time, tz, high, low, volume) {
  if (!bars.length || !(price > 0) || !(time > 0)) return bars;
  const last = bars[bars.length - 1], qd = Clock.day(time, tz), ld = Clock.day(last.t, tz);
  if (qd === ld) return [...bars.slice(0, -1), { ...last, c: price, h: Math.max(last.h, price, high ?? price), l: Math.min(last.l, price, low ?? price), v: volume > 0 ? volume : last.v }];
  if (qd > ld) return [...bars, { t: time, o: price, h: Math.max(price, high ?? price), l: Math.min(price, low ?? price), c: price, v: volume ?? 0 }];
  return bars;
}

export const unreliable = (q) => q.freshness === 'stale' || q.freshness === 'error' || q.check?.mismatch === true;

// ── 네이버 응답 파싱 ──
export const num = (s) => { if (s === null || s === undefined) return null; if (typeof s === 'number') return s; const v = parseFloat(String(s).replace(/[,+]/g, '').trim()); return Number.isNaN(v) ? null : v; };
export const isoSec = (s) => { if (!s) return null; const t = Date.parse(String(s).replace(/(\.\d{3})\d+/, '$1')); return Number.isNaN(t) ? null : Math.floor(t / 1000); };
const signed = (v, o) => { if (v === null) return null; const code = o?.compareToPreviousPrice?.code; return (code === '4' || code === '5') && v > 0 ? -v : v; };
const sessionOf = (t) => ({ PRE_MARKET: 'pre', AFTER_MARKET: 'post', REGULAR_MARKET: 'regular' })[String(t || '').toUpperCase()] || 'unknown';

/** 네이버 시세 1건(polling·순위 목록 공통 형식) → Snap */
export function parseNaverItem(d, kr, nowSec = Math.floor(Date.now() / 1000)) {
  const price = num(d.closePriceRaw ?? d.closePrice);
  if (!(price > 0)) throw new Error('네이버 시세 형식 오류(현재가 없음)');
  const ex = d.stockExchangeType || {};
  let zone = ex.zoneId || ''; if (!zone || zone === 'EST5EDT') zone = kr ? 'Asia/Seoul' : 'America/New_York';
  const tradedAt = isoSec(d.localTradedAt) ?? nowSec;
  let ext = null; const o = d.overMarketPriceInfo;
  if (o && num(o.overPriceRaw ?? o.overPrice) > 0) {
    const ses = sessionOf(o.tradingSessionType);
    ext = { session: kr ? 'nxt' : ses, label: kr ? 'NXT ' + (ses === 'regular' ? '메인마켓' : SESSION[ses]) : SESSION[ses], price: num(o.overPriceRaw ?? o.overPrice),
      change: signed(num(o.compareToPreviousClosePriceRaw ?? o.compareToPreviousClosePrice), o), changePct: signed(num(o.fluctuationsRatio), o),
      time: isoSec(o.localTradedAt) ?? tradedAt, active: String(o.overMarketStatus || '').toUpperCase() === 'OPEN' };
  }
  const change = signed(num(d.compareToPreviousClosePriceRaw ?? d.compareToPreviousClosePrice), d);
  return { code: d.itemCode ?? d.reutersCode ?? '', name: d.stockName ?? d.indexName ?? null, symbolCode: d.symbolCode ?? null, price, change,
    changePct: signed(num(d.fluctuationsRatioRaw ?? d.fluctuationsRatio), d), open: num(d.openPriceRaw ?? d.openPrice), high: num(d.highPriceRaw ?? d.highPrice),
    low: num(d.lowPriceRaw ?? d.lowPrice), volume: num(d.accumulatedTradingVolumeRaw ?? d.accumulatedTradingVolume), marketStatus: d.marketStatus ?? 'UNKNOWN',
    tradedAt, delayMin: ex.delayTime ?? d.delayTime ?? 0, ext, zone, currency: d.currencyType?.code ?? (kr ? 'KRW' : 'USD'),
    marketCap: num(d.marketValueFullRaw ?? d.marketValueRaw), tradingValue: num(d.accumulatedTradingValueRaw), prevClose: change !== null ? price - change : null };
}
export function parseNaverPolling(json, kr, nowSec) {
  const d = json?.datas?.[0]; if (!d) throw new Error('네이버 금융에 해당 종목 시세가 없습니다.');
  return parseNaverItem(d, kr, nowSec);
}
/** 미국 공식 티커 → 네이버 코드 후보 (BRK.B → BRKb) */
export function usCandidates(ticker, known) {
  if (known) return [known];
  const t = ticker.toUpperCase(), dot = t.indexOf('.');
  if (dot > 0) { const b = t.slice(0, dot), c = t.slice(dot + 1).toLowerCase(); return [`${b}${c}`, `${b}${c}.O`]; }
  return [`${t}.O`, t, `${t}.K`];
}
/** 다른 회사 가격 연결 방지: 응답 티커가 요청 티커와 같은지 */
export function sameTicker(ticker, snap) {
  if (!snap.symbolCode) return true;
  const n = (s) => s.toUpperCase().replace(/[ -]/g, '.');
  return n(snap.symbolCode) === n(ticker);
}
