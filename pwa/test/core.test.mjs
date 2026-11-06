import test from 'node:test';
import assert from 'node:assert/strict';
import { analyze } from '../js/core/analysis.js';
import { Ind } from '../js/core/indicators.js';
import * as Q from '../js/core/quote.js';
import { Clock } from '../js/core/clock.js';
import { classify } from '../js/core/news.js';
import { score } from '../js/core/recommender.js';

const SNDK = {"reutersCode":"SNDK.O","stockName":"샌디스크","symbolCode":"SNDK","stockExchangeType":{"code":"NSQ","zoneId":"EST5EDT","delayTime":0},"closePrice":"806.43","compareToPreviousClosePrice":"-230.97","compareToPreviousPrice":{"code":"5"},"fluctuationsRatio":"-22.26","localTradedAt":"2026-11-05T16:00:00-05:00","marketStatus":"CLOSE","overMarketPriceInfo":{"tradingSessionType":"AFTER_MARKET","overMarketStatus":"CLOSE","overPrice":"844.99","compareToPreviousPrice":{"code":"2"},"compareToPreviousClosePrice":"38.56","fluctuationsRatio":"4.78","localTradedAt":"2026-11-05T20:00:00-05:00"},"closePriceRaw":"806.43","compareToPreviousClosePriceRaw":"-230.97"};
const SAMSUNG = {"itemCode":"005930","stockName":"삼성전자","stockExchangeType":{"code":"KS","zoneId":"Asia/Seoul","delayTime":0},"closePrice":"191,100","compareToPreviousClosePrice":"-10,400","compareToPreviousPrice":{"code":"5"},"fluctuationsRatio":"-5.16","accumulatedTradingVolume":"30,061,960","marketStatus":"OPEN","localTradedAt":"2026-11-06T14:49:14.101783+09:00","overMarketPriceInfo":{"tradingSessionType":"REGULAR_MARKET","overMarketStatus":"OPEN","overPrice":"191,100","localTradedAt":"2026-11-06T14:49:14.101881+09:00"}};

test('네이버 미국: 정규장 종가와 애프터마켓 분리', () => {
  const s = Q.parseNaverItem(SNDK, false);
  assert.equal(s.price, 806.43); assert.ok(Math.abs(s.prevClose - 1037.4) < 1e-6);
  assert.equal(s.ext.session, 'post'); assert.equal(s.ext.price, 844.99); assert.equal(s.zone, 'America/New_York');
  assert.equal(Clock.fmt(s.tradedAt), '2026-11-06 06:00');
});
test('네이버 국내: 쉼표·NXT·소수점 6자리 시각', () => {
  const s = Q.parseNaverItem(SAMSUNG, true);
  assert.equal(s.price, 191100); assert.equal(s.change, -10400); assert.equal(s.volume, 30061960); assert.equal(s.ext.session, 'nxt');
  assert.equal(Clock.fmt(s.tradedAt), '2026-11-06 14:49');
});
test('특수 티커·다른 종목 연결 방지', () => {
  assert.equal(Q.usCandidates('BRK.B')[0], 'BRKb');
  assert.ok(Q.sameTicker('BRK.B', { symbolCode: 'BRK B' })); assert.ok(!Q.sameTicker('BRK.A', { symbolCode: 'BRK B' }));
});
test('신선도: 지연·미상은 실시간으로 표시하지 않음', () => {
  const n = 1_800_000_000;
  assert.equal(Q.freshness(true, n - 30, n, 0, true), 'realtime');
  assert.equal(Q.freshness(true, n - 30, n, null, false), 'delayed');
  assert.equal(Q.freshness(true, n - 1200, n, 0, true), 'stale');
  assert.equal(Q.freshness(false, n - 9 * 3600, n, 0, true), 'closed');
});
test('NXT 애프터마켓은 정규장이 아님', () => {
  assert.equal(Q.regularOpen(true, 'KR', 1_793_948_280_000), false); // 15:58 KST
  assert.equal(Q.regularOpen(true, 'KR', 1_793_940_000_000), true);  // 13:40 KST
});
test('교차검증', () => {
  const t = 1_800_000_000;
  assert.equal(Q.crossCheck(100, t, true, 'Y', 97, t - 1200).mismatch, false);
  assert.equal(Q.crossCheck(806.43, t, false, 'Y', 850.96, t).mismatch, true);
});
test('parseLocal 시간대 변환', () => {
  assert.equal(Clock.fmt(Clock.parseLocal('20261020090000', 'Asia/Seoul')), '2026-10-20 09:00');
  assert.equal(Clock.fmt(Clock.parseLocal('20261105160000', 'America/New_York'), 'America/New_York'), '2026-11-05 16:00');
});

