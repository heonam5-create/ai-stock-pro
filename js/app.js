// AI 주식 분석센터 PRO — PWA (아이폰 Safari 홈 화면 앱). 화면 구성은 안드로이드·iOS 앱과 동일 (홈·종목 분석·BEST 5·관심 종목·설정)
import * as R from './core/repo.js';
import { directory } from './core/directory.js';
import { Settings } from './core/settings.js';
import { Fmt } from './core/fmt.js';
import { Clock, Events } from './core/clock.js';
import { VERDICT } from './core/analysis.js';
import { SENT_LABEL } from './core/news.js';
import * as Q from './core/quote.js';
import { liveAvailable, friendly, proxyBase, local } from './core/net.js';
import { mountChart, MA_COLORS } from './chart.js';

const $ = (s, el = document) => el.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const isKr = (sym) => /\.(KS|KQ)$/.test(sym);
const cc = (v) => (v == null || Number.isNaN(v) || v === 0 ? 'var(--sub)' : v > 0 ? 'var(--up)' : 'var(--down)');
const vcolor = (v) => ({ strongBuy: 'var(--up)', splitBuy: 'var(--orange)', hold: 'var(--sub)', splitSell: 'var(--down)', avoid: '#9333EA', na: 'var(--sub)' })[v];
const scol = (s) => (s >= 2 ? 'var(--up)' : s === 1 ? 'var(--orange)' : s === 0 ? 'var(--sub)' : s === -1 ? 'var(--down)' : '#9333EA');
const fcol = (f) => ({ realtime: 'var(--green)', delayed: 'var(--accent)', snapshot: 'var(--accent)', closed: 'var(--sub)', stale: 'var(--orange)', error: 'var(--orange)' })[f];
const pill = (t, c) => `<span class="pill" style="color:${c};background:color-mix(in srgb, ${c} 18%, transparent)">${esc(t)}</span>`;
const kv = (k, v, c) => `<div class="kv"><div class="k">${esc(k)}</div><div class="v"${c ? ` style="color:${c}"` : ''}>${esc(v)}</div></div>`;
const card = (title, body, action = '') => `<section class="card"><div class="ct"><h2>${esc(title)}</h2>${action}</div>${body}</section>`;
const loading = (t = '불러오는 중…') => `<div class="loading"><div class="spin"></div>${esc(t)}</div>`;
const errBox = (m, act) => `<div class="banner">⚠ ${esc(m)}</div>${act ? `<button class="btn2" data-act="${act}">다시 시도</button>` : ''}`;
const disclaimer = '<p class="disclaimer">※ 본 앱의 분석·추천은 투자 권유가 아닌 참고용 정보입니다. 무료 공개 데이터는 지연·누락될 수 있으며, 투자 판단과 결과의 책임은 본인에게 있습니다.</p>';
const toast = (m) => { const t = $('#toast'); t.textContent = m; t.hidden = false; clearTimeout(toast.t); toast.t = setTimeout(() => (t.hidden = true), 2600); };
const header = (sub, right = '') => `<header class="hd"><div class="t"><h1>AI 주식 분석센터 PRO</h1><div class="s">${esc(sub)}</div></div>${right}</header>`;
const modeBanner = () => (liveAvailable() ? '' : `<div class="card banner blue" style="margin:6px 12px">지금은 <b>스냅샷 모드</b>입니다: 서버가 주기적으로 모은 네이버 시세를 표시하며 <b>지연</b>되어 있습니다(수집 시각 표시). 실시간 시세는 설정 › 실시간 프록시에서 무료로 연결할 수 있습니다.</div>`);

const S = {
  tab: 'home', query: '', results: [], selected: null, quote: { s: 'idle' }, chart: { s: 'idle' }, interval: 'd1', unsupported: {},
  analysis: { s: 'idle' }, news: { s: 'idle' }, ai: { s: 'idle' }, refreshing: false,
  overlays: { ma: new Set([5, 20, 60, 120]), bb: false, vwap: false, levels: true, zones: true, trend: true }, sub: 'volume',
  home: { idx: { s: 'idle' }, macro: { s: 'idle' } }, best: { market: 'KR', KR: { s: 'idle' }, US: { s: 'idle' }, prog: [0, 0, ''] },
  watch: { tab: 0, quotes: {}, errors: {}, loading: false, fx: null },
  watchlist: Settings.get('watchlist', []), alerts: Settings.get('alerts', []), recent: Settings.get('recent', []),
};
let chartInst = null;
const save = () => { Settings.set('watchlist', S.watchlist); Settings.set('alerts', S.alerts); Settings.set('recent', S.recent.slice(0, 10)); };

// ── 라우팅 ──
function go(tab) { S.tab = tab; render(); window.scrollTo(0, 0); if (tab === 'home') loadHome(); if (tab === 'watch') refreshWatch(); }
document.querySelectorAll('#tabs button').forEach((b) => b.addEventListener('click', () => go(b.dataset.tab)));

function render() {
  document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('on', b.dataset.tab === S.tab));
  chartInst?.destroy(); chartInst = null;
  const v = $('#view');
  v.innerHTML = { home: viewHome, analysis: viewAnalysis, best: viewBest, watch: viewWatch, settings: viewSettings }[S.tab]();
  const cv = $('#chart'); if (cv && S.chart.s === 'ok') {
    const cd = S.chart.v;
    chartInst = mountChart(cv, { bars: cd.bars, intraday: !['d1', 'w1', 'mo1'].includes(cd.interval), tz: cd.tz, kr: S.selected?.market === 'KR', overlays: S.overlays, sub: S.sub, levels: chartLevels() });
  }
  const inp = $('#q'); if (inp && document.activeElement?.id !== 'q' && S.focusSearch) { inp.focus(); S.focusSearch = false; }
}
const rerenderKeepScroll = () => { const y = window.scrollY; render(); window.scrollTo(0, y); };

// ── 검색 ──
const searchBox = (ph = '종목명·초성·6자리 코드·티커 (예: 삼성전자, ㅅㅅㅈㅈ, 042700, NVDA)') =>
  `<div class="search"><span>🔍</span><input id="q" type="search" enterkeyhint="search" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" placeholder="${esc(ph)}" value="${esc(S.query)}"></div><div id="results">${resultsHtml()}</div>`;
function resultsHtml() {
  if (!S.query.trim()) return '';
  const list = S.results.slice(0, 20).map((r) => `<div class="stock" data-open="${esc(r.symbol)}"><div class="grow"><div class="n">${esc(R.displayName(r))}</div><div class="c">${esc(r.symbol)} · ${esc(r.exchange || (r.market === 'KR' ? '한국' : '미국'))}${r.nameKr && r.name !== r.nameKr ? ' · ' + esc(r.name) : ''}</div></div><span class="muted">›</span></div>`).join('');
  return card(`검색 결과 (${S.results.length})`, list || '<div class="muted">일치하는 종목이 없습니다. 초성·코드·영문 티커로도 검색해 보세요.</div>');
}
let searchT;
document.addEventListener('input', (e) => {
  if (e.target.id !== 'q') return;
  S.query = e.target.value; clearTimeout(searchT);
  S.results = directory.search(S.query).map((x) => directory.ref(x)); $('#results').innerHTML = resultsHtml();
  searchT = setTimeout(async () => { const q = S.query; if (!q.trim()) return; const r = await R.search(q); if (q === S.query) { S.results = r; const el = $('#results'); if (el) el.innerHTML = resultsHtml(); } }, 450);
});
const findRef = (sym) => S.results.find((r) => r.symbol === sym) || (directory.bySymbol(sym) ? directory.ref(directory.bySymbol(sym)) : null) || S.recent.find((r) => r.symbol === sym) || { symbol: sym, name: sym, market: isKr(sym) ? 'KR' : 'US' };

