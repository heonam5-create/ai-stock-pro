// 캔들 차트 (Canvas): 두 손가락 확대·축소, 한 손가락 좌우 이동, 탭 = 십자선 정보. 세로 스크롤은 페이지로 넘김
import { indicatorSet } from './core/indicators.js';
import { Fmt } from './core/fmt.js';
import { Clock } from './core/clock.js';

export const MA_COLORS = { 5: '#FACC15', 10: '#F472B6', 20: '#F97316', 60: '#22C55E', 120: '#A78BFA', 200: '#94A3B8' };
const C = { line: '#263247', sub: '#8E99AD', up: '#F04452', down: '#3B82F6', teal: '#14B8A6', orange: '#F59E0B', stop: '#E11D48', purple: '#A78BFA', gold: '#FACC15', accent: '#5B8CFF', green: '#22C55E' };

export function mountChart(canvas, { bars, intraday, tz, kr, overlays, sub, levels }) {
  const ind = indicatorSet(bars, intraday, tz);
  const n = bars.length;
  let visible = Math.min(intraday ? 120 : 100, n), offset = 0, selected = -1;
  const axisW = 62;
  const dpr = Math.min(window.devicePixelRatio || 1, 3);
  const ctx = canvas.getContext('2d');

  const size = () => { const r = canvas.getBoundingClientRect(); canvas.width = r.width * dpr; canvas.height = r.height * dpr; return r; };
  let rect = size();
  const start = () => Math.max(0, n - Math.round(visible) - Math.round(offset));
  const end = () => Math.min(n, start() + Math.round(visible));
  const dateText = (t) => { const s = Clock.fmt(t, tz); return intraday ? s.slice(5) : s.slice(2, 10); };

  function draw() {
    const W = rect.width, H = rect.height;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, W, H);
    const s = start(), e = end(); if (e - s < 2) return;
    const w = W - axisW, mainH = H * 0.68, subTop = mainH + 18, subH = H - subTop - 14, bw = w / (e - s);
    let hi = -Infinity, lo = Infinity;
    for (let i = s; i < e; i++) { hi = Math.max(hi, bars[i].h); lo = Math.min(lo, bars[i].l); }
    if (overlays.bb) for (let i = s; i < e; i++) if (!Number.isNaN(ind.bb.upper[i])) { hi = Math.max(hi, ind.bb.upper[i]); lo = Math.min(lo, ind.bb.lower[i]); }
    if (levels && (overlays.levels || overlays.zones)) for (const p of [...levels.supports, ...levels.resistances, levels.stop, levels.buyLow, levels.sellHigh].filter((x) => x)) if (p > lo * 0.85 && p < hi * 1.15) { hi = Math.max(hi, p); lo = Math.min(lo, p); }
    const pad = (hi - lo) * 0.06; hi += pad; lo -= pad;
    const y = (p) => ((hi - p) / (hi - lo)) * mainH, x = (i) => (i - s + 0.5) * bw;
    const hline = (yy, color, dash = [], lw = 1) => { ctx.strokeStyle = color; ctx.lineWidth = lw; ctx.setLineDash(dash); ctx.beginPath(); ctx.moveTo(0, yy); ctx.lineTo(w, yy); ctx.stroke(); ctx.setLineDash([]); };
    ctx.font = '10px -apple-system, sans-serif'; ctx.textBaseline = 'middle';
    for (let k = 0; k <= 4; k++) { const p = lo + ((hi - lo) * k) / 4; hline(y(p), C.line, [], 0.5); ctx.fillStyle = C.sub; ctx.fillText(Fmt.num(p, kr), w + 4, y(p)); }
    if (levels && overlays.zones) {
      if (levels.buyHigh && levels.buyLow) { ctx.fillStyle = 'rgba(240,68,82,.12)'; ctx.fillRect(0, y(levels.buyHigh), w, Math.max(1, y(levels.buyLow) - y(levels.buyHigh))); }
      if (levels.sellHigh && levels.sellLow) { ctx.fillStyle = 'rgba(59,130,246,.12)'; ctx.fillRect(0, y(levels.sellHigh), w, Math.max(1, y(levels.sellLow) - y(levels.sellHigh))); }
    }
    if (levels && overlays.levels) {
      levels.supports.forEach((p) => hline(y(p), C.teal, [6, 4], 1.5)); levels.resistances.forEach((p) => hline(y(p), C.orange, [6, 4], 1.5));
      if (levels.stop) hline(y(levels.stop), C.stop, [3, 3], 1.5);
    }
    const series = (arr, color, lw = 1.2, dash = []) => {
      ctx.strokeStyle = color; ctx.lineWidth = lw; ctx.setLineDash(dash); ctx.beginPath(); let st = false;
      for (let i = s; i < e; i++) { const v = arr[i]; if (Number.isNaN(v) || v === undefined) { st = false; continue; } if (st) ctx.lineTo(x(i), y(v)); else { ctx.moveTo(x(i), y(v)); st = true; } }
      ctx.stroke(); ctx.setLineDash([]);
    };
    if (overlays.bb) { series(ind.bb.upper, 'rgba(167,139,250,.7)', 1); series(ind.bb.mid, 'rgba(167,139,250,.4)', 1); series(ind.bb.lower, 'rgba(167,139,250,.7)', 1); }
    for (let i = s; i < e; i++) {
      const b = bars[i], col = b.c >= b.o ? C.up : C.down;
      ctx.strokeStyle = col; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(x(i), y(b.h)); ctx.lineTo(x(i), y(b.l)); ctx.stroke();
      const top = y(Math.max(b.o, b.c)), bot = y(Math.min(b.o, b.c)); ctx.fillStyle = col; ctx.fillRect(x(i) - bw * 0.35, top, Math.max(1, bw * 0.7), Math.max(1, bot - top));
    }
    [...overlays.ma].sort((a, b) => a - b).forEach((m) => series(ind.ma[m], MA_COLORS[m]));
    if (overlays.vwap) series(ind.vwap, '#38BDF8', 1.2, [4, 3]);
    if (overlays.trend) for (const t of ind.trendLines) { const a = Math.max(t.i1, s), b = e - 1; if (b <= a) continue; ctx.strokeStyle = t.up ? C.green : C.stop; ctx.lineWidth = 1.4; ctx.beginPath(); ctx.moveTo(x(a), y(t.at(a))); ctx.lineTo(x(b), y(t.at(b))); ctx.stroke(); }
    ctx.fillStyle = C.sub; ctx.textAlign = 'center';
    for (const k of [s, Math.floor((s + e) / 2), e - 1]) ctx.fillText(dateText(bars[k].t), Math.min(Math.max(x(k), 30), w - 30), mainH + 8);
    ctx.textAlign = 'left';
    drawSub(s, e, x, subTop, subH, w, bw);
    if (selected >= s && selected < e) {
      const b = bars[selected], chg = selected > 0 ? (b.c / bars[selected - 1].c - 1) * 100 : NaN;
      ctx.strokeStyle = 'rgba(255,255,255,.5)'; ctx.lineWidth = 0.8; ctx.beginPath(); ctx.moveTo(x(selected), 0); ctx.lineTo(x(selected), H); ctx.stroke();
      const info = `${dateText(b.t)} 시${Fmt.num(b.o, kr)} 고${Fmt.num(b.h, kr)} 저${Fmt.num(b.l, kr)} 종${Fmt.num(b.c, kr)} ${Fmt.pct(chg)}`;
      ctx.font = 'bold 11px -apple-system, sans-serif'; const tw = Math.min(ctx.measureText(info).width, w - 10);
      ctx.fillStyle = 'rgba(0,0,0,.75)'; ctx.fillRect(4, 4, tw + 10, 20); ctx.fillStyle = '#fff'; ctx.fillText(info, 9, 14, w - 14);
    }
  }
  function drawSub(s, e, x, top, h, w, bw) {
    ctx.strokeStyle = C.line; ctx.lineWidth = 0.5; ctx.beginPath(); ctx.moveTo(0, top); ctx.lineTo(w, top); ctx.stroke();
    const rng = (arrs) => { let a = -Infinity, b = Infinity; for (const arr of arrs) for (let i = s; i < e; i++) if (!Number.isNaN(arr[i])) { a = Math.max(a, arr[i]); b = Math.min(b, arr[i]); } return Number.isFinite(a) ? [a === b ? a + 1 : a, b] : [1, 0]; };
    const yy = (v, r) => top + ((r[0] - v) / (r[0] - r[1])) * h;
    const ser = (arr, r, color) => { ctx.strokeStyle = color; ctx.lineWidth = 1.2; ctx.beginPath(); let st = false; for (let i = s; i < e; i++) { const v = arr[i]; if (Number.isNaN(v)) { st = false; continue; } if (st) ctx.lineTo(x(i), yy(v, r)); else { ctx.moveTo(x(i), yy(v, r)); st = true; } } ctx.stroke(); };
    const dash = (v, r) => { ctx.strokeStyle = 'rgba(142,153,173,.5)'; ctx.setLineDash([3, 3]); ctx.beginPath(); ctx.moveTo(0, yy(v, r)); ctx.lineTo(w, yy(v, r)); ctx.stroke(); ctx.setLineDash([]); };
    let label = { volume: '거래량 (20일 평균)', rsi: 'RSI', macd: 'MACD', stoch: '스토캐스틱', atr: 'ATR' }[sub];
    if (sub === 'volume') { const r = [rng([ind.v])[0], 0]; for (let i = s; i < e; i++) { const b = bars[i]; ctx.fillStyle = b.c >= b.o ? 'rgba(240,68,82,.7)' : 'rgba(59,130,246,.7)'; ctx.fillRect(x(i) - bw * 0.35, yy(b.v, r), Math.max(1, bw * 0.7), top + h - yy(b.v, r)); } ser(ind.volMa20, r, C.gold); }
    if (sub === 'rsi') { const r = [100, 0]; dash(70, r); dash(30, r); ser(ind.rsi, r, C.purple); if (!Number.isNaN(ind.rsi[e - 1])) label += ' ' + ind.rsi[e - 1].toFixed(1); }
    if (sub === 'macd') { const r = rng([ind.macd.macd, ind.macd.signal, ind.macd.hist]); for (let i = s; i < e; i++) { const v = ind.macd.hist[i]; if (Number.isNaN(v)) continue; ctx.fillStyle = v >= 0 ? 'rgba(240,68,82,.6)' : 'rgba(59,130,246,.6)'; const z = yy(0, r), v2 = yy(v, r); ctx.fillRect(x(i) - bw * 0.35, Math.min(z, v2), Math.max(1, bw * 0.7), Math.abs(z - v2)); } ser(ind.macd.macd, r, C.gold); ser(ind.macd.signal, r, C.accent); }
    if (sub === 'stoch') { const r = [100, 0]; dash(80, r); dash(20, r); ser(ind.stoch.k, r, C.gold); ser(ind.stoch.d, r, C.accent); }
    if (sub === 'atr') ser(ind.atr, rng([ind.atr]), C.teal);
    ctx.fillStyle = C.sub; ctx.font = '10px -apple-system, sans-serif'; ctx.textBaseline = 'top'; ctx.fillText(label, 4, top + 2); ctx.textBaseline = 'middle';
  }

  // 터치 제스처
  let t0 = null;
  canvas.addEventListener('touchstart', (ev) => {
    const ts = [...ev.touches];
    t0 = { touches: ts.map((t) => ({ x: t.clientX, y: t.clientY })), visible, offset, moved: false, horiz: null, time: Date.now() };
  }, { passive: true });
  canvas.addEventListener('touchmove', (ev) => {
    if (!t0) return;
    const ts = [...ev.touches];
    if (ts.length >= 2 && t0.touches.length >= 2) {
      ev.preventDefault();
      const d0 = Math.hypot(t0.touches[0].x - t0.touches[1].x, t0.touches[0].y - t0.touches[1].y), d1 = Math.hypot(ts[0].clientX - ts[1].clientX, ts[0].clientY - ts[1].clientY);
      visible = Math.min(Math.max(t0.visible * (d0 / Math.max(d1, 1)), Math.min(15, n)), n); offset = Math.min(offset, Math.max(0, n - visible)); t0.moved = true; draw(); return;
    }
    const dx = ts[0].clientX - t0.touches[0].x, dy = ts[0].clientY - t0.touches[0].y;
    if (t0.horiz === null && Math.hypot(dx, dy) > 8) t0.horiz = Math.abs(dx) > Math.abs(dy);
    if (t0.horiz) { ev.preventDefault(); t0.moved = true; offset = Math.min(Math.max(t0.offset + dx / ((rect.width - axisW) / visible), 0), Math.max(0, n - visible)); draw(); }
  }, { passive: false });
  canvas.addEventListener('touchend', (ev) => {
    if (t0 && !t0.moved && Date.now() - t0.time < 400) { const bx = t0.touches[0].x - canvas.getBoundingClientRect().left; pick(bx); }
    t0 = null;
  });
  canvas.addEventListener('click', (ev) => { if (!('ontouchstart' in window)) pick(ev.offsetX); });
  function pick(bx) { const w = rect.width - axisW; if (bx > w) { selected = -1; draw(); return; } const i = start() + Math.floor(bx / (w / (end() - start()))); selected = selected === i ? -1 : i; draw(); }
  const ro = new ResizeObserver(() => { rect = size(); draw(); }); ro.observe(canvas);
  draw();
  return { destroy: () => ro.disconnect() };
}
