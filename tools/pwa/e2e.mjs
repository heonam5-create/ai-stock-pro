// PWA 실제 동작 점검 (Playwright · iPhone 15 에뮬레이션): 각 화면을 열고 JS 오류·핵심 요소를 확인, 스크린샷 저장
// 사용: node tools/pwa/e2e.mjs <사이트 URL> <스크린샷 폴더>
import { createRequire } from 'node:module';
const { chromium, devices } = createRequire(import.meta.url)('playwright');   // 전역 설치본 사용 (NODE_PATH)
import fs from 'node:fs';
const base = process.argv[2] || 'http://localhost:8080/', out = process.argv[3] || 'shots';
fs.mkdirSync(out, { recursive: true });
const browser = await chromium.launch();
const ctx = await browser.newContext({ ...devices['iPhone 15'], deviceScaleFactor: 2, locale: 'ko-KR', timezoneId: 'Asia/Seoul' });
const errors = [], results = [];
const check = (name, ok, detail = '') => { results.push({ name, ok, detail }); console.log(`${ok ? '✅' : '❌'} ${name} ${detail}`); };
async function page(url, name, waitFor, extra) {
  const p = await ctx.newPage();
  p.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
  p.on('console', (m) => { if (m.type() === 'error') errors.push(`${name} console: ${m.text()}`); });
  await p.goto(base + url, { waitUntil: 'domcontentloaded' });
  await p.waitForFunction(() => !document.getElementById('splash'), null, { timeout: 30_000 }).catch(() => {});
  try { await p.waitForFunction(waitFor, null, { timeout: 90_000 }); check(name, true); } catch { check(name, false, '대기 조건 시간 초과'); }
  if (extra) await extra(p);
  await p.screenshot({ path: `${out}/${name}.png` });
  return p;
}
const has = (t) => `document.body.innerText.includes(${JSON.stringify(t)})`;
const notLoading = `!document.querySelector('.loading')`;
await page('', '1_home', `${has('시장 지수')} && ${notLoading}`, async (p) => { check('홈: 지수 표시', await p.evaluate(() => /코스피|S&P/.test(document.body.innerText))); });
await page('?tab=analysis', '2_search', has('종목 분석') , async (p) => {
  await p.fill('#q', 'ㅅㅅㅈㅈ'); await p.waitForTimeout(700);
  check('검색: 초성 ㅅㅅㅈㅈ → 삼성전자', await p.evaluate(() => document.querySelector('#results')?.innerText.includes('삼성전자')));
  await p.fill('#q', '한미반도채'); await p.waitForTimeout(700);
  check('검색: 오타 한미반도채 → 한미반도체', await p.evaluate(() => document.querySelector('#results')?.innerText.includes('한미반도체')));
  await p.fill('#q', 'BRK.B'); await p.waitForTimeout(700);
  check('검색: BRK.B', await p.evaluate(() => document.querySelector('#results')?.innerText.includes('버크셔')));
});
await page('?symbol=005930.KS', '3_quote_samsung', `document.querySelector('.price') && document.querySelector('#chart')`, async (p) => {
  const t = await p.evaluate(() => document.body.innerText);
  check('시세: 가격·상태·제공업체 표시', /원/.test(t) && /(실시간|지연|장 마감|스냅샷)/.test(t) && t.includes('데이터 제공'));
});
await page('?symbol=005930.KS&analyze=1', '4_analysis_samsung', `${has('AI 종합 판단')} || ${has('분석 불가')}`, async (p) => {
  await p.evaluate(() => document.getElementById('verdict')?.scrollIntoView()); await p.waitForTimeout(500);
  const t = await p.evaluate(() => document.body.innerText);
  check('분석: 10개 항목', t.includes('10개 항목 상세 분석')); check('분석: 매수·매도 전략 또는 보호 안내', t.includes('1차 매수가') || t.includes('표시하지 않습니다'));
});
await page('?symbol=NVDA&analyze=1', '5_analysis_nvda', `${has('AI 종합 판단')} || ${has('분석 불가')}`, async (p) => { await p.evaluate(() => document.getElementById('verdict')?.scrollIntoView()); await p.waitForTimeout(500); });
await page('?tab=best&run=1&market=KR', '6_best5_kr', `${has('분석 완료')} || ${has('다시 시도')}`, async (p) => {
  const t = await p.evaluate(() => document.body.innerText); check('BEST5: 분석 종목 수 표시', /실제 일봉 분석 \d+종목/.test(t));
});
await page('?tab=settings', '7_settings', has('데이터 모드'), async (p) => {
  const t = await p.evaluate(() => document.body.innerText); const m = t.match(/한국\s+(\d+)종목/); check('설정: 전체 종목 목록 로드', !!m && +m[1] > 2500, m?.[1] || '');
});
// PWA 요건
const p = await ctx.newPage(); await p.goto(base, { waitUntil: 'load' });
const man = await p.evaluate(async () => { const l = document.querySelector('link[rel=manifest]'); const r = await fetch(l.href); return r.json(); });
check('manifest: standalone·아이콘', man.display === 'standalone' && man.icons.length >= 2);
check('apple-touch-icon·standalone 메타', await p.evaluate(() => !!document.querySelector('link[rel=apple-touch-icon]') && document.querySelector('meta[name=apple-mobile-web-app-capable]')?.content === 'yes'));
if (base.startsWith('https') || base.includes('localhost')) {
  const sw = await p.evaluate(async () => { try { const r = await navigator.serviceWorker.register('sw.js'); await navigator.serviceWorker.ready; return !!r; } catch (e) { return String(e); } });
  check('Service Worker 등록', sw === true, sw === true ? '' : sw);
}
check('JS 오류 없음', errors.length === 0, errors.slice(0, 5).join(' | '));
fs.writeFileSync(`${out}/results.json`, JSON.stringify({ base, results, errors }, null, 1));
await browser.close();
process.exit(results.every((r) => r.ok) ? 0 : 1);