// ── 클릭 처리 (이벤트 위임) ──
document.addEventListener('click', async (e) => {
  const o = e.target.closest('[data-open]');
  if (o) { const ref = findRef(o.dataset.open); if (S.tab === 'watch' && S.watch.adding) { addWatch(ref); S.query = ''; S.results = []; S.watch.adding = false; render(); } else open(ref); return; }
  const a = e.target.closest('[data-act]'); if (!a) return;
  const act = a.dataset.act, arg = a.dataset.arg;
  const acts = {
    homeRefresh: () => loadHome(true), quoteRefresh: () => refreshQuote(true), loadChart: () => loadChart(), analyze: () => runAnalysis(), news: () => loadNews(true),
    interval: () => { if (!S.unsupported[arg]) { S.interval = arg; loadChart(); } },
    ma: () => { const m = +arg; S.overlays.ma.has(m) ? S.overlays.ma.delete(m) : S.overlays.ma.add(m); rerenderKeepScroll(); },
    ov: () => { S.overlays[arg] = !S.overlays[arg]; rerenderKeepScroll(); }, sub: () => { S.sub = arg; rerenderKeepScroll(); },
    heart: () => { S.selected && (isWatched(S.selected.symbol) ? removeWatch(S.selected.symbol) : addWatch(S.selected)); rerenderKeepScroll(); },
    ai: () => runAi(), quick: () => open(findRef(arg)),
    bestMarket: () => { S.best.market = arg; render(); }, best: () => runBest(S.best.market),
    watchTab: () => { S.watch.tab = +arg; render(); }, watchRefresh: () => refreshWatch(), unwatch: () => { removeWatch(arg); render(); },
    holding: () => holdingDialog(arg), calc: () => calcDialog(arg), addAlertQuick: () => { const [t, v] = arg.split('|'); addAlert(S.selected.symbol, R.displayName(S.selected), t, +v); a.textContent = '등록됨'; a.disabled = true; },
    addAlert: () => alertDialog(), toggleAlert: () => { const x = S.alerts.find((x) => x.id === +arg); if (x) { x.enabled = !x.enabled; x.lastFired = 0; save(); render(); } },
    delAlert: () => { S.alerts = S.alerts.filter((x) => x.id !== +arg); save(); render(); }, checkAlerts: async () => { const n = await checkAlerts(true); toast(`확인 완료 · 알림 ${n}건`); render(); },
    notifyPerm: async () => { if ('Notification' in window) { const p = await Notification.requestPermission(); toast(p === 'granted' ? '알림이 허용되었습니다' : '알림이 허용되지 않았습니다'); render(); } else toast('이 브라우저는 알림을 지원하지 않습니다 (홈 화면에 추가한 앱에서 가능)'); },
    saveSettings: () => saveSettings(), testProxy: () => testProxy(), testAi: () => testAi(), clearKeys: () => { ['aiKey', 'finnhubKey'].forEach((k) => Settings.remove(k)); toast('저장된 키를 삭제했습니다'); render(); },
    reloadLists: () => loadLists(true).then(() => { toast('종목 목록을 다시 불러왔습니다'); render(); }), clearCache: () => { R.clearCache(); toast('캐시를 비웠습니다'); },
    watchAdd: () => { S.watch.adding = true; S.focusSearch = true; render(); },
  };
  await acts[act]?.();
});

// ── 종목 ──
function open(ref) {
  const r = R.enrich(ref);
  S.selected = r; S.tab = 'analysis'; S.query = ''; S.results = [];
  S.recent = [r, ...S.recent.filter((x) => x.symbol !== r.symbol)].slice(0, 10); save();
  S.quote = { s: 'loading' }; S.chart = { s: 'loading' }; S.news = { s: 'loading' }; S.analysis = { s: 'idle' }; S.ai = { s: 'idle' }; S.unsupported = {}; S.interval = 'd1';
  render(); window.scrollTo(0, 0);
  loadQuote(); loadChart(); loadNews(false);
}
async function loadQuote() {
  const ref = S.selected; if (!ref) return;
  try { const q = await R.quote(ref); if (S.selected?.symbol === ref.symbol) { S.quote = { s: 'ok', v: q }; S.selected = { ...S.selected, ...q.ref }; } }
  catch (e) { if (S.selected?.symbol === ref.symbol) S.quote = { s: 'err', e: friendly(e) }; }
  if (S.tab === 'analysis') rerenderKeepScroll();
}
async function refreshQuote(manual) {
  const ref = S.selected; if (!ref) return;
  if (S.quote.s !== 'ok') return loadQuote();
  S.refreshing = true; if (manual) rerenderKeepScroll();
  try { const q = await R.quote(ref); if (S.selected?.symbol === ref.symbol) S.quote = { s: 'ok', v: q }; checkAlerts(false); }
  catch (e) { const p = S.quote.v; S.quote = { s: 'ok', v: { ...p, freshness: 'error', statusText: Q.statusText('error'), warning: `새로고침 실패: ${friendly(e)} — 표시된 값은 이전에 받은 데이터입니다.` } }; }
  S.refreshing = false; if (S.tab === 'analysis') rerenderKeepScroll();
}
async function loadChart() {
  const ref = S.selected; if (!ref) return; const itv = S.interval;
  S.chart = { s: 'loading' }; if (S.tab === 'analysis') rerenderKeepScroll();
  try { const cd = await R.chart(ref, itv); if (S.selected?.symbol === ref.symbol && S.interval === itv) S.chart = { s: 'ok', v: cd }; }
  catch (e) {
    if (itv !== 'd1') { S.unsupported[itv] = `${R.INTERVALS.find((x) => x[0] === itv)[1]} 데이터를 제공받지 못했습니다`; S.interval = 'd1'; return loadChart(); }
    S.chart = { s: 'err', e: friendly(e) };
  }
  if (S.tab === 'analysis') rerenderKeepScroll();
}
async function loadNews(force) {
  const ref = S.selected; if (!ref) return;
  try { const n = await R.news(ref, force); if (S.selected?.symbol === ref.symbol) S.news = { s: 'ok', v: n }; } catch (e) { S.news = { s: 'err', e: friendly(e) }; }
  if (S.tab === 'analysis') rerenderKeepScroll();
}
async function runAnalysis() {
  const ref = S.selected; if (!ref || S.analysis.s === 'loading') return;
  S.analysis = { s: 'loading' }; S.ai = { s: 'idle' }; rerenderKeepScroll();
  try { const fa = await R.analyzeFull(ref); if (S.selected?.symbol !== ref.symbol) return; S.quote = { s: 'ok', v: fa.quote }; if (fa.news) S.news = { s: 'ok', v: fa.news }; S.analysis = { s: 'ok', v: fa }; }
  catch (e) { S.analysis = { s: 'err', e: friendly(e) }; }
  rerenderKeepScroll();
  if (S.scrollToVerdict) $('#verdict')?.scrollIntoView();
}
function analysisDrift() {
  const fa = S.analysis.v, cur = S.quote.v; if (!fa || !cur || fa.quote.ref.symbol !== cur.ref.symbol) return null;
  const d = (cur.price / fa.quote.price - 1) * 100, age = (Date.now() - fa.quote.fetchedAt) / 60000;
  if (Math.abs(d) >= 1) return `분석 이후 가격이 ${Fmt.pct(d)} 변동했습니다. 매수·매도가를 다시 계산하려면 'AI 종합 분석'을 다시 누르세요.`;
  if (cur.marketOpen && age >= 30) return `분석한 지 ${Math.floor(age)}분이 지났습니다. 장중이므로 다시 분석하세요.`;
  return null;
}
function chartLevels() {
  const fa = S.analysis.v; if (!fa || fa.quote.ref.symbol !== S.selected?.symbol || Q.unreliable(fa.quote)) return null;
  const r = fa.result, s = r.strategy;
  return { supports: r.supports.slice(0, 3).map((x) => x.price), resistances: r.resistances.slice(0, 3).map((x) => x.price), buyHigh: s?.buys[0].price, buyLow: s?.buys[2].price, sellLow: s?.targets[0].price, sellHigh: s?.targets[2].price, stop: s?.stop.price };
}
async function runAi() {
  const fa = S.analysis.v; if (!fa) return; S.ai = { s: 'loading' }; rerenderKeepScroll();
  try { S.ai = { s: 'ok', v: await R.aiComment(fa.quote, fa.result) }; } catch (e) { S.ai = { s: 'err', e: friendly(e) }; }
  rerenderKeepScroll();
}
const nextRefreshSec = () => { const q = S.quote.v; if (!q) return 30; if (!liveAvailable()) return 300; const lv = q.marketOpen || q.ext?.active; return !lv ? 60 : q.ref.market === 'KR' ? 10 : 15; };

