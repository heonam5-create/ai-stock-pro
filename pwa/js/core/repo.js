// 데이터 단일 진입점 — 실시간(프록시) 우선, 없으면 스냅샷(서버 수집 정적 JSON). 임의 가격 생성 금지.
import { live, local, direct, liveAvailable, friendly, NoLive } from './net.js';
import * as Q from './quote.js';
import { Clock, Events } from './clock.js';
import { analyze } from './analysis.js';
import { score, rank } from './recommender.js';
import { finishNews } from './news.js';
import { directory } from './directory.js';
import { Settings } from './settings.js';
import { Fmt } from './fmt.js';

const NAVER = '네이버 금융', YAHOO = 'Yahoo Finance';
const POLL = 'https://polling.finance.naver.com/api/realtime';
const DATA = './data';
const nowSec = () => Math.floor(Date.now() / 1000);
const tzOf = (m) => (m === 'KR' ? 'Asia/Seoul' : 'America/New_York');

export const enrich = (ref) => {
  const e = directory.bySymbol(ref.symbol); if (!e) return { ...ref, market: ref.market || (/\.(KS|KQ)$/.test(ref.symbol) ? 'KR' : 'US') };
  return { ...directory.ref(e), ...Object.fromEntries(Object.entries(ref).filter(([, v]) => v !== null && v !== undefined && v !== '')), naverCode: ref.naverCode || e.naverCode, yahoo: ref.yahoo || e.yahoo, nameKr: ref.nameKr || e.nameKr || null };
};
export const displayName = (r) => r.nameKr || r.name || r.symbol;
const code = (r) => r.symbol.split('.')[0];

// ── 스냅샷 ──
let snap = null, snapLoadedAt = 0;
export async function snapshot(force = false) {
  if (!force && snap && Date.now() - snapLoadedAt < 60_000) return snap;
  const [quotes, meta, indices] = await Promise.all([local(`${DATA}/snapshot/quotes.json`), local(`${DATA}/snapshot/meta.json`), local(`${DATA}/snapshot/indices.json`, { optional: true })]);
  snap = { quotes, meta, indices, bars: new Set(meta.bars), news: new Set(meta.news) }; snapLoadedAt = Date.now();
  return snap;
}

const cache = new Map();
const cached = async (key, ttlMs, fn) => { const c = cache.get(key); if (c && Date.now() - c.t < ttlMs) return c.v; const v = await fn(); cache.set(key, { t: Date.now(), v }); return v; };
export const clearCache = () => cache.clear();
const lastQuote = new Map();
const naverCodes = new Map();

// ── 시세 ──
function quoteFromSnap(ref, s, source, liveMode, extra = {}) {
  const now = nowSec(), kr = ref.market === 'KR';
  const open = Q.regularOpen(String(s.marketStatus).toUpperCase() === 'OPEN', ref.market, now * 1000);
  let fr;
  if (liveMode) fr = Q.freshness(open, s.tradedAt, now, s.delayMin, true);
  else {
    const age = now - extra.collectedAt;
    fr = open ? (age > 45 * 60 ? 'stale' : 'snapshot') : Q.freshness(false, s.tradedAt, now, 0, true);
  }
  const session = open ? 'regular' : 'closed';
  const ageMin = Math.floor((now - (liveMode ? s.tradedAt : extra.collectedAt)) / 60);
  const q = { ref: { ...ref, nameKr: ref.nameKr || s.name }, currency: s.currency, price: s.price, prevClose: s.prevClose, change: s.change, changePct: s.changePct,
    volume: s.volume, dayHigh: s.high, dayLow: s.low, high52: null, low52: null, marketCap: s.marketCap, marketCapSource: s.marketCap ? NAVER : null,
    marketTime: s.tradedAt, timezone: s.zone, marketOpen: open, session, freshness: fr, statusText: Q.statusText(fr, session, open, ageMin),
    source: liveMode ? NAVER : `${NAVER} (서버 수집 스냅샷)`, delayText: liveMode ? `${NAVER} · 지연 0분(공급원 표기 기준) · 프록시 경유` : `스냅샷 수집 ${Clock.fmt(extra.collectedAt, 'Asia/Seoul', true)} · 수집 주기만큼 지연`,
    ext: s.ext, check: extra.check || null, fetchedAt: Date.now(), warning: null, fallback: !liveMode, mode: liveMode ? 'live' : 'snapshot' };
  if (fr === 'stale') q.warning = liveMode ? `시세가 오래되었습니다 (체결 기준 ${Clock.fmt(s.tradedAt)}).` : `스냅샷이 ${ageMin}분 전 수집되어 장중 시세로 쓰기에 오래되었습니다. 매매 판단에 사용하지 마세요.`;
  else if (q.check?.mismatch) q.warning = q.check.message;
  else if (s.ext && !open && Math.abs(s.ext.changePct ?? 0) >= 2) q.warning = `${s.ext.label} 가격이 정규장 종가 대비 ${Fmt.pct(s.ext.changePct)} 차이 — 분석·매매가는 정규장 종가 기준입니다.`;
  if (kr && !liveMode && !q.warning && open) q.warning = null;
  return q;
}

