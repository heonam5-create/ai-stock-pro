// 숫자 표시 형식 — 안드로이드 Fmt.kt / iOS Fmt.swift 와 동일
const nf = (d) => new Intl.NumberFormat('en-US', { minimumFractionDigits: d, maximumFractionDigits: d });
const ok = (v) => v !== null && v !== undefined && !Number.isNaN(v);
export const Fmt = {
  price(p, kr) {
    if (!ok(p)) return '-';
    if (kr) return nf(0).format(p) + '원';
    if (Math.abs(p) < 1) return '$' + p.toFixed(4);
    return (p < 0 ? '-$' : '$') + nf(2).format(Math.abs(p));
  },
  num(p, kr) { return ok(p) ? nf(kr ? 0 : 2).format(p) : '-'; },
  won(p) { return ok(p) ? nf(0).format(p) + '원' : '-'; },
  pct(p, sign = true) { if (!ok(p)) return '-'; const s = p.toFixed(2) + '%'; return sign && p >= 0 ? '+' + s : s; },
  d(v, n = 2) { return ok(v) ? v.toFixed(n) : '-'; },
  volume(v, kr) {
    if (!ok(v)) return '-';
    if (kr) { if (v >= 1e8) return (v / 1e8).toFixed(2) + '억주'; if (v >= 1e4) return nf(0).format(v / 1e4) + '만주'; return nf(0).format(v) + '주'; }
    if (v >= 1e9) return (v / 1e9).toFixed(2) + 'B'; if (v >= 1e6) return (v / 1e6).toFixed(2) + 'M'; if (v >= 1e3) return (v / 1e3).toFixed(1) + 'K';
    return nf(0).format(v);
  },
  marketCap(v, kr) {
    if (!ok(v) || v <= 0) return '-';
    if (kr) return v >= 1e12 ? nf(1).format(v / 1e12) + '조원' : nf(0).format(v / 1e8) + '억원';
    if (v >= 1e12) return '$' + (v / 1e12).toFixed(2) + 'T'; if (v >= 1e9) return '$' + (v / 1e9).toFixed(1) + 'B';
    return '$' + (v / 1e6).toFixed(0) + 'M';
  },
};