// ── 화면: 홈 ──
async function loadHome(force) {
  if (!force && S.home.idx.s === 'ok') return;
  S.home.idx = { s: 'loading' }; S.home.macro = { s: 'loading' }; if (S.tab === 'home') render();
  R.indices().then((v) => (S.home.idx = v.length ? { s: 'ok', v } : { s: 'err', e: '시장 지수를 불러오지 못했습니다.' })).catch((e) => (S.home.idx = { s: 'err', e: friendly(e) })).finally(() => S.tab === 'home' && rerenderKeepScroll());
  R.macroNews().then((v) => (S.home.macro = { s: 'ok', v })).catch((e) => (S.home.macro = { s: 'err', e: friendly(e) })).finally(() => S.tab === 'home' && rerenderKeepScroll());
}
function viewHome() {
  const quick = ['005930.KS', '000660.KS', '035420.KS', '042700.KS', 'NVDA', 'MU', 'SNDK', 'TSLA', 'AAPL'].map((s) => directory.bySymbol(s)).filter(Boolean);
  const idx = S.home.idx;
  return header('한국·미국 주식 시세 · AI 분석', `<button class="ib" data-act="homeRefresh" aria-label="새로고침">⟳</button>`) + searchBox() + modeBanner() +
    (S.query ? '' : `<div style="padding:4px 16px"><div class="sub">빠른 검색</div><div class="chips">${quick.map((e) => `<button class="chip" data-act="quick" data-arg="${e.symbol}">${esc(e.nameKr || e.symbol)}</button>`).join('')}</div></div>`) +
    card('시장 지수 · 환율', idx.s === 'ok' ? idx.v.map((x) => `<div class="row" style="padding:3px 0"><span class="grow">${esc(x.name)}</span><b>${Fmt.num(x.price, false)}</b><span style="width:78px;text-align:right;color:${cc(x.pct)}">${Fmt.pct(x.pct)}</span></div>`).join('') +
      `<div class="sub">출처: ${esc([...new Set(idx.v.map((x) => x.source))].join(', '))} · 기준 ${Clock.fmt(idx.v[0].time)}</div>` : idx.s === 'err' ? errBox(idx.e, 'homeRefresh') : loading()) +
    (S.recent.length ? card('최근 본 종목', S.recent.slice(0, 5).map((r) => `<div class="stock" data-open="${esc(r.symbol)}"><div class="grow"><div class="n">${esc(R.displayName(r))}</div><div class="c">${esc(r.symbol)}</div></div><span class="muted">›</span></div>`).join('')) : '') +
    card('주요 경제 일정', Events.upcomingMacro().map((e) => `<div class="row" style="align-items:flex-start;padding:4px 0">${pill(e.daysLeft >= 0 && e.daysLeft < 999 ? 'D-' + e.daysLeft : '-', 'var(--gold)')}<div class="grow"><div>${esc(e.date)} ${esc(e.title)}</div><div class="sub">${esc(e.detail)}</div></div></div>`).join('') + '<div class="sub">CPI·고용지표 발표일은 무료 API가 없어 아래 경제 뉴스로 확인하세요.</div>') +
    card('경제·금리·반도체 뉴스 (최근 7일)', S.home.macro.s === 'ok' ? (S.home.macro.v.length ? S.home.macro.v.slice(0, 10).map(newsRow).join('') : '<div class="muted">최근 7일 내 관련 뉴스가 없습니다.</div>') : S.home.macro.s === 'err' ? errBox(S.home.macro.e) : loading()) + disclaimer;
}
const newsRow = (n) => `<a class="news" href="${esc(n.link)}" target="_blank" rel="noopener">${pill(SENT_LABEL[n.sentiment], n.sentiment === 'positive' ? 'var(--up)' : n.sentiment === 'negative' ? 'var(--down)' : 'var(--sub)')}${n.categories.slice(0, 2).map((c) => pill(c, 'var(--purple)')).join('')}<div class="tt">${esc(n.title)}</div><div class="sub">${esc(n.source)} · ${Clock.relative(n.publishedAt)} (${Clock.fmt(n.publishedAt)})${n.relatedName ? ' · 관련: ' + esc(n.relatedName) : ''}</div></a>`;