async function naverLive(ref) {
  if (ref.market === 'KR') return [Q.parseNaverPolling(await live(`${POLL}/domestic/stock/${code(ref)}`), true), code(ref)];
  let last;
  for (const c of Q.usCandidates(ref.symbol, naverCodes.get(ref.symbol) || ref.naverCode)) {
    try {
      const s = Q.parseNaverPolling(await live(`${POLL}/worldstock/stock/${encodeURIComponent(c)}`), false);
      if (!Q.sameTicker(ref.symbol, s)) { last = new Error(`네이버 코드 ${c} 가 다른 종목으로 연결되어 사용하지 않습니다.`); continue; }
      naverCodes.set(ref.symbol, c); return [s, c];
    } catch (e) { last = e; }
  }
  throw last || new Error('네이버 금융에 해당 종목이 없습니다.');
}

async function yahooMeta(ref) {
  const sym = ref.market === 'KR' ? ref.symbol : (ref.yahoo || ref.symbol.replace('.', '-'));
  const j = await live(`https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(sym)}?interval=1d&range=5d`);
  const m = j?.chart?.result?.[0]?.meta; if (!m) throw new Error('Yahoo 응답 없음');
  return { price: m.regularMarketPrice, time: m.regularMarketTime, high52: m.fiftyTwoWeekHigh, low52: m.fiftyTwoWeekLow, volume: m.regularMarketVolume, tz: m.exchangeTimezoneName };
}

