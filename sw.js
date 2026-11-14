// Service Worker: 앱 화면·종목 목록은 캐시(오프라인 실행), 시세 스냅샷은 항상 네트워크 우선 (오래된 시세를 최신처럼 보이지 않게)
const VERSION = '197';
const SHELL = `shell-${VERSION}`, DATA = `data-${VERSION}`;
const ASSETS = ['./', 'index.html', 'config.js', 'css/app.css', 'manifest.webmanifest', 'icons/icon-192.png', 'icons/apple-touch-icon.png',
  'js/app.js', 'js/chart.js', 'js/core/analysis.js', 'js/core/clock.js', 'js/core/directory.js', 'js/core/fmt.js', 'js/core/indicators.js',
  'js/core/net.js', 'js/core/news.js', 'js/core/quote.js', 'js/core/recommender.js', 'js/core/repo.js', 'js/core/settings.js'];

self.addEventListener('install', (e) => { e.waitUntil(caches.open(SHELL).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting())); });
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== SHELL && k !== DATA).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const u = new URL(e.request.url);
  if (e.request.method !== 'GET' || u.origin !== location.origin) return;           // 프록시·외부 AI 는 캐시하지 않음
  if (u.pathname.includes('/data/snapshot/')) {                                    // 시세: 네트워크 우선, 실패 시에만 마지막 사본
    e.respondWith(fetch(e.request, { cache: 'no-store' }).then((r) => { const c = r.clone(); caches.open(DATA).then((x) => x.put(e.request, c)); return r; }).catch(() => caches.match(e.request)));
    return;
  }
  if (u.pathname.endsWith('/stocks_kr.json') || u.pathname.endsWith('/stocks_us.json')) {  // 종목 목록: 캐시 후 백그라운드 갱신
    e.respondWith(caches.open(DATA).then(async (c) => { const hit = await c.match(e.request); const net = fetch(e.request).then((r) => { if (r.ok) c.put(e.request, r.clone()); return r; }).catch(() => hit); return hit || net; }));
    return;
  }
  e.respondWith(caches.match(e.request, { ignoreSearch: u.pathname.endsWith('/') || u.pathname.endsWith('index.html') }).then((hit) => hit || fetch(e.request)));
});
self.addEventListener('notificationclick', (e) => { e.notification.close(); e.waitUntil(self.clients.matchAll({ type: 'window' }).then((cs) => (cs[0] ? cs[0].focus() : self.clients.openWindow('./')))); });