// ── 화면: 종목 분석 ──
function viewAnalysis() {
  const r = S.selected;
  const heart = r ? `<button class="ib ${isWatched(r.symbol) ? 'on' : ''}" data-act="heart" aria-label="관심 종목">${isWatched(r.symbol) ? '♥' : '♡'}</button>` : '';
  let h = header(r ? `${R.displayName(r)} · ${r.symbol}` : '종목을 검색하세요', heart) + searchBox() + modeBanner();
  if (!r) return h + '<p class="muted" style="padding:8px 16px">위 검색창에 종목명(한글/영문/초성), 6자리 코드, 티커를 입력하세요.</p>' + disclaimer;
  const kr = r.market === 'KR';
  h += card('현재가', S.quote.s === 'ok' ? quoteBlock(S.quote.v) : S.quote.s === 'err' ? errBox(S.quote.e, 'quoteRefresh') : loading('시세 불러오는 중…'),
    `<span class="row">${S.refreshing ? '<span class="sub">갱신 중…</span>' : ''}<button class="ib" data-act="quoteRefresh" aria-label="시세 새로고침" style="color:var(--accent)">⟳</button></span>`);
  const ivs = R.INTERVALS.map(([k, l]) => `<button class="chip ${S.interval === k ? 'on' : ''}" data-act="interval" data-arg="${k}" ${S.unsupported[k] ? 'disabled' : ''}>${l}</button>`).join('');
  const mas = [5, 10, 20, 60, 120, 200].map((m) => `<button class="chip ${S.overlays.ma.has(m) ? 'on' : ''}" data-act="ma" data-arg="${m}">MA${m}</button>`).join('') +
    [['bb', '볼린저'], ['vwap', 'VWAP'], ['trend', '추세선'], ['levels', '지지/저항'], ['zones', '매매구간']].map(([k, l]) => `<button class="chip ${S.overlays[k] ? 'on' : ''}" data-act="ov" data-arg="${k}">${l}</button>`).join('');
  const subs = [['volume', '거래량'], ['rsi', 'RSI'], ['macd', 'MACD'], ['stoch', '스토캐스틱'], ['atr', 'ATR']].map(([k, l]) => `<button class="chip ${S.sub === k ? 'on' : ''}" data-act="sub" data-arg="${k}">${l}</button>`).join('');
  let ch = `<div class="chips">${ivs}</div>${Object.values(S.unsupported)[0] ? `<div class="sub warn">※ ${esc(Object.values(S.unsupported)[0])} (비활성화됨)</div>` : ''}<div class="chips">${mas}</div><div class="chips">${subs}</div>`;
  if (S.chart.s === 'ok') {
    const cd = S.chart.v, lv = chartLevels();
    ch += `<canvas id="chart" class="chart"></canvas><div class="sub">두 손가락으로 확대·축소, 좌우로 밀어 이동, 탭하면 해당 봉 정보. ${lv ? '청록=지지, 주황=저항, 빨강 점선=손절, 붉은 띠=매수구간, 푸른 띠=매도구간.' : 'AI 분석 후 지지·저항·손절·매수/매도 구간이 표시됩니다.'}</div>` +
      `<div class="row" style="gap:6px">${[...S.overlays.ma].sort((a, b) => a - b).map((m) => `<span class="sub" style="color:${MA_COLORS[m]}">MA${m}</span>`).join('')}</div><div class="sub">데이터: ${cd.bars.length}개 봉 · ${esc(cd.source)} · 마지막 봉 ${Clock.format(cd.bars.at(-1).t, r.market)}</div>`;
  } else ch += S.chart.s === 'err' ? errBox(S.chart.e, 'loadChart') : `<div style="height:400px">${loading('차트 불러오는 중…')}</div>`;
  h += card('차트', ch);
  h += `<div style="padding:6px 12px"><button class="btn red" data-act="analyze" ${S.analysis.s === 'loading' ? 'disabled' : ''}>${S.analysis.s === 'loading' ? 'AI 종합 분석 중…' : 'AI 종합 분석'}</button></div>`;
  if (S.analysis.s === 'idle') h += '<p class="muted" style="padding:0 16px">버튼을 누르면 일봉·뉴스·일정을 종합해 10개 항목을 분석합니다 (무료 내장 엔진).</p>';
  else if (S.analysis.s === 'loading') h += loading('시세·뉴스 수집 및 분석 중…');
  else if (S.analysis.s === 'err') h += `<div class="card">${errBox(S.analysis.e, 'analyze')}</div>`;
  else h += analysisBlocks(S.analysis.v, kr);
  h += card('종목 뉴스 (최근 7일 · 호재/악재 자동 분류)', S.news.s === 'ok' ? newsBlock(S.news.v) : S.news.s === 'err' ? errBox(S.news.e, 'news') : loading(), '<button class="ib" data-act="news">⟳</button>');
  return h + disclaimer;
}
function newsBlock(list) {
  if (!list.length) return '<div class="muted">최근 7일 내 관련 뉴스가 없습니다.</div>';
  const p = list.filter((n) => n.sentiment === 'positive').length, n = list.filter((n) => n.sentiment === 'negative').length;
  return `<div class="sub">호재 ${p} · 악재 ${n} · 중립 ${list.length - p - n} (제목 키워드 자동 분류)</div>${list.slice(0, 15).map(newsRow).join('')}<div class="sub">출처: 네이버 금융 뉴스 · Google 뉴스 RSS</div>`;
}
function quoteBlock(q) {
  const kr = q.ref.market === 'KR';
  return (q.warning ? `<div class="banner">⚠ ${esc(q.warning)}</div>` : '') +
    `<div class="row" style="align-items:baseline;flex-wrap:wrap"><span class="price" style="color:${Q.unreliable(q) ? 'var(--sub)' : cc(q.changePct)}">${Fmt.price(q.price, kr)}</span><span style="color:${cc(q.changePct)};font-size:16px">${q.change != null ? (q.change > 0 ? '▲' : q.change < 0 ? '▼' : '') + Fmt.num(Math.abs(q.change), kr) : '-'} ${Fmt.pct(q.changePct)}</span></div>` +
    `<div>${pill(Q.FRESH[q.freshness], fcol(q.freshness))}${q.freshness !== 'closed' ? pill(Q.SESSION[q.session], q.marketOpen ? 'var(--green)' : 'var(--sub)') : ''}${pill(q.mode === 'snapshot' ? '스냅샷' : q.source, 'var(--accent)')}${q.fallback && q.mode !== 'snapshot' ? pill('지연 가능 / 대체 데이터 사용 중', 'var(--orange)') : ''}</div>` +
    (q.ext ? kv(`${q.ext.label} (${q.ext.active ? '진행 중' : '종료'})`, `${Fmt.price(q.ext.price, kr)}  ${Fmt.pct(q.ext.changePct)}\n${Clock.format(q.ext.time, q.ref.market)}`, cc(q.ext.changePct)) : '') +
    (!kr ? '<div class="sub">※ 국내 증권사 \'주간거래\'(미국 야간 대체거래소) 가격은 무료 공급원이 제공하지 않아 표시하지 않습니다.</div>' : '') +
    kv('체결 기준 시각', Clock.format(q.marketTime, q.ref.market)) + kv('마지막 갱신', `${Clock.fmt(Math.floor(q.fetchedAt / 1000), 'Asia/Seoul', true)} · 자동 ${nextRefreshSec()}초`) +
    kv('데이터 제공', q.delayText) + (q.check ? kv('교차 검증', q.check.message, q.check.mismatch ? 'var(--orange)' : '') : '') +
    kv('종목명', R.displayName(q.ref) + (q.ref.nameKr && q.ref.name && q.ref.name !== q.ref.nameKr ? ` (${q.ref.name})` : '')) + kv('티커 · 거래소', `${q.ref.symbol} · ${q.ref.exchange || '-'}`) +
    kv('거래량', Fmt.volume(q.volume, kr)) + kv('시가총액', q.marketCap ? `${Fmt.marketCap(q.marketCap, kr)} (${q.marketCapSource})` : '미제공') +
    kv('당일 고가 / 저가', `${Fmt.price(q.dayHigh, kr)} / ${Fmt.price(q.dayLow, kr)}`) + kv('52주 최고 / 최저', `${Fmt.price(q.high52, kr)} / ${Fmt.price(q.low52, kr)}`) + kv('전일 종가', Fmt.price(q.prevClose, kr)) +
    '<div class="sub">공휴일은 장 운영 판단에 반영되지 않습니다. \'실시간\'은 공급원이 지연 0분으로 표기하고 체결 시각이 최신일 때만 표시합니다.</div>';
}
function analysisBlocks(fa, kr) {
  const r = fa.result, q = fa.quote, drift = analysisDrift(), bad = Q.unreliable(q);
  let h = '<div id="verdict"></div>';
  const guard = bad ? `분석 당시 시세가 신뢰할 수 없는 상태였습니다 (${q.statusText}). 매수·매도 판단에 사용하지 마세요.` : drift;
  if (guard) h += card('⚠ 시세 확인 필요', `<div class="banner">${esc(guard)}</div><button class="btn" data-act="analyze">최신 시세로 다시 분석</button>`);
  const c = vcolor(r.verdict);
  h += card('AI 종합 판단', `<div class="row"><span class="verdict" style="color:${c};background:color-mix(in srgb, ${c} 18%, transparent)">${VERDICT[r.verdict]}</span>${r.verdict !== 'na' ? `<div class="grow"><div>종합 점수 ${r.score} / 100</div><div class="bar"><i style="width:${r.score}%;background:${c}"></i></div></div>` : ''}</div>` +
    `<p>${esc(r.summary)}</p>${r.reasons.length ? `<b style="color:var(--up)">판단 근거</b>${r.reasons.map((x) => `<div>• ${esc(x)}</div>`).join('')}` : ''}${r.risks.length ? `<b style="color:var(--down)">위험 요인</b>${r.risks.map((x) => `<div>• ${esc(x)}</div>`).join('')}` : ''}` +
    r.warnings.map((w) => `<div class="sub warn">⚠ ${esc(w)}</div>`).join('') + `<div class="sub">분석 기준: 일봉(${esc(fa.barsSource)}) · 정규장 가격 ${Fmt.price(q.price, kr)} · ${esc(q.statusText)} · ${Clock.format(q.marketTime, q.ref.market)} · 무료 내장 분석 엔진</div>`);
  if (r.factors.length) h += card('10개 항목 상세 분석', r.factors.map((f) => `<div style="padding:5px 0"><div class="row"><b class="grow">${f.no}. ${esc(f.title)}</b>${pill(f.status, f.available ? scol(f.score) : 'var(--sub)')}</div><div class="sub">${esc(f.detail)}</div></div>`).join(''));
  const lv = (x, col) => `<div class="lv"><div class="h"><span style="color:${col}">${esc(x.label)}</span><span>${Fmt.price(x.price, kr)} ${Fmt.pct(x.diffPct)}</span></div><div class="sub">근거: ${esc(x.basis)}</div><div style="font-size:13.5px">${esc(x.action)}</div>${x.extra ? `<div class="sub">${esc(x.extra)}</div>` : ''}</div>`;
  if (bad) h += card('매수·매도 전략', '<div class="banner">⚠ 시세 신뢰도가 낮아(지연·오류·불일치) 매수가·목표가·손절가를 표시하지 않습니다. 새로고침 후 다시 분석하세요.</div><button class="btn" data-act="analyze">다시 분석</button>');
  else if (r.strategy) {
    const s = r.strategy;
    h += card('매수·매도 전략 (자동 계산)', '<div class="sub">현재가 대비 차이(%) 표시. 매수가는 실제 지지 근거가 있는 가격 중 현재가에서 가까운 순으로 선정합니다.</div>' +
      `<b style="color:var(--up)">매수 전략</b>${s.buys.map((x) => lv(x, 'var(--up)')).join('')}<div class="banner" style="background:rgba(225,29,72,.18);color:var(--text)">매수 금지 가격대: ${esc(s.noBuyText)}</div>` +
      `<b style="color:var(--down)">매도 전략</b>${s.targets.map((x) => lv(x, 'var(--down)')).join('')}${lv(s.stop, 'var(--stop)')}${lv(s.trendBreak, 'var(--purple)')}` +
      `<div>손익비(계획 평균단가→2차 목표가 / 손절까지): ${s.riskReward != null ? Fmt.d(s.riskReward) + ' : 1' : '-'} · ATR ${Fmt.price(r.atr, kr)} (${Fmt.d(r.atrPct)}%)</div><div class="sub">※ 과거 데이터 기반 계산값이며 체결·수익을 보장하지 않습니다.</div>`);
  }
  if (r.supports.length || r.resistances.length) h += card('지지선 · 저항선', `<b style="color:var(--orange)">저항선 (위)</b>${r.resistances.length ? r.resistances.map((x) => kv(`${Fmt.price(x.price, kr)}  ${Fmt.pct((x.price / r.price - 1) * 100)}`, x.basis)).join('') : '<div class="muted">가까운 저항선 없음 (신고가 영역)</div>'}` +
    `<b style="color:var(--teal)">지지선 (아래)</b>${r.supports.length ? r.supports.map((x) => kv(`${Fmt.price(x.price, kr)}  ${Fmt.pct((x.price / r.price - 1) * 100)}`, x.basis)).join('') : '<div class="muted">가까운 지지선 없음</div>'}`);
  if (r.strategy && !bad) {
    const s = r.strategy, items = [['1차 매수가', 'buyPrice', s.buys[0].price], ['1차 목표가', 'sellPrice', s.targets[0].price], ['손절가', 'stopLoss', s.stop.price]];
    if (r.supports[0]) items.push(['지지선 이탈', 'supportBreak', r.supports[0].price]); if (r.resistances[0]) items.push(['저항선 돌파', 'resistBreak', r.resistances[0].price]);
    h += card('이 가격으로 알림 설정', items.map(([l, t, p]) => `<div class="row" style="min-height:44px"><span class="grow">${l} ${Fmt.price(p, kr)}</span><button class="btn2" data-act="addAlertQuick" data-arg="${t}|${p}">알림 등록</button></div>`).join('') + '<div class="sub">웹앱 알림은 앱을 열어 둔 동안 확인됩니다 (설정 › 알림 안내).</div>');
  }
  const aiOn = Settings.get('aiEndpoint') && Settings.get('aiModel') && Settings.get('aiKey');
  h += card('외부 AI 의견 (선택 · 유료 API)', !aiOn ? '<div class="sub">설정에서 OpenAI 호환 API 주소·모델·키를 입력하면 외부 AI 의견을 받을 수 있습니다 (사용량에 따라 API 요금이 부과될 수 있음). 기본 분석은 무료 내장 엔진이 수행합니다.</div>'
    : (S.ai.s === 'idle' ? '<button class="btn" data-act="ai">외부 AI 의견 받기</button>' : S.ai.s === 'loading' ? loading('외부 AI 응답 대기 중…') : S.ai.s === 'err' ? errBox(S.ai.e, 'ai') : `<p style="white-space:pre-line">${esc(S.ai.v)}</p>`) + '<div class="sub">외부 AI는 시세·뉴스를 직접 조회하지 않으며, 앱이 계산한 분석 결과만 전달받습니다. 키는 이 기기에만 저장되어 API 제공사로 직접 전송됩니다.</div>');
  if (fa.newsError) h += `<p class="sub warn" style="padding:0 16px">뉴스: ${esc(fa.newsError)}</p>`;
  h += card('주요 일정', fa.events.slice(0, 5).map((e) => kv(`${e.date} (D-${e.daysLeft})`, `${e.title} · ${e.detail}`)).join('') + (kr ? '<div class="sub">한국 기업 실적 발표일은 무료 API가 없어 표시하지 않습니다 (DART 전자공시 확인).</div>' : !Settings.get('finnhubKey') ? '<div class="sub">설정에서 무료 Finnhub 키를 등록하면 미국 실적 발표일이 표시됩니다.</div>' : ''));
  return h;
}