export async function quote(ref0) {
  const ref = enrich(ref0);
  let q;
  if (liveAvailable()) {
    const [nv, ym] = await Promise.allSettled([naverLive(ref), yahooMeta(ref)]);
    if (nv.status === 'fulfilled') {
      const [s, c] = nv.value; ref.naverCode = c;
      const open = Q.regularOpen(String(s.marketStatus).toUpperCase() === 'OPEN', ref.market);
      const y = ym.status === 'fulfilled' ? ym.value : null;
      const check = Q.crossCheck(s.price, s.tradedAt, open, YAHOO, y?.price, y?.time);
      q = quoteFromSnap(ref, s, NAVER, true, { check });
      if (y) { q.high52 = y.high52; q.low52 = y.low52; }
    } else if (ym.status === 'fulfilled') {
      const y = ym.value, open = Clock.isOpen(ref.market), now = nowSec();
      const fr = Q.freshness(open, y.time, now, null, false);
      q = { ref, currency: ref.market === 'KR' ? 'KRW' : 'USD', price: y.price, prevClose: null, change: null, changePct: null, volume: y.volume, dayHigh: null, dayLow: null,
        high52: y.high52, low52: y.low52, marketCap: null, marketTime: y.time, timezone: y.tz || tzOf(ref.market), marketOpen: open, session: open ? 'regular' : 'closed', freshness: fr,
        statusText: Q.statusText(fr, open ? 'regular' : 'closed', open, Math.floor((now - y.time) / 60)), source: YAHOO, delayText: `${YAHOO} · 지연 가능 / 대체 데이터 사용 중`,
        ext: null, check: { mismatch: false, message: `기본 공급원(네이버) 실패: ${friendly(nv.reason)} — 교차검증 불가` }, fetchedAt: Date.now(),
        warning: '지연 가능 / 대체 데이터 사용 중 — 기본 공급원(네이버) 조회 실패', fallback: true, mode: 'live' };
    }
  }
  if (!q) {
    try {
      const sp = await snapshot();
      const raw = sp.quotes.items[ref.symbol];
      if (!raw) throw new Error(liveAvailable() ? '실시간 조회 실패, 스냅샷에도 없는 종목입니다.' : '스냅샷에 없는 종목입니다 (실시간 프록시를 설정하면 조회 가능).');
      const s = Q.parseNaverItem(raw, ref.market === 'KR');
      q = quoteFromSnap(ref, s, NAVER, false, { collectedAt: sp.quotes.at, check: { mismatch: false, message: '스냅샷 모드 — 교차검증 생략 (네이버 단일 공급원)' } });
    } catch (e) {
      const old = lastQuote.get(ref.symbol);
      if (old) return { ...old, freshness: 'error', statusText: Q.statusText('error'), warning: `최신 시세를 받지 못했습니다 (${friendly(e)}). 표시된 값은 ${Clock.fmt(Math.floor(old.fetchedAt / 1000), 'Asia/Seoul', true)} 에 받은 데이터입니다.` };
      throw new Error(`${friendly(e)} 임의의 가격은 표시하지 않습니다.`);
    }
  }
  if (q.high52 == null) {
    try { const b = (await chart(ref, 'd1')).bars.slice(-252); q.high52 = Math.max(...b.map((x) => x.h)); q.low52 = Math.min(...b.map((x) => x.l)); } catch { /* 일봉 없음 */ }
  }
  lastQuote.set(ref.symbol, q);
  return q;
}

// ── 차트 ──
export const INTERVALS = [['m1', '1분'], ['m5', '5분'], ['m15', '15분'], ['m30', '30분'], ['h1', '1시간'], ['d1', '일봉'], ['w1', '주봉'], ['mo1', '월봉']];
const intraday = (i) => ['m1', 'm5', 'm15', 'm30', 'h1'].includes(i);
const ymdhm = (sec, tz) => Clock.fmt(sec, tz).replace(/[-: ]/g, '');

function parseCandles(arr, tz, openHM) {
  return (arr || []).map((o) => {
    const c = Q.num(o.closePrice ?? o.currentPrice); if (!(c > 0)) return null;
    const t = o.localDate ? Clock.parseLocal(o.localDate + openHM, tz) : o.localDateTime ? Clock.parseLocal(o.localDateTime, tz) : null; if (!t) return null;
    const op = Q.num(o.openPrice) > 0 ? Q.num(o.openPrice) : c;
    return { t, o: op, h: Math.max(Q.num(o.highPrice) ?? c, op, c), l: Math.min(Q.num(o.lowPrice) ?? c, op, c), c, v: Q.num(o.accumulatedTradingVolume) ?? 0 };
  }).filter(Boolean).sort((a, b) => a.t - b.t);
}
function aggregate(bars, minutes) {
  if (minutes <= 1) return bars; const step = minutes * 60, out = [];
  for (const b of bars) { const k = b.t - (b.t % step), l = out.at(-1); if (l && l.t === k) { l.h = Math.max(l.h, b.h); l.l = Math.min(l.l, b.l); l.c = b.c; l.v += b.v; } else out.push({ ...b, t: k }); }
  return out;
}
function weekly(bars, tz) {
  const out = [];
  for (const b of bars) {
    const d = new Date(Clock.day(b.t, tz) + 'T00:00:00Z'), wk = Math.floor((d.getTime() / 86400000 + 3) / 7);
    const l = out.at(-1); if (l && l.wk === wk) { l.h = Math.max(l.h, b.h); l.l = Math.min(l.l, b.l); l.c = b.c; l.v += b.v; } else out.push({ ...b, wk });
  }
  return out.map(({ wk, ...b }) => b);
}
function yahooParse(j) {
  const r = j?.chart?.result?.[0]; if (!r) throw new Error('Yahoo 차트 없음');
  const q = r.indicators?.quote?.[0] || {}, out = [];
  (r.timestamp || []).forEach((t, i) => { const c = q.close?.[i]; if (!(c > 0)) return; const o = q.open?.[i] ?? c; out.push({ t, o, h: Math.max(q.high?.[i] ?? c, o, c), l: Math.min(q.low?.[i] ?? c, o, c), c, v: q.volume?.[i] ?? 0 }); });
  return { bars: out, tz: r.meta?.exchangeTimezoneName };
}

