// 기술적 지표 — 안드로이드 Indicators.kt / iOS Indicators.swift 와 동일 계산 (부족한 앞부분은 NaN)
const NaNs = (n) => new Array(n).fill(NaN);
export const avg = (v) => (v.length ? v.reduce((a, b) => a + b, 0) / v.length : NaN);

export const Ind = {
  sma(v, n) {
    const out = NaNs(v.length); if (n <= 0 || v.length < n) return out;
    let s = 0;
    for (let i = 0; i < v.length; i++) { s += v[i]; if (i >= n) s -= v[i - n]; if (i >= n - 1) out[i] = s / n; }
    return out;
  },
  ema(v, n) {
    const out = NaNs(v.length);
    const start = v.findIndex((x) => !Number.isNaN(x));
    if (start < 0 || v.length - start < n || n <= 0) return out;
    const k = 2 / (n + 1);
    let prev = avg(v.slice(start, start + n));
    out[start + n - 1] = prev;
    for (let i = start + n; i < v.length; i++) { prev = v[i] * k + prev * (1 - k); out[i] = prev; }
    return out;
  },
  rsi(c, n = 14) {
    const out = NaNs(c.length); if (c.length <= n) return out;
    let g = 0, l = 0;
    for (let i = 1; i <= n; i++) { const d = c[i] - c[i - 1]; if (d > 0) g += d; else l -= d; }
    g /= n; l /= n;
    out[n] = l === 0 ? 100 : 100 - 100 / (1 + g / l);
    for (let i = n + 1; i < c.length; i++) {
      const d = c[i] - c[i - 1];
      g = (g * (n - 1) + Math.max(d, 0)) / n; l = (l * (n - 1) + Math.max(-d, 0)) / n;
      out[i] = l === 0 ? 100 : 100 - 100 / (1 + g / l);
    }
    return out;
  },
  macd(c, fast = 12, slow = 26, sig = 9) {
    const f = Ind.ema(c, fast), s = Ind.ema(c, slow);
    const m = c.map((_, i) => (Number.isNaN(f[i]) || Number.isNaN(s[i]) ? NaN : f[i] - s[i]));
    const signal = Ind.ema(m, sig);
    return { macd: m, signal, hist: c.map((_, i) => (Number.isNaN(m[i]) || Number.isNaN(signal[i]) ? NaN : m[i] - signal[i])) };
  },
  bollinger(c, n = 20, k = 2) {
    const mid = Ind.sma(c, n), up = NaNs(c.length), lo = NaNs(c.length);
    for (let i = n - 1; i < c.length; i++) {
      if (Number.isNaN(mid[i])) continue;
      let ss = 0; for (let j = i - n + 1; j <= i; j++) ss += (c[j] - mid[i]) ** 2;
      const sd = Math.sqrt(ss / n); up[i] = mid[i] + k * sd; lo[i] = mid[i] - k * sd;
    }
    return { mid, upper: up, lower: lo };
  },
  smaNan(v, n) {
    const out = NaNs(v.length);
    for (let i = n - 1; i < v.length; i++) {
      let s = 0, okk = true;
      for (let j = i - n + 1; j <= i; j++) { if (Number.isNaN(v[j])) { okk = false; break; } s += v[j]; }
      if (okk) out[i] = s / n;
    }
    return out;
  },
  stochastic(h, l, c, n = 14, sk = 3, sd = 3) {
    const raw = NaNs(c.length);
    for (let i = n - 1; i < c.length; i++) {
      let hh = -Infinity, ll = Infinity;
      for (let j = i - n + 1; j <= i; j++) { hh = Math.max(hh, h[j]); ll = Math.min(ll, l[j]); }
      raw[i] = hh - ll === 0 ? 50 : ((c[i] - ll) / (hh - ll)) * 100;
    }
    const k = Ind.smaNan(raw, sk);
    return { k, d: Ind.smaNan(k, sd) };
  },
  atr(h, l, c, n = 14) {
    const out = NaNs(c.length); if (c.length <= n) return out;
    const tr = c.map((_, i) => (i === 0 ? h[0] - l[0] : Math.max(h[i] - l[i], Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1]))));
    let a = avg(tr.slice(1, n + 1)); out[n] = a;
    for (let i = n + 1; i < c.length; i++) { a = (a * (n - 1) + tr[i]) / n; out[i] = a; }
    return out;
  },
  vwap(bars, intraday, tz = 'UTC', rollingN = 20) {
    const out = NaNs(bars.length);
    if (intraday) {
      let pv = 0, vol = 0, day = '';
      const f = new Intl.DateTimeFormat('en-CA', { timeZone: tz });
      bars.forEach((b, i) => {
        const d = f.format(new Date(b.t * 1000));
        if (d !== day) { pv = 0; vol = 0; day = d; }
        const tp = (b.h + b.l + b.c) / 3; pv += tp * b.v; vol += b.v; out[i] = vol > 0 ? pv / vol : tp;
      });
    } else {
      for (let i = rollingN - 1; i < bars.length; i++) {
        let pv = 0, vol = 0;
        for (let j = i - rollingN + 1; j <= i; j++) { const b = bars[j]; pv += ((b.h + b.l + b.c) / 3) * b.v; vol += b.v; }
        out[i] = vol > 0 ? pv / vol : bars[i].c;
      }
    }
    return out;
  },
  pivots(h, l, left = 3, right = 3) {
    const highs = [], lows = [];
    for (let i = left; i < h.length - right; i++) {
      let isH = true, isL = true;
      for (let j = i - left; j <= i + right; j++) { if (j === i) continue; if (h[j] > h[i]) isH = false; if (l[j] < l[i]) isL = false; }
      if (isH) highs.push(i); if (isL) lows.push(i);
    }
    return { highs, lows };
  },
  srLevels(bars, atr, lookback = 160) {
    if (bars.length < 20 || Number.isNaN(atr) || atr <= 0) return [];
    const sub = bars.slice(-lookback), h = sub.map((b) => b.h), l = sub.map((b) => b.l);
    const p = Ind.pivots(h, l, 3, 3);
    const pts = [...p.highs.map((i) => h[i]), ...p.lows.map((i) => l[i])].sort((a, b) => a - b);
    const cl = [];
    for (const x of pts) { const last = cl[cl.length - 1]; if (last && x - avg(last) <= atr * 0.6) last.push(x); else cl.push([x]); }
    return cl.map((c) => ({ price: avg(c), touches: c.length }));
  },
  volumeProfile(bars, bins = 24, lookback = 120) {
    const sub = bars.slice(-lookback); if (sub.length < 10) return [];
    const lo = Math.min(...sub.map((b) => b.l)), hi = Math.max(...sub.map((b) => b.h)); if (hi <= lo) return [];
    const step = (hi - lo) / bins, vol = new Array(bins).fill(0);
    for (const b of sub) { const tp = (b.h + b.l + b.c) / 3; vol[Math.min(Math.max(Math.floor((tp - lo) / step), 0), bins - 1)] += b.v; }
    return vol.map((v, i) => ({ low: lo + i * step, high: lo + (i + 1) * step, volume: v, mid: lo + (i + 0.5) * step }));
  },
  trendLines(bars, lookback = 120) {
    if (bars.length < 20) return [];
    const off = Math.max(0, bars.length - lookback), sub = bars.slice(off), h = sub.map((b) => b.h), l = sub.map((b) => b.l);
    const p = Ind.pivots(h, l, 4, 4), out = [];
    const mk = (i1, p1, i2, p2, up) => ({ i1, p1, i2, p2, up, at: (i) => p1 + ((p2 - p1) * (i - i1)) / (i2 - i1) });
    if (p.lows.length >= 2) { const a = p.lows.at(-2), b = p.lows.at(-1); if (l[b] > l[a]) out.push(mk(a + off, l[a], b + off, l[b], true)); }
    if (p.highs.length >= 2) { const a = p.highs.at(-2), b = p.highs.at(-1); if (h[b] < h[a]) out.push(mk(a + off, h[a], b + off, h[b], false)); }
    return out;
  },
  krTick(p) { return p < 2000 ? 1 : p < 5000 ? 5 : p < 20000 ? 10 : p < 50000 ? 50 : p < 200000 ? 100 : p < 500000 ? 500 : 1000; },
  roundPrice(p, kr) { if (Number.isNaN(p) || p <= 0) return p; if (kr) { const t = Ind.krTick(p); return Math.round(p / t) * t; } return Math.round(p * 100) / 100; },
};

export function indicatorSet(bars, intraday, tz) {
  const c = bars.map((b) => b.c), h = bars.map((b) => b.h), l = bars.map((b) => b.l), v = bars.map((b) => b.v);
  const ma = {}; for (const n of [5, 10, 20, 60, 120, 200]) ma[n] = Ind.sma(c, n);
  return { bars, c, h, l, v, ma, volMa20: Ind.sma(v, 20), rsi: Ind.rsi(c, 14), macd: Ind.macd(c), bb: Ind.bollinger(c),
    stoch: Ind.stochastic(h, l, c), atr: Ind.atr(h, l, c, 14), vwap: Ind.vwap(bars, intraday, tz), trendLines: Ind.trendLines(bars) };
}