function series(n, start, drift, vol) {
  let seed = 7n; const rnd = () => { seed = (seed * 6364136223846793005n + 1442695040888963407n) & ((1n << 64n) - 1n); return Number(seed >> 11n) / 2 ** 53; };
  let p = start; const now = 1_793_000_000;
  return Array.from({ length: n }, (_, i) => {
    const o = p, r = drift + vol * (rnd() * 2 - 1) + 0.002 * Math.sin(i / 6), c = o * (1 + r);
    const h = Math.max(o, c) * (1 + vol * 0.5 * rnd()), l = Math.min(o, c) * (1 - vol * 0.5 * rnd()); p = c;
    return { t: now - (n - i) * 86400, o, h, l, c, v: 1e6 * (0.6 + rnd()) };
  });
}
test('분석: 데이터 부족 → 분석 불가', () => {
  assert.equal(analyze({ bars: series(60, 100, 0, 0.01), price: 100, isKr: true, news: [], events: [], nowSec: 1_793_000_000 }).verdict, 'na');
});
test('분석: 상승 추세 → 전략·체결 가능한 1차 매수가', () => {
  const bars = series(400, 50000, 0.004, 0.012), price = bars.at(-1).c;
  const r = analyze({ bars, price, isKr: true, news: [], events: [], nowSec: 1_793_000_000 });
  assert.notEqual(r.verdict, 'na'); assert.equal(r.factors.length, 10); assert.ok(r.score >= 0 && r.score <= 100);
  assert.equal(r.strategy.buys.length, 3); assert.ok(r.strategy.buys[0].price >= price - 1.3 * r.atr); assert.ok(r.strategy.stop.price < r.strategy.buys[2].price);
});
test('분석: 하락 추세는 적극 매수 아님 + 추천 점수', () => {
  const bars = series(400, 50000, -0.004, 0.012);
  assert.notEqual(analyze({ bars, price: bars.at(-1).c, isKr: true, news: [], events: [], nowSec: 1_793_000_000 }).verdict, 'strongBuy');
  const up = series(400, 50000, 0.004, 0.012); const r = analyze({ bars: up, price: up.at(-1).c, isKr: true, news: null, events: [], nowSec: 1_793_000_000 });
  const s = score({ symbol: 'X' }, r, 0); if (s) assert.ok(s.total >= 0 && s.total <= 100);
});
test('지표·뉴스 분류', () => {
  assert.equal(Ind.sma([1, 2, 3, 4], 2).at(-1), 3.5); assert.equal(Ind.roundPrice(191234, true), 191200);
  assert.equal(classify({ title: '삼성전자, 사상 최대 실적 호실적 발표' }).sentiment, 'positive');
});

import { Directory } from '../js/core/directory.js';
import { readFileSync } from 'node:fs';
test('전체 종목 검색 (KRX·Nasdaq 목록)', () => {
  const d = new Directory().load(JSON.parse(readFileSync(new URL('../../data/stocks_kr.json', import.meta.url))), JSON.parse(readFileSync(new URL('../../data/stocks_us.json', import.meta.url))));
  assert.ok(d.counts.kr > 2500 && d.counts.us > 5000);
  assert.equal(d.search('삼성전자')[0].symbol, '005930.KS');
  assert.equal(d.search('한미반도체')[0].symbol, '042700.KS');
  assert.equal(d.search('042700')[0].symbol, '042700.KS');
  assert.equal(d.search('삼전')[0].symbol, '005930.KS');
  assert.equal(d.search('ㅅㅅㅈㅈ')[0].symbol, '005930.KS');
  assert.ok(d.search('한미반도채').some((e) => e.symbol === '042700.KS'));
  assert.equal(d.search('엔비디아')[0].symbol, 'NVDA');
  assert.ok(d.search('nvidia').some((e) => e.symbol === 'NVDA'));
  assert.equal(d.bySymbol('BRK.B').naverCode, 'BRKb');
});