export async function chart(ref0, interval) {
  const ref = enrich(ref0), kr = ref.market === 'KR', tz = tzOf(ref.market);
  return cached(`chart|${ref.symbol}|${interval}`, intraday(interval) ? 60_000 : 120_000, async () => {
    const errs = [];
    if (liveAvailable()) {
      if (!intraday(interval) || kr) {
        try {
          const unit = { d1: 'day', w1: 'week', mo1: 'month' }[interval] || 'minute';
          const years = interval === 'mo1' ? 25 : interval === 'w1' ? 10 : 2;
          const start = nowSec() - (intraday(interval) ? 6 * 86400 : years * 365 * 86400);
          const c = kr ? code(ref) : (naverCodes.get(ref.symbol) || ref.naverCode || `${ref.symbol}.O`);
          const j = await live(`https://api.stock.naver.com/chart/${kr ? 'domestic' : 'foreign'}/item/${encodeURIComponent(c)}/${unit}?startDateTime=${ymdhm(start, tz)}&endDateTime=${ymdhm(nowSec() + 86400, tz)}`);
          let bars = parseCandles(j, tz, kr ? '090000' : '093000');
          if (intraday(interval)) bars = aggregate(bars, { m1: 1, m5: 5, m15: 15, m30: 30, h1: 60 }[interval]);
          if (bars.length >= 2) return { bars, interval, tz, source: NAVER };
          errs.push('네이버: 데이터 부족');
        } catch (e) { errs.push(`네이버: ${friendly(e)}`); }
      }
      try {
        const yi = { m1: ['1m', '5d'], m5: ['5m', '1mo'], m15: ['15m', '1mo'], m30: ['30m', '1mo'], h1: ['60m', '3mo'], d1: ['1d', '2y'], w1: ['1wk', '10y'], mo1: ['1mo', 'max'] }[interval];
        const sym = kr ? ref.symbol : (ref.yahoo || ref.symbol.replace('.', '-'));
        const y = yahooParse(await live(`https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(sym)}?interval=${yi[0]}&range=${yi[1]}`));
        if (y.bars.length >= 2) return { bars: y.bars, interval, tz: y.tz || tz, source: YAHOO };
      } catch (e) { errs.push(`Yahoo: ${friendly(e)}`); }
    }
    // 스냅샷: 상위 종목 일봉(약 300개) — 주봉은 일봉으로 계산, 분봉·월봉은 미제공
    if (interval === 'd1' || interval === 'w1') {
      const sp = await snapshot();
      if (sp.bars.has(ref.symbol)) {
        const rows = await local(`${DATA}/snapshot/bars/${encodeURIComponent(ref.symbol)}.json`);
        let bars = rows.map(([d, o, h, l, c, v]) => ({ t: Clock.parseLocal(d + (kr ? '090000' : '093000'), tz), o, h, l, c, v }));
        if (interval === 'w1') bars = weekly(bars, tz);
        return { bars, interval, tz, source: `${NAVER} (스냅샷 일봉 ${rows.at(-1)[0]})` };
      }
      errs.push(`스냅샷 일봉은 시가총액 상위 ${sp.meta.topKr}+${sp.meta.topUs}개 종목만 제공`);
    } else errs.push('스냅샷 모드는 일봉·주봉만 제공');
    throw new Error(`${displayName(ref)} 차트를 불러오지 못했습니다 (${errs.join(' / ')}). 실시간 프록시를 설정하면 전 종목 차트를 볼 수 있습니다.`);
  });
}