// ── 화면: BEST 5 ──
async function runBest(m) {
  if (S.best[m].s === 'loading') return;
  S.best[m] = { s: 'loading' }; S.best.prog = [0, 0, '']; render();
  try { S.best[m] = { s: 'ok', v: await R.best5(m, (d, t, msg) => { S.best.prog = [d, t, msg]; if (S.tab === 'best') { const el = $('#bestprog'); if (el) el.innerHTML = progHtml(); } }, Settings.get('bestWatch', true), S.watchlist) }; }
  catch (e) { S.best[m] = { s: 'err', e: friendly(e) }; }
  if (S.tab === 'best') render();
}
const progHtml = () => { const [d, t, msg] = S.best.prog; return `<div class="bar"><i style="width:${t ? (d / t) * 100 : 3}%;background:var(--accent)"></i></div><div class="sub">${esc(msg || '준비 중…')}</div>`; };
function viewBest() {
  const m = S.best.market, st = S.best[m];
  let h = header('한국·미국 추천 BEST 5 (실제 데이터 분석)') + modeBanner() + `<div class="seg"><button class="${m === 'KR' ? 'on' : ''}" data-act="bestMarket" data-arg="KR">한국</button><button class="${m === 'US' ? 'on' : ''}" data-act="bestMarket" data-arg="US">미국</button></div>` +
    `<div style="padding:0 12px"><button class="btn red" data-act="best" ${st.s === 'loading' ? 'disabled' : ''}>${st.s === 'loading' ? '분석 중…' : `${m === 'KR' ? '한국' : '미국'} BEST 5 분석 시작`}</button></div>` +
    `<p class="sub" style="padding:4px 16px">① 시가총액 순위로 후보군 확보 → ② 거래대금·주가 필터 → ③ 거래대금 상위 80개 일봉 분석 → ④ 상위 10개 뉴스 반영 재분석 → ⑤ 종합 점수 상위 5개. ${liveAvailable() ? '' : '스냅샷 모드에서는 일봉이 수집된 시가총액 상위 종목만 후보입니다.'}</p>`;
  if (st.s === 'loading') h += `<div class="card" id="bestprog">${progHtml()}</div>`;
  else if (st.s === 'err') h += `<div class="card">${errBox(st.e, 'best')}</div>`;
  else if (st.s === 'ok') {
    const res = st.v, kr = m === 'KR';
    h += `<p class="sub" style="padding:0 16px">분석 완료 ${Clock.fmt(res.at)} · 후보군: ${esc(res.scope)} ${res.candidates}종목 → 거래대금·주가 필터 통과 ${res.liquid}종목 → 실제 일봉 분석 ${res.analyzed}종목 · 추천 제외(매수 금지·분할매도·분석 불가) ${res.excluded}종목${res.staleExcluded ? ` · 시세 지연·불일치로 제외 ${res.staleExcluded}종목` : ''}${res.failed.length ? ` · 데이터 수집 실패 ${res.failed.length}종목(${esc(res.failed.slice(0, 5).join(', '))})` : ''}<br>※ 분석하지 않은 종목(필터 탈락·상위 80개 밖)은 평가하지 않았습니다.</p>`;
    if (!res.picks.length) h += card('추천 결과', '<div class="muted">현재 추천 조건(매수 판단 + 손익비)을 충족한 종목이 없습니다. 시장이 약세일 때는 추천하지 않는 것이 정상 동작입니다.</div>');
    else {
      if (res.picks.length < 5) h += `<p class="sub warn" style="padding:0 16px">조건을 충족한 종목이 ${res.picks.length}개뿐이라 ${res.picks.length}개만 표시합니다.</p>`;
      h += res.picks.map((rec, i) => { const s = rec.result.strategy;
        return `<section class="card"><div class="row"><span style="width:36px;height:36px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-weight:800;background:${i === 0 ? 'var(--gold)' : 'var(--card2)'};color:${i === 0 ? '#000' : 'var(--text)'}">${i + 1}</span><div class="grow"><div style="font-size:18px;font-weight:800">${esc(R.displayName(rec.ref))}</div><div class="sub">${esc(rec.ref.symbol)} · 현재가 ${Fmt.price(rec.price, kr)}</div></div><div style="text-align:right"><div style="color:var(--gold);font-size:19px;font-weight:900">${rec.total}점</div>${pill(VERDICT[rec.result.verdict], vcolor(rec.result.verdict))}</div></div>` +
          `<p style="font-size:14px">추천 이유: ${esc(rec.reason)}</p>${s ? kv('1차 매수가', `${Fmt.price(s.buys[0].price, kr)} (${Fmt.pct(s.buys[0].diffPct)})`, 'var(--up)') + kv('1차 목표가', `${Fmt.price(s.targets[0].price, kr)} (${Fmt.pct(s.targets[0].diffPct)})`, 'var(--down)') + kv('손절가', `${Fmt.price(s.stop.price, kr)} (${Fmt.pct(s.stop.diffPct)})`, 'var(--stop)') : ''}` +
          kv('위험 등급', rec.riskGrade, rec.riskGrade === '낮음' ? 'var(--green)' : rec.riskGrade === '보통' ? 'var(--gold)' : 'var(--orange)') + kv('분석 기준 시각', Clock.format(rec.dataTime, m)) +
          `<details><summary class="sub" style="padding:6px 0">9개 평가항목 점수 보기</summary>${rec.parts.map(([k, v]) => `<div class="row"><span class="grow sub">${esc(k)}</span><div class="bar" style="width:110px"><i style="width:${v * 10}%;background:var(--accent)"></i></div><span style="width:24px;text-align:right">${v}</span></div>`).join('')}</details>` +
          `<button class="btn2" style="margin-top:6px" data-open="${esc(rec.ref.symbol)}">상세 분석 보기 ›</button></section>`; }).join('');
    }
  } else h += '<p class="muted" style="padding:0 16px">버튼을 눌러 분석을 시작하세요.</p>';
  return h + disclaimer;
}

