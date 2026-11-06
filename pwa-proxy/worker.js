/**
 * AI 주식 분석센터 PRO — 시세 중계(프록시) Cloudflare Worker (무료 플랜: 하루 10만 요청)
 * 네이버 금융·Yahoo·Google 뉴스는 브라우저 직접 호출을 막으므로(403·CORS) 이 Worker 가 대신 받아 전달한다.
 * - 비밀값 없음 (API 키를 저장하지 않음)
 * - 허용된 데이터 주소(아래 ALLOW)만 중계 → 임의 사이트 프록시로 악용 방지
 * - 허용된 웹앱 출처(ALLOWED_ORIGINS)에만 CORS 허용
 * - 짧은 캐시로 공급원 호출 수를 줄임 (시세 5초, 차트 5분, 뉴스 10분)
 */
const ALLOW = new Set([
  'polling.finance.naver.com', 'api.stock.naver.com', 'm.stock.naver.com', 'ac.stock.naver.com',
  'query1.finance.yahoo.com', 'query2.finance.yahoo.com', 'news.google.com',
]);
const UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1';

function ttl(u) {
  if (u.hostname === 'polling.finance.naver.com') return 5;
  if (u.pathname.includes('/chart/') || u.hostname.includes('yahoo')) return 300;
  if (u.pathname.includes('news') || u.hostname === 'news.google.com') return 600;
  return 60;
}

export default {
  async fetch(req, env, ctx) {
    const origins = (env.ALLOWED_ORIGINS || 'https://heonam5-create.github.io').split(',').map((s) => s.trim());
    const origin = req.headers.get('Origin') || '';
    const cors = {
      'Access-Control-Allow-Origin': origins.includes(origin) ? origin : origins[0],
      'Access-Control-Allow-Methods': 'GET, OPTIONS',
      'Access-Control-Max-Age': '86400',
      Vary: 'Origin',
    };
    if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });
    if (req.method !== 'GET') return new Response('GET only', { status: 405, headers: cors });
    if (origin && !origins.includes(origin)) return new Response('origin not allowed', { status: 403, headers: cors });

    const target = new URL(req.url).searchParams.get('u');
    if (!target) return new Response(JSON.stringify({ ok: true, service: 'ai-stock-pro-proxy' }), { headers: { ...cors, 'Content-Type': 'application/json' } });
    let u;
    try { u = new URL(target); } catch { return new Response('bad url', { status: 400, headers: cors }); }
    if (u.protocol !== 'https:' || !ALLOW.has(u.hostname)) return new Response('host not allowed', { status: 403, headers: cors });

    const cache = caches.default;
    const key = new Request('https://cache.local/' + encodeURIComponent(u.toString()));
    let res = await cache.match(key);
    if (!res) {
      const up = await fetch(u.toString(), { headers: { 'User-Agent': UA, Accept: 'application/json, application/xml, text/xml, */*', 'Accept-Language': 'ko-KR,ko;q=0.9,en;q=0.8' } });
      res = new Response(up.body, { status: up.status, headers: { 'Content-Type': up.headers.get('Content-Type') || 'application/octet-stream', 'Cache-Control': `public, max-age=${ttl(u)}` } });
      if (up.ok) ctx.waitUntil(cache.put(key, res.clone()));
    }
    const h = new Headers(res.headers);
    Object.entries(cors).forEach(([k, v]) => h.set(k, v));
    h.set('X-Proxy-Upstream', u.hostname);
    return new Response(res.body, { status: res.status, headers: h });
  },
};