export async function alignedDaily(q) {
  const cd = await chart(q.ref, 'd1');
  return { bars: Q.alignDaily(cd.bars, q.price, q.marketTime, q.timezone || cd.tz, q.dayHigh, q.dayLow, q.volume), source: cd.source };
}

// ── 검색 ──
export async function search(query) {
  const out = directory.search(query).map((e) => directory.ref(e));
  if (liveAvailable() && out.length < 8 && query.trim()) {
    try {
      const j = await live(`https://ac.stock.naver.com/ac?q=${encodeURIComponent(query)}&target=stock`);
      for (const a of j.items || []) {
        let r = null;
        if (a.nationCode === 'KOR') r = directory.byKrCode(a.code) ? directory.ref(directory.byKrCode(a.code)) : { symbol: `${a.code}.${a.typeCode === 'KOSDAQ' ? 'KQ' : 'KS'}`, name: a.name, nameKr: a.name, market: 'KR', exchange: a.typeName };
        else if (a.nationCode === 'USA') { const t = a.code.replace(/ /g, '.'); r = directory.bySymbol(t) ? directory.ref(directory.bySymbol(t)) : { symbol: t, name: a.name, nameKr: a.name, market: 'US', exchange: a.typeCode, naverCode: a.reutersCode, yahoo: t.replace('.', '-') }; }
        if (r && !out.some((x) => x.symbol === r.symbol)) out.push(r);
      }
    } catch { /* 온라인 보강 실패는 무시 */ }
  }
  return out.slice(0, 30);
}

// ── 뉴스·일정 ──
const KST = 'Asia/Seoul';
export async function news(ref0, force = false) {
  const ref = enrich(ref0), kr = ref.market === 'KR';
  return cached(`news|${ref.symbol}`, force ? 0 : 600_000, async () => {
    const all = [];
    if (liveAvailable()) {
      try {
        if (kr) {
          const j = await live(`https://m.stock.naver.com/api/news/stock/${code(ref)}?pageSize=20&page=1`);
          for (const g of j || []) for (const o of g.items || []) all.push({ title: o.titleFull || o.title, source: o.officeName || '네이버 뉴스', link: o.mobileNewsUrl || '', publishedAt: Clock.parseLocal(o.datetime, KST), relatedName: displayName(ref) });
        } else {
          const c = naverCodes.get(ref.symbol) || ref.naverCode || `${ref.symbol}.O`;
          const j = await live(`https://api.stock.naver.com/news/worldStock/${encodeURIComponent(c)}?pageSize=20&page=1`);
          for (const o of j || []) all.push({ title: o.tit, source: o.ohnm || '네이버 해외증시', link: `https://m.stock.naver.com/worldstock/news/${o.oid}/${o.aid}`, publishedAt: Clock.parseLocal(o.dt, KST), relatedName: displayName(ref) });
        }
      } catch { /* 다음 공급원 */ }
      try { all.push(...parseRss(await live(googleUrl(kr ? `"${ref.nameKr || ref.name}" 주가` : `"${ref.name}" OR ${ref.symbol} stock`, kr), { text: true }), displayName(ref))); } catch { /* 무시 */ }
    }
    if (!all.length) {
      const sp = await snapshot();
      if (sp.news.has(ref.symbol)) for (const o of await local(`${DATA}/snapshot/news/${encodeURIComponent(ref.symbol)}.json`)) all.push({ title: o.title, source: o.source, link: o.link, publishedAt: Clock.parseLocal(o.dt, KST), relatedName: displayName(ref) });
      else if (!liveAvailable()) throw new Error(`스냅샷 뉴스는 시가총액 상위 종목만 제공합니다. 실시간 프록시를 설정하면 모든 종목 뉴스를 볼 수 있습니다.`);
    }
    return finishNews(all.filter((n) => n.title && n.publishedAt));
  });
}
const googleUrl = (q, ko) => { const s = encodeURIComponent(`${q} when:7d`); return ko ? `https://news.google.com/rss/search?q=${s}&hl=ko&gl=KR&ceid=KR:ko` : `https://news.google.com/rss/search?q=${s}&hl=en-US&gl=US&ceid=US:en`; };
function parseRss(xml, related) {
  const doc = new DOMParser().parseFromString(xml, 'text/xml');
  return [...doc.querySelectorAll('item')].map((it) => {
    let title = it.querySelector('title')?.textContent?.trim() || '', src = it.querySelector('source')?.textContent?.trim() || '';
    if (src && title.endsWith(' - ' + src)) title = title.slice(0, -src.length - 3);
    const t = Date.parse(it.querySelector('pubDate')?.textContent || '');
    return { title, source: src || 'Google 뉴스', link: it.querySelector('link')?.textContent?.trim() || '', publishedAt: Number.isNaN(t) ? null : Math.floor(t / 1000), relatedName: related };
  });
}
export async function macroNews() {
  return cached('macro', 900_000, async () => {
    if (liveAvailable()) {
      const all = [];
      for (const [q, ko] of [['FOMC OR 금리 OR CPI OR 고용지표 OR 연준', true], ['반도체 업황 OR HBM OR 메모리 가격', true], ['Fed OR CPI OR "jobs report" stocks', false]]) {
        try { all.push(...parseRss(await live(googleUrl(q, ko), { text: true }), null)); } catch { /* 무시 */ }
      }
      if (all.length) return finishNews(all).slice(0, 30);
    }
    const m = await local(`${DATA}/snapshot/macro.json`, { optional: true });
    return finishNews((m?.items || []).map((o) => ({ title: o.title, source: o.source, link: o.link, publishedAt: o.ts }))).slice(0, 30);
  });
}
export async function events(ref) {
  const list = Events.upcomingMacro(); const k = Settings.get('finnhubKey', '');
  if (ref.market === 'US' && k) {
    try {
      const f = (s) => Clock.day(s, 'America/New_York'), now = nowSec();
      const j = await direct(`https://finnhub.io/api/v1/calendar/earnings?from=${f(now)}&to=${f(now + 90 * 86400)}&symbol=${ref.symbol}&token=${k}`);
      for (const o of j.earningsCalendar || []) list.push({ date: o.date, title: `${ref.symbol} 실적 발표 ${o.hour === 'bmo' ? '장 시작 전' : o.hour === 'amc' ? '장 마감 후' : ''}`.trim(), detail: o.epsEstimate != null ? `EPS 예상 ${Fmt.d(o.epsEstimate)}` : 'EPS 예상치 정보 없음', daysLeft: Math.round((Date.parse(o.date) - Date.parse(f(now))) / 86400000) });
    } catch { /* 키 오류 등 무시 */ }
  }
  return list.sort((a, b) => a.daysLeft - b.daysLeft);
}