// ── 관심 종목·알림 ──
const isWatched = (s) => S.watchlist.some((w) => w.symbol === s);
function addWatch(r) { if (isWatched(r.symbol)) return; S.watchlist.push({ symbol: r.symbol, name: R.displayName(r), naverCode: r.naverCode || null, yahoo: r.yahoo || null, qty: 0, avgPrice: 0, targetPrice: null, stopPrice: null }); save(); refreshWatch(); toast('관심 종목에 추가했습니다'); }
function removeWatch(s) { S.watchlist = S.watchlist.filter((w) => w.symbol !== s); save(); }
async function refreshWatch() {
  if (S.watch.loading) return; S.watch.loading = true; if (S.tab === 'watch') rerenderKeepScroll();
  const res = await Promise.allSettled(S.watchlist.map((w) => R.quote({ symbol: w.symbol, name: w.name, nameKr: w.name, naverCode: w.naverCode, yahoo: w.yahoo })));
  S.watch.quotes = {}; S.watch.errors = {};
  res.forEach((r, i) => { const s = S.watchlist[i]?.symbol; if (!s) return; if (r.status === 'fulfilled') S.watch.quotes[s] = r.value; else S.watch.errors[s] = friendly(r.reason); });
  const fx = await R.usdKrw(); if (fx) S.watch.fx = fx.price;
  S.watch.loading = false; checkAlerts(false); if (S.tab === 'watch') rerenderKeepScroll();
}
function viewWatch() {
  const t = S.watch.tab;
  let h = header('관심 종목 · 보유 주식 · 알림', '<button class="ib" data-act="watchRefresh">⟳</button>') +
    `<div class="seg"><button class="${t === 0 ? 'on' : ''}" data-act="watchTab" data-arg="0">관심·보유 종목 (${S.watchlist.length})</button><button class="${t === 1 ? 'on' : ''}" data-act="watchTab" data-arg="1">주가 알림 (${S.alerts.filter((a) => a.enabled).length})</button></div>`;
  if (t === 1) return h + viewAlerts() + disclaimer;
  h += `<div class="sub" style="padding:0 16px">종목 추가</div>${S.watch.adding || S.query ? searchBox('추가할 종목 검색 (한글·초성·코드·티커)') : '<div style="padding:4px 12px"><button class="btn2" style="width:100%" data-act="watchAdd">+ 종목 검색해서 추가</button></div>'}`;
  const fx = S.watch.fx; let ev = 0, cost = 0, miss = false;
  for (const w of S.watchlist.filter((w) => w.qty > 0 && w.avgPrice > 0)) { const q = S.watch.quotes[w.symbol]; if (!q) continue; const rate = isKr(w.symbol) ? 1 : fx; if (!rate) { miss = true; continue; } ev += q.price * w.qty * rate; cost += w.avgPrice * w.qty * rate; }
  if (cost > 0) { const pl = ev - cost; h += card('보유 주식 합계 (원화 환산)', kv('총 평가금액', Fmt.won(ev)) + kv('총 매수금액', Fmt.won(cost)) + kv('총 평가손익', `${Fmt.won(pl)} (${Fmt.pct((pl / cost) * 100)})`, cc(pl)) + kv('적용 환율', fx ? `1달러 = ${Fmt.won(fx)} (현재 환율, 매수 시점 환율 아님)` : '환율 조회 실패') + (miss ? '<div class="sub warn">환율을 불러오지 못해 미국 주식은 합계에서 제외되었습니다.</div>' : '')); }
  if (S.watch.loading && !Object.keys(S.watch.quotes).length) h += loading('관심 종목 시세 불러오는 중…');
  if (!S.watchlist.length) h += card('관심 종목', '<div class="muted">등록된 관심 종목이 없습니다. 위에서 검색해 추가하거나, 종목 분석 화면의 ♡ 버튼을 누르세요.</div>');
  for (const w of S.watchlist) {
    const kr = isKr(w.symbol), q = S.watch.quotes[w.symbol];
    let b = `<div class="sub">${esc(w.symbol)}${q ? ' · ' + esc(q.statusText) : ''}</div>`;
    if (q) {
      b += `<div class="row" style="align-items:baseline"><span style="font-size:24px;font-weight:800;color:${Q.unreliable(q) ? 'var(--sub)' : cc(q.changePct)}">${Fmt.price(q.price, kr)}</span><span style="color:${cc(q.changePct)}">${Fmt.pct(q.changePct)}</span></div>` +
        `<div class="sub">기준 ${Clock.format(q.marketTime, kr ? 'KR' : 'US')} · ${esc(q.mode === 'snapshot' ? '스냅샷(지연)' : q.source)}</div>` + (q.ext ? `<div style="color:${cc(q.ext.changePct)};font-size:13px">${esc(q.ext.label)}${q.ext.active ? '(진행 중)' : '(종료)'} ${Fmt.price(q.ext.price, kr)} ${Fmt.pct(q.ext.changePct)}</div>` : '') + (q.warning ? `<div class="sub warn">⚠ ${esc(q.warning)}</div>` : '');
    } else b += `<div class="${S.watch.errors[w.symbol] ? 'warn' : 'muted'}">${esc(S.watch.errors[w.symbol] || (S.watch.loading ? '시세 불러오는 중…' : '시세 없음'))}</div>`;
    if (w.qty > 0 && w.avgPrice > 0) {
      b += kv('보유 수량', `${Fmt.num(w.qty, true)}주`) + kv('평균 매수가', Fmt.price(w.avgPrice, kr));
      if (q) { const e2 = q.price * w.qty, c2 = w.avgPrice * w.qty, pl = e2 - c2; b += kv('평가금액', Fmt.price(e2, kr)) + kv('평가손익', `${Fmt.price(pl, kr)} (${Fmt.pct((pl / c2) * 100)})`, cc(pl)) + (q.ext ? kv(`${q.ext.label} 기준`, `${Fmt.price(q.ext.price * w.qty - c2, kr)} · 참고용`, cc(q.ext.price * w.qty - c2)) : '') + (!kr && fx ? kv('원화 환산 손익', `${Fmt.won(pl * fx)} (현재 환율 ${Fmt.won(fx)})`, cc(pl)) : '') + `<div class="sub ${Q.unreliable(q) ? 'warn' : ''}">평가 기준: ${esc(q.statusText)} 가격${Q.unreliable(q) ? ' · ⚠ 시세 신뢰도 낮음' : ''}</div>`; }
    }
    if (w.targetPrice) b += kv('목표 매도가', Fmt.price(w.targetPrice, kr) + (q ? ` (${Fmt.pct((w.targetPrice / q.price - 1) * 100)})` : ''));
    if (w.stopPrice) b += kv('손절가', Fmt.price(w.stopPrice, kr) + (q ? ` (${Fmt.pct((w.stopPrice / q.price - 1) * 100)})` : ''));
    b += `<div class="row" style="margin-top:8px"><button class="btn2 grow" data-open="${esc(w.symbol)}">분석</button><button class="btn2 grow" data-act="holding" data-arg="${esc(w.symbol)}">보유 입력</button><button class="btn2 grow" data-act="calc" data-arg="${esc(w.symbol)}">계산기</button></div>`;
    h += card(w.name, b, `<button class="ib" data-act="unwatch" data-arg="${esc(w.symbol)}" aria-label="삭제">🗑</button>`);
  }
  return h + disclaimer;
}
function dialog(html, onOk) {
  const d = document.createElement('dialog'); d.innerHTML = html; document.body.appendChild(d); d.showModal();
  d.addEventListener('close', () => d.remove());
  d.querySelector('[data-cancel]')?.addEventListener('click', () => d.close());
  d.querySelector('[data-ok]')?.addEventListener('click', () => { if (onOk(d) !== false) d.close(); });
  return d;
}
const n_ = (v) => { const x = parseFloat(String(v).replace(/,/g, '')); return Number.isNaN(x) ? null : x; };
function holdingDialog(sym) {
  const w = S.watchlist.find((x) => x.symbol === sym); if (!w) return;
  const f = (id, l, v) => `<label class="sub">${l}<input class="f" id="${id}" inputmode="decimal" value="${v || ''}"></label>`;
  dialog(`<h3 style="margin-top:0">${esc(w.name)} 보유 정보</h3>${f('hq', '보유 수량 (주)', w.qty)}${f('ha', `평균 매수가 (${isKr(sym) ? '원' : '달러'})`, w.avgPrice)}${f('ht', '목표 매도가 (선택)', w.targetPrice)}${f('hs', '손절가 (선택)', w.stopPrice)}<div class="row" style="margin-top:10px"><button class="btn2 grow" data-cancel>취소</button><button class="btn2 grow" data-ok style="background:var(--accent)">저장</button></div>`,
    (d) => { w.qty = n_($('#hq', d).value) || 0; w.avgPrice = n_($('#ha', d).value) || 0; w.targetPrice = n_($('#ht', d).value); w.stopPrice = n_($('#hs', d).value); save(); render(); });
}
function calcDialog(sym) {
  const w = S.watchlist.find((x) => x.symbol === sym); const kr = isKr(sym), cur = S.watch.quotes[sym]?.price;
  const d = dialog(`<h3 style="margin-top:0">${esc(w.name)} 계산기</h3><div class="seg" style="margin:0 0 8px"><button class="on" data-m="0">분할매수 평단</button><button data-m="1">분할매도 수익</button></div>` +
    ['보유 수량|c1|' + (w.qty || ''), '평균단가|c2|' + (w.avgPrice || ''), '추가 매수/매도 수량|c3|', '추가 매수/매도 가격|c4|' + (cur ? (kr ? Math.round(cur) : cur.toFixed(2)) : '')].map((s) => { const [l, id, v] = s.split('|'); return `<label class="sub">${l}<input class="f" id="${id}" inputmode="decimal" value="${v}"></label>`; }).join('') +
    `<div id="cr" style="margin-top:8px"></div><button class="btn2" style="width:100%;margin-top:8px" data-cancel>닫기</button>`, () => {});
  let mode = 0;
  const calc = () => {
    const a = n_($('#c1', d).value), b = n_($('#c2', d).value), c = n_($('#c3', d).value), e = n_($('#c4', d).value); let o = '<div class="muted">숫자를 모두 입력하면 결과가 표시됩니다.</div>';
    if (a != null && b > 0 && c > 0 && e > 0) {
      if (mode === 0) { const nq = a + c, na = (a * b + c * e) / nq; o = kv('매수 후 수량', `${Fmt.num(nq, true)}주`) + kv('새 평균단가', Fmt.price(na, kr), 'var(--gold)') + kv('평단 변화', Fmt.pct((na / b - 1) * 100)) + kv('추가 투입금', Fmt.price(c * e, kr)); }
      else if (c > a) o = '<div class="warn">매도 수량이 보유 수량보다 많습니다.</div>';
      else { const p = (e - b) * c; o = kv('실현 손익', Fmt.price(p, kr), cc(p)) + kv('수익률', Fmt.pct((e / b - 1) * 100), cc(p)) + kv('매도 금액', Fmt.price(c * e, kr)) + kv('잔여 수량', `${Fmt.num(a - c, true)}주 (평단 ${Fmt.price(b, kr)} 유지)`) + '<div class="sub">※ 세금·수수료 제외</div>'; }
    }
    $('#cr', d).innerHTML = o;
  };
  d.addEventListener('input', calc);
  d.querySelectorAll('[data-m]').forEach((b) => b.addEventListener('click', () => { mode = +b.dataset.m; d.querySelectorAll('[data-m]').forEach((x) => x.classList.toggle('on', x === b)); calc(); }));
  calc();
}
const ALERT_TYPES = { buyPrice: ['목표 매수가 도달 (이하)', '가격'], sellPrice: ['목표 매도가 도달 (이상)', '가격'], supportBreak: ['지지선 이탈 (이하)', '가격'], resistBreak: ['저항선 돌파 (이상)', '가격'],
  stopLoss: ['손절 기준가 도달 (이하)', '가격'], surge: ['급등 (등락률 이상)', '%'], plunge: ['급락 (등락률 이하, 양수로 입력)', '%'], volumeSpike: ['거래량 급증 (평균 대비 배수)', '배'] };
