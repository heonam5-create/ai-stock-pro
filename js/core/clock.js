// 장 운영 시간 판단 + 시각 표시 (공휴일 미반영 → 화면에 안내)
const parts = (date, tz) => {
  const o = {};
  for (const p of new Intl.DateTimeFormat('en-US', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', weekday: 'short', hourCycle: 'h23' }).formatToParts(date)) o[p.type] = p.value;
  return o;
};
export const SEOUL = 'Asia/Seoul', NY = 'America/New_York';
export const Clock = {
  isOpen(market, nowMs = Date.now()) {
    const p = parts(new Date(nowMs), market === 'KR' ? SEOUL : NY);
    if (p.weekday === 'Sat' || p.weekday === 'Sun') return false;
    const m = +p.hour * 60 + +p.minute;
    return market === 'KR' ? m >= 540 && m < 930 : m >= 570 && m < 960;
  },
  fmt(sec, tz = SEOUL, withSec = false) {
    const p = parts(new Date(sec * 1000), tz);
    return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}${withSec ? ':' + p.second : ''}`;
  },
  day(sec, tz) { const p = parts(new Date(sec * 1000), tz); return `${p.year}-${p.month}-${p.day}`; },
  format(sec, market) {
    const k = Clock.fmt(sec, SEOUL);
    return market === 'US' ? `${k} (한국) / ${Clock.fmt(sec, NY)} (뉴욕)` : `${k} (한국)`;
  },
  relative(sec, nowMs = Date.now()) {
    const d = Math.floor(nowMs / 1000) - sec;
    if (d < 60) return '방금'; if (d < 3600) return `${Math.floor(d / 60)}분 전`; if (d < 86400) return `${Math.floor(d / 3600)}시간 전`;
    return `${Math.floor(d / 86400)}일 전`;
  },
  /** 지정 시간대의 yyyyMMdd(HHmmss) 문자열 → epoch 초 */
  parseLocal(s, tz) {
    const y = +s.slice(0, 4), mo = +s.slice(4, 6) - 1, d = +s.slice(6, 8), h = +(s.slice(8, 10) || 0), mi = +(s.slice(10, 12) || 0), se = +(s.slice(12, 14) || 0);
    const guess = Date.UTC(y, mo, d, h, mi, se);
    const p = parts(new Date(guess), tz);
    const asUtc = Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
    return Math.round((guess - (asUtc - guess)) / 1000);
  },
};

const FOMC = ['2026-01-28', '2026-03-18', '2026-04-29', '2026-06-17', '2026-07-29', '2026-09-16', '2026-10-28', '2026-12-09',
  '2027-01-27', '2027-03-17', '2027-04-28', '2027-06-09', '2027-07-28', '2027-09-15', '2027-10-27', '2027-12-08'];
export const Events = {
  upcomingMacro(nowMs = Date.now(), limit = 3) {
    const today = Clock.day(Math.floor(nowMs / 1000), SEOUL);
    const t0 = Date.parse(today + 'T00:00:00Z');
    const out = FOMC.map((s) => ({ date: s, title: 'FOMC 금리 결정', detail: '뉴욕 오후 2시 발표 (한국 다음날 새벽) · 결과에 따라 변동성 확대 가능', daysLeft: Math.round((Date.parse(s + 'T00:00:00Z') - t0) / 86400000) }))
      .filter((e) => e.daysLeft >= -1).slice(0, limit);
    return out.length ? out : [{ date: '-', title: 'FOMC 일정 갱신 필요', detail: '내장 일정이 끝났습니다. 앱 업데이트가 필요합니다.', daysLeft: 999 }];
  },
};