// ── 분석 ──
export async function analyzeFull(ref0) {
  const ref = enrich(ref0);
  const q = await quote(ref);
  let ns = null, newsError = null;
  try { ns = await news(q.ref); } catch (e) { newsError = friendly(e); }
  const ev = await events(q.ref);
  const { bars, source } = await alignedDaily(q);
  const result = analyze({ bars, price: q.price, isKr: q.ref.market === 'KR', news: ns, events: ev });
  return { quote: q, result, news: ns, events: ev, newsError, barsSource: source };
}

// ── BEST 5 ──
export async function best5(market, progress, includeWatch = true, watchlist = []) {
  const kr = market === 'KR', deep = 80;
  progress?.(0, 1, '후보군 수집 중');
  const cands = []; let scope;
  if (liveAvailable()) {
    const plan = kr ? [['KOSPI', 1], ['KOSPI', 2], ['KOSDAQ', 1]] : [['NASDAQ', 1], ['NASDAQ', 2], ['NYSE', 1], ['NYSE', 2]];
    for (const [m, pg] of plan) {
      try {
        const dom = m === 'KOSPI' || m === 'KOSDAQ';
        const j = await live(dom ? `https://m.stock.naver.com/api/stocks/marketValue/${m}?page=${pg}&pageSize=100` : `https://api.stock.naver.com/stock/exchange/${m}/marketValue?page=${pg}&pageSize=100`);
        for (const s of j.stocks || []) {
          const sym = dom ? `${s.itemCode}.${m === 'KOSPI' ? 'KS' : 'KQ'}` : String(s.symbolCode || '').replace(/ /g, '.');
          if (sym) cands.push({ ref: enrich({ symbol: sym, name: s.stockNameEng || s.stockName, nameKr: s.stockName, market, naverCode: dom ? null : s.reutersCode }), price: Q.num(s.closePriceRaw ?? s.closePrice), tv: Q.num(s.accumulatedTradingValueRaw) });
        }
      } catch { /* 다음 */ }
    }
    scope = kr ? '코스피 시총 상위 200 + 코스닥 시총 상위 100 (실시간)' : '나스닥 시총 상위 200 + NYSE 시총 상위 200 (실시간)';
  }
  if (!cands.length) {
    const sp = await snapshot();
    for (const sym of sp.meta.bars) {
      const raw = sp.quotes.items[sym]; if (!raw) continue;
      const isKr = /\.(KS|KQ)$/.test(sym); if (isKr !== kr) continue;
      cands.push({ ref: enrich({ symbol: sym, name: raw.stockName, nameKr: raw.stockName, market }), price: Q.num(raw.closePriceRaw ?? raw.closePrice), tv: Q.num(raw.accumulatedTradingValueRaw) });
    }
    scope = kr ? `스냅샷: 일봉 보유 시총 상위 종목 (${Clock.fmt(sp.quotes.at)} 수집)` : `스냅샷: 일봉 보유 시총 상위 종목 (${Clock.fmt(sp.quotes.at)} 수집)`;
  }
  const seen = new Set(), uniq = cands.filter((c) => !seen.has(c.ref.symbol) && seen.add(c.ref.symbol));
  const minTv = kr ? 5e9 : 1e8, minP = kr ? 1000 : 5;
  let liquid = uniq.filter((c) => { const n = displayName(c.ref); if (/스팩|ACQUISITION| ETF/i.test(n)) return false; return c.tv == null || (c.tv >= minTv && (c.price ?? 0) >= minP); })
    .sort((a, b) => (b.tv ?? 0) - (a.tv ?? 0));
  const liquidCount = liquid.length; liquid = liquid.slice(0, deep);
  if (includeWatch) for (const w of watchlist) if ((/\.(KS|KQ)$/.test(w.symbol) ? 'KR' : 'US') === market && !liquid.some((c) => c.ref.symbol === w.symbol)) liquid.push({ ref: enrich({ symbol: w.symbol, name: w.name, nameKr: w.name }) });
  const total = liquid.length, first = [], failed = []; let done = 0, stale = 0;
  for (let i = 0; i < liquid.length; i += 3) {
    await Promise.all(liquid.slice(i, i + 3).map(async (c) => {
      try {
        const q = await quote(c.ref); if (Q.unreliable(q)) { stale++; return; }
        const { bars } = await alignedDaily(q);
        first.push({ q, r: analyze({ bars, price: q.price, isKr: kr, news: null, events: Events.upcomingMacro() }), bars });
      } catch { failed.push(displayName(c.ref)); } finally { done++; }
    }));
    progress?.(done, total, `일봉 분석 중 ${done}/${total}`);
  }
  const prelim = first.map((x) => ({ ...x, s: score(x.q.ref, x.r, x.q.marketTime) })).filter((x) => x.s).sort((a, b) => b.s.total - a.s.total).slice(0, 10);
  progress?.(total, total, `상위 ${prelim.length}개 뉴스 반영 재분석 중`);
  const finals = [];
  for (const x of prelim) {
    let ns = null; try { ns = await news(x.q.ref); } catch { /* 뉴스 없음 → 해당 항목 제외 */ }
    const r = analyze({ bars: x.bars, price: x.q.price, isKr: kr, news: ns, events: await events(x.q.ref) });
    const s = score(x.q.ref, r, x.q.marketTime); if (s) finals.push(s);
  }
  return { picks: rank(finals), candidates: uniq.length, liquid: liquidCount, analyzed: first.length + stale, failed, excluded: first.length - first.filter((x) => score(x.q.ref, x.r, 0)).length,
    staleExcluded: stale, scope, at: nowSec(), mode: liveAvailable() ? 'live' : 'snapshot' };
}