function addAlert(symbol, name, type, value) { S.alerts.push({ id: Date.now() + Math.floor(Math.random() * 1000), symbol, name, type, value, enabled: true, lastFired: 0 }); save(); toast('알림을 등록했습니다'); }
function alertDialog() {
  const cands = [...(S.selected ? [[S.selected.symbol, R.displayName(S.selected)]] : []), ...S.watchlist.map((w) => [w.symbol, w.name])].filter((x, i, a) => a.findIndex((y) => y[0] === x[0]) === i);
  dialog(`<h3 style="margin-top:0">주가 알림 추가</h3>${cands.length ? '' : '<div class="warn">먼저 관심 종목을 등록하거나 종목을 분석하세요.</div>'}<label class="sub">종목<select class="f" id="as">${cands.map(([s, n]) => `<option value="${esc(s)}">${esc(n)}</option>`).join('')}</select></label>` +
    `<label class="sub">조건<select class="f" id="at">${Object.entries(ALERT_TYPES).map(([k, [l]]) => `<option value="${k}">${l}</option>`).join('')}</select></label><label class="sub">값<input class="f" id="av" inputmode="decimal"></label>` +
    `<div class="row" style="margin-top:10px"><button class="btn2 grow" data-cancel>취소</button><button class="btn2 grow" data-ok style="background:var(--accent)">추가</button></div>`,
    (d) => { const v = n_($('#av', d).value), s = $('#as', d).value; if (v == null || !s) { toast('값을 입력하세요'); return false; } addAlert(s, cands.find((c) => c[0] === s)[1], $('#at', d).value, v); render(); });
}
function viewAlerts() {
  const perm = 'Notification' in window ? Notification.permission : 'unsupported';
  let h = card('알림 동작 안내 (웹앱)', `<div class="sub" style="line-height:1.6">• 앱(홈 화면 아이콘)을 열어 둔 동안 시세가 새로고침될 때마다 조건을 확인해 알림을 띄웁니다.<br>• 앱을 닫은 뒤의 백그라운드 감시는 <b>푸시 서버가 필요</b>해 무료 정적 호스팅에서는 제공되지 않습니다.<br>• 아이폰은 iOS 16.4 이상에서 <b>홈 화면에 추가한 앱</b>만 알림을 받을 수 있습니다.<br>• 가격 도달형은 1회 알림 후 꺼지고, 급등락·거래량은 장중 6시간 간격입니다. 지연·불일치 시세로는 알림을 보내지 않습니다.</div>` +
    `<div class="row" style="margin-top:8px"><button class="btn2 grow" data-act="addAlert">+ 알림 추가</button><button class="btn2 grow" data-act="checkAlerts">지금 확인</button></div>` +
    `<div class="row" style="margin-top:6px"><span class="grow sub">알림 권한: ${{ granted: '허용됨', denied: '거부됨 (설정 앱에서 변경)', default: '미설정', unsupported: '이 브라우저 미지원' }[perm]}</span>${perm === 'default' ? '<button class="btn2" data-act="notifyPerm">알림 허용</button>' : ''}</div>`);
  if (!S.alerts.length) h += card('등록된 알림', '<div class="muted">등록된 알림이 없습니다. 종목 분석 후 \'이 가격으로 알림 설정\' 버튼으로도 추가할 수 있습니다.</div>');
  for (const a of S.alerts) { const kr = isKr(a.symbol), [l, u] = ALERT_TYPES[a.type];
    h += card(a.name, `<div>${esc(l)}</div><div class="row">${pill(u === '가격' ? Fmt.price(a.value, kr) : a.value + u, 'var(--gold)')}<span class="sub">${a.lastFired ? '마지막 알림 ' + Clock.fmt(Math.floor(a.lastFired / 1000)) : a.enabled ? '감시 중' : '꺼짐'}</span></div>`,
      `<span class="row"><button class="btn2" data-act="toggleAlert" data-arg="${a.id}">${a.enabled ? '끄기' : '켜기'}</button><button class="ib" data-act="delAlert" data-arg="${a.id}">🗑</button></span>`); }
  return h;
}
async function checkAlerts(force) {
  const active = S.alerts.filter((a) => a.enabled); if (!active.length) return 0;
  let sent = 0; const now = Date.now();
  for (const sym of [...new Set(active.map((a) => a.symbol))]) {
    let q = S.watch.quotes[sym] || (S.quote.v?.ref.symbol === sym ? S.quote.v : null);
    if (force || !q) { try { q = await R.quote({ symbol: sym, name: sym }); } catch { continue; } }
    if (!q || Q.unreliable(q)) continue;
    let avg20 = null; try { const b = (await R.chart(q.ref, 'd1')).bars; if (b.length > 21) avg20 = b.slice(-21, -1).reduce((x, y) => x + y.v, 0) / 20; } catch { /* 없음 */ }
    const kr = isKr(sym), p = Fmt.price(q.price, kr);
    for (const a of active.filter((x) => x.symbol === sym)) {
      const v = Fmt.price(a.value, kr), pc = q.changePct; let msg = null;
      if (a.type === 'buyPrice' && q.price <= a.value) msg = `목표 매수가 ${v} 도달 (현재 ${p})`;
      if (a.type === 'sellPrice' && q.price >= a.value) msg = `목표 매도가 ${v} 도달 (현재 ${p})`;
      if (a.type === 'supportBreak' && q.price < a.value) msg = `지지선 ${v} 이탈 (현재 ${p})`;
      if (a.type === 'resistBreak' && q.price > a.value) msg = `저항선 ${v} 돌파 (현재 ${p})`;
      if (a.type === 'stopLoss' && q.price <= a.value) msg = `⚠ 손절 기준가 ${v} 도달 (현재 ${p})`;
      if (a.type === 'surge' && q.marketOpen && pc >= a.value) msg = `급등 ${Fmt.pct(pc)} (현재 ${p})`;
      if (a.type === 'plunge' && q.marketOpen && pc <= -a.value) msg = `급락 ${Fmt.pct(pc)} (현재 ${p})`;
      if (a.type === 'volumeSpike' && q.marketOpen && avg20 && q.volume >= avg20 * a.value) msg = `거래량 급증: 20일 평균의 ${(q.volume / avg20).toFixed(1)}배 (현재 ${p})`;
      if (!msg) continue;
      const oneShot = !['surge', 'plunge', 'volumeSpike'].includes(a.type);
      if (!oneShot && now - a.lastFired < 6 * 3600_000) continue;
      a.lastFired = now; if (oneShot) a.enabled = false; sent++;
      await notify(`${a.name} · ${msg}`, `${Clock.format(q.marketTime, q.ref.market)} 기준 · ${q.statusText}`);
    }
  }
  save(); return sent;
}
async function notify(title, body) {
  try { if ('Notification' in window && Notification.permission === 'granted') { const reg = await navigator.serviceWorker?.ready; if (reg) { await reg.showNotification(title, { body, icon: 'icons/icon-192.png', badge: 'icons/icon-192.png' }); return; } new Notification(title, { body }); return; } } catch { /* 토스트로 대체 */ }
  toast(`🔔 ${title}`);
}

