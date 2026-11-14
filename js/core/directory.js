// 한국·미국 전체 종목 목록 검색 (오프라인 — 시세 조회와 분리: 시세가 막혀도 검색 동작)
// 목록: KRX KIND 상장법인목록 + 네이버 금융 + Nasdaq Trader (매주 GitHub Actions 로 갱신)
export const norm = (s) => (s || '').toLowerCase().replace(/[\s\-.&,'()]/g, '');
const CHO = 'ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ';
export const chosung = (s) => [...(s || '')].map((ch) => { const c = ch.charCodeAt(0); return c >= 0xac00 && c <= 0xd7a3 ? CHO[Math.floor((c - 0xac00) / 588)] : ''; }).join('');
const isCho = (q) => q.length > 0 && [...q].every((c) => CHO.includes(c));
function lev(a, b) {
  const p = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) { let prev = p[0]; p[0] = i; for (let j = 1; j <= b.length; j++) { const t = p[j]; p[j] = Math.min(p[j] + 1, p[j - 1] + 1, prev + (a[i - 1] === b[j - 1] ? 0 : 1)); prev = t; } }
  return p[b.length];
}

export const ALIASES = { '005930.KS': ['삼전'], '000660.KS': ['하이닉스', 'sk hynix'], '373220.KS': ['엘솔', '엘지에너지솔루션'], '207940.KS': ['삼바'], '005380.KS': ['현대자동차'],
  '000270.KS': ['기아차'], '035420.KS': ['네이버'], '005490.KS': ['포스코'], NVDA: ['엔비', '엔비디아'], TSLA: ['테슬라'], AAPL: ['애플'], MSFT: ['마소', '마이크로소프트'],
  GOOGL: ['구글', '알파벳'], AMZN: ['아마존'], META: ['메타', '페이스북'], 'BRK.B': ['버크셔'], MU: ['마이크론'], SNDK: ['샌디스크'], AMD: ['에이엠디'], TSM: ['tsmc'] };

export class Directory {
  constructor() { this.entries = []; this.by = new Map(); this.updated = { kr: '-', us: '-' }; }
  load(krDoc, usDoc) {
    const list = [];
    if (krDoc) { this.updated.kr = krDoc.updated; for (const [code, name, mk, rank] of krDoc.items) list.push(this.make(`${code}.${mk}`, name, '', 'KR', mk === 'KS' ? '코스피' : '코스닥', null, `${code}.${mk}`, rank)); }
    if (usDoc) { this.updated.us = usDoc.updated; for (const [t, kr, en, ex, nc, y, rank] of usDoc.items) list.push(this.make(t, kr, en, 'US', ex, nc || null, y, rank)); }
    this.entries = list; this.by = new Map(list.map((e) => [e.symbol, e]));
    return this;
  }
  make(symbol, nameKr, nameEn, market, exchange, naverCode, yahoo, rank) {
    const short = (nameEn || '').replace(/(,? inc\.?|,? corporation|,? corp\.?|,? ltd\.?|,? plc| common stock| class [a-z]| ordinary shares?)/gi, '');
    const keys = [...new Set([nameKr, nameEn, short, symbol, symbol.split('.')[0], ...(ALIASES[symbol] || [])].map(norm).filter(Boolean))];
    return { symbol, nameKr, nameEn, market, exchange, naverCode, yahoo, rank, keys, cho: chosung(nameKr) };
  }
  ref(e) { return { symbol: e.symbol, name: e.nameEn || e.nameKr, nameKr: e.nameKr || null, exchange: e.exchange, market: e.market, naverCode: e.naverCode, yahoo: e.yahoo }; }
  get counts() { let kr = 0, us = 0; for (const e of this.entries) e.market === 'KR' ? kr++ : us++; return { kr, us }; }
  bySymbol(s) { return this.by.get(s) || this.by.get(String(s).toUpperCase()); }
  byKrCode(c) { return this.by.get(`${c.toUpperCase()}.KS`) || this.by.get(`${c.toUpperCase()}.KQ`); }
  /** 정확 > 앞부분 > 포함 > 초성 > 유사(오타 1~2자). 같은 점수는 시가총액 순위 */
  search(query, limit = 30) {
    const raw = (query || '').trim().replace(/\s/g, ''), q = norm(query);
    if (!q) return [];
    const cho = isCho(raw), scored = [];
    for (const e of this.entries) {
      let best = 0;
      for (const k of e.keys) { if (k === q) { best = 100; break; } if (k.startsWith(q)) best = Math.max(best, 80); else if (q.length >= 2 && k.includes(q)) best = Math.max(best, 60); }
      if (!best && cho && e.cho) { if (e.cho.startsWith(raw)) best = 55; else if (e.cho.includes(raw)) best = 45; }
      if (best) scored.push([e, best]);
    }
    if (!scored.length && q.length >= 3) {
      const maxD = q.length >= 6 ? 2 : 1;
      for (const e of this.entries) for (const k of e.keys) { if (Math.abs(k.length - q.length) <= maxD && k.length <= 30 && lev(k, q) <= maxD) { scored.push([e, 30]); break; } }
    }
    return scored.sort((a, b) => b[1] - a[1] || a[0].rank - b[0].rank).slice(0, limit).map((x) => x[0]);
  }
  top(market, n) { return this.entries.filter((e) => e.market === market).sort((a, b) => a.rank - b.rank).slice(0, n); }
}
export const directory = new Directory();