// ── 홈 지수 ──
export async function indices() {
  if (liveAvailable()) {
    const out = [];
    for (const [c, n, dom] of [['KOSPI', '코스피', true], ['KOSDAQ', '코스닥', true], ['.INX', 'S&P 500', false], ['.IXIC', '나스닥', false]]) {
      try { const s = Q.parseNaverPolling(await live(`${POLL}/${dom ? 'domestic' : 'worldstock'}/index/${encodeURIComponent(c)}`), dom); out.push({ name: n, price: s.price, pct: s.changePct, time: s.tradedAt, source: NAVER }); } catch { /* 다음 */ }
    }
    const fx = await usdKrw(); if (fx) out.push({ name: '원/달러', price: fx.price, pct: null, time: fx.time, source: fx.source });
    if (out.length) return out;
  }
  const sp = await snapshot();
  return Object.entries(sp.indices?.items || {}).map(([n, v]) => ({ name: n, price: v.price, pct: v.pct, time: v.ts ?? Q.isoSec(v.localTradedAt) ?? sp.indices.at, source: v.source || `${NAVER} (스냅샷)` }));
}
export async function usdKrw() {
  if (liveAvailable()) { try { const m = await yahooMeta({ symbol: 'KRW=X', market: 'KR' }); return { price: m.price, time: m.time, source: YAHOO }; } catch { /* 스냅샷 */ } }
  try { const sp = await snapshot(); const v = sp.indices?.items?.['원/달러']; if (v) return { price: v.price, time: v.ts, source: `${YAHOO} (스냅샷)` }; } catch { /* 없음 */ }
  return null;
}