// ── 설정 ──
function viewSettings() {
  const c = directory.counts, px = proxyBase();
  return header('설정 · 데이터 출처 · 외부 AI') +
    card('데이터 모드', `<div>${liveAvailable() ? pill('실시간 모드 (프록시 연결됨)', 'var(--green)') : pill('스냅샷 모드 (지연)', 'var(--accent)')}</div><div class="sub" style="line-height:1.6">• 스냅샷: GitHub Actions 가 네이버 금융에서 전 종목 시세·상위 종목 일봉·뉴스를 모아 이 웹앱과 함께 배포합니다. 수집 시각만큼 <b>지연</b>되며, 일봉·뉴스는 시가총액 상위 종목만 있습니다.<br>• 실시간: 본인의 <b>무료 Cloudflare Workers</b> 에 중계 서버를 올리면 앱판과 같은 네이버 실시간 시세·전 종목 차트·뉴스를 사용합니다 (설치 설명서 참고).</div>` +
      `<label class="sub">실시간 프록시 주소 (선택)<input class="f" id="sproxy" inputmode="url" placeholder="https://ai-stock-pro-proxy.xxxx.workers.dev" value="${esc(Settings.get('proxy', '') || '')}"></label>${globalThis.APP_CONFIG?.proxy ? `<div class="sub">기본 프록시(배포 설정): ${esc(globalThis.APP_CONFIG.proxy)}</div>` : ''}` +
      `<div class="row"><button class="btn2 grow" data-act="testProxy">프록시 연결 테스트</button><button class="btn2 grow" data-act="saveSettings" style="background:var(--accent)">저장</button></div><div id="proxyres" class="sub"></div>`) +
    card('알림 · 추천', `<label class="sw"><span>BEST 5 후보에 관심 종목도 포함</span><input type="checkbox" id="sbw" ${Settings.get('bestWatch', true) ? 'checked' : ''}></label><div class="sub">알림 안내는 관심 종목 › 주가 알림 탭을 참고하세요.</div>`) +
    card('종목 목록 (전체 검색용)', kv('한국', `${c.kr}종목 · 기준 ${directory.updated.kr}`) + kv('미국', `${c.us}종목 · 기준 ${directory.updated.us}`) + '<div class="sub">KRX 공식 상장법인목록·네이버 금융·Nasdaq Trader 목록으로 매주 자동 생성되어 웹앱과 함께 배포됩니다 (검색은 오프라인에서도 동작).</div><button class="btn2" data-act="reloadLists">목록 다시 불러오기</button>') +
    card('무료 API 키 (선택)', '<div class="sub">Finnhub 무료 키: 미국 실적 발표일 (finnhub.io 가입). 키는 <b>이 기기 브라우저에만</b> 저장되며 저장소·웹앱 소스에 포함되지 않습니다.</div>' +
      `<input class="f" id="sfh" type="password" autocomplete="off" placeholder="${Settings.get('finnhubKey') ? '등록됨 (변경하려면 새 키 입력)' : 'Finnhub API 키'}">`) +
    card('외부 AI 연결 (선택 · 유료 API)', '<div class="sub">기본 분석은 무료 내장 엔진입니다. OpenAI 등 외부 AI는 <b>본인 API 키로 사용량만큼 요금이 부과</b>됩니다. 예: https://api.openai.com/v1 · gpt-4o-mini. 키는 이 기기에만 저장되어 API 제공사로 직접 전송됩니다.</div>' +
      `<input class="f" id="sae" inputmode="url" placeholder="API 주소" value="${esc(Settings.get('aiEndpoint', '') || '')}"><input class="f" id="sam" placeholder="모델 이름" value="${esc(Settings.get('aiModel', '') || '')}"><input class="f" id="sak" type="password" autocomplete="off" placeholder="${Settings.get('aiKey') ? '등록됨 (변경하려면 새 키 입력)' : 'API 키'}">` +
      '<div class="row"><button class="btn2 grow" data-act="saveSettings" style="background:var(--accent)">저장</button><button class="btn2 grow" data-act="testAi">연결 테스트</button><button class="btn2" data-act="clearKeys">키 삭제</button></div><div id="aires" class="sub"></div>') +
    card('데이터 출처 · 이용 조건', '<div class="sub" style="line-height:1.6">• 시세·차트·뉴스: 네이버 금융 공개 데이터 (비공식, 개인·비상업 열람 용도)<br>• 보조: Yahoo Finance (교차검증·대체, 실시간 모드에서만), Google 뉴스 RSS<br>• 미국 \'주간거래\'(야간 대체거래소) 가격은 무료 공급원이 제공하지 않습니다<br>• 공급원 정책 변경 시 조회가 실패할 수 있으며, 이때 앱은 오류로 표시하고 임의 가격을 만들지 않습니다</div><button class="btn2" data-act="clearCache">캐시 비우기</button>') +
    card('앱 정보', kv('버전', `${globalThis.APP_CONFIG?.version || 'dev'} (${globalThis.APP_CONFIG?.builtAt || '-'})`) + kv('분석 엔진', '무료 내장 규칙 기반 다요인 모델 (안드로이드·iOS판과 동일)') + kv('설치', '사파리 공유 버튼 › 홈 화면에 추가')) + disclaimer;
}
function saveSettings() {
  const v = (id) => $(id)?.value?.trim();
  if ($('#sproxy')) Settings.set('proxy', v('#sproxy'));
  if ($('#sbw')) Settings.set('bestWatch', $('#sbw').checked);
  if (v('#sfh')) Settings.set('finnhubKey', v('#sfh'));
  if ($('#sae')) Settings.set('aiEndpoint', v('#sae')); if ($('#sam')) Settings.set('aiModel', v('#sam')); if (v('#sak')) Settings.set('aiKey', v('#sak'));
  R.clearCache(); toast('저장했습니다'); render();
}
async function testProxy() {
  saveSettings(); const el = () => $('#proxyres');
  if (!liveAvailable()) { el() && (el().textContent = '프록시 주소가 비어 있습니다 (스냅샷 모드).'); return; }
  el() && (el().textContent = '테스트 중…');
  try { const q = await R.quote({ symbol: '005930.KS', name: '삼성전자', market: 'KR' }); el() && (el().textContent = q.mode === 'live' ? `✅ 연결 성공 · 삼성전자 ${Fmt.price(q.price, true)} (${q.statusText})` : `❌ 프록시 실패 → 스냅샷 사용 중`); }
  catch (e) { el() && (el().textContent = '❌ ' + friendly(e)); }
}
async function testAi() {
  saveSettings(); const el = $('#aires'); if (el) el.textContent = '테스트 중…';
  try { const t = await R.aiComment(null, null, true); if ($('#aires')) $('#aires').textContent = '✅ 연결 성공: ' + t.slice(0, 40); } catch (e) { if ($('#aires')) $('#aires').textContent = '❌ ' + friendly(e); }
}

// ── 시작 ──
async function loadLists() {
  const [kr, us] = await Promise.all([local('./data/stocks_kr.json'), local('./data/stocks_us.json')]);
  directory.load(kr, us);
}
async function boot() {
  try { await loadLists(); } catch (e) { toast('종목 목록을 불러오지 못했습니다: ' + friendly(e)); }
  $('#splash').classList.add('hide'); setTimeout(() => $('#splash').remove(), 400);
  const p = new URLSearchParams(location.search);
  if (p.get('symbol')) { open(findRef(p.get('symbol'))); if (p.get('analyze')) { S.scrollToVerdict = true; setTimeout(runAnalysis, 1500); } }
  else go(p.get('tab') || 'home');
  if (p.get('tab') === 'best' && p.get('run')) runBest(p.get('market') || 'KR');
  // 화면이 보이는 동안 자동 새로고침 (실시간 모드: 한국 10초·미국 15초·마감 60초 / 스냅샷: 5분)
  let last = Date.now();
  setInterval(() => {
    if (document.hidden) return;
    if (S.tab === 'analysis' && S.selected && Date.now() - last >= nextRefreshSec() * 1000) { last = Date.now(); refreshQuote(false); }
  }, 2000);
  setInterval(() => { if (!document.hidden && S.tab === 'watch') refreshWatch(); }, liveAvailable() ? 30_000 : 300_000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { if (S.tab === 'analysis') refreshQuote(false); if (S.tab === 'watch') refreshWatch(); } });
  if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(() => {});
}
window.addEventListener('error', (e) => toast('오류: ' + (e.message || '알 수 없는 오류')));
window.addEventListener('unhandledrejection', (e) => { console.error(e.reason); });
boot();