// ── 외부 AI (선택, 사용자 본인 키 · 브라우저에서 직접 호출) ──
export function aiFacts(q, r) {
  const kr = q.ref.market === 'KR';
  let s = `종목: ${displayName(q.ref)} (${q.ref.symbol})\n정규장 가격: ${Fmt.price(q.price, kr)} (${Fmt.pct(q.changePct)}), 상태 ${q.statusText}, 기준시각 ${Clock.format(q.marketTime, q.ref.market)}, 출처 ${q.source}\n로컬 엔진 판단: ${r.verdict}, 점수 ${r.score}/100\n`;
  r.factors.forEach((f) => { s += `- ${f.title}: ${f.status} / ${f.detail}\n`; });
  if (r.strategy) s += `매수가: ${r.strategy.buys.map((b) => `${b.label} ${Fmt.price(b.price, kr)}`).join(', ')}\n목표가: ${r.strategy.targets.map((b) => `${b.label} ${Fmt.price(b.price, kr)}`).join(', ')}\n손절가: ${Fmt.price(r.strategy.stop.price, kr)}\n`;
  return s;
}
const aiUrl = (ep) => { const e = ep.trim().replace(/\/+$/, ''); return e.endsWith('/chat/completions') ? e : e + '/chat/completions'; };
export async function aiComment(q, r, test = false) {
  const ep = Settings.get('aiEndpoint', ''), model = Settings.get('aiModel', ''), key = Settings.get('aiKey', '');
  if (!ep || !model || !key) throw new Error('설정에서 외부 AI 주소·모델·키를 입력하세요.');
  const body = test ? { model, max_tokens: 10, messages: [{ role: 'user', content: "연결 테스트입니다. '정상' 이라고만 답하세요." }] }
    : { model, temperature: 0.3, messages: [
      { role: 'system', content: '너는 신중한 주식 분석 보조자다. 아래 제공된 데이터만 근거로 한국어로 5~8문장 의견을 써라. 너는 인터넷이나 시세를 직접 조회할 수 없으므로 제공되지 않은 숫자·뉴스를 지어내지 말고, 조회한 것처럼 말하지 마라. 투자 권유가 아니라는 점을 마지막에 한 줄로 밝혀라.' },
      { role: 'user', content: aiFacts(q, r) }] };
  const j = await direct(aiUrl(ep), { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${key}` }, body: JSON.stringify(body) });
  const t = j?.choices?.[0]?.message?.content; if (!t) throw new Error('외부 AI 응답 형식을 해석하지 못했습니다.');
  return t;
}
export { NoLive };
