// 네트워크: 실시간 모드는 사용자의 Cloudflare Worker 프록시를 통해서만 네이버·Yahoo 에 접근 (브라우저 직접 호출은 공급원이 차단)
import { Settings } from './settings.js';

export class NoLive extends Error { constructor() { super('실시간 프록시 미설정'); this.noLive = true; } }

export function proxyBase() { return (Settings.get('proxy') || (globalThis.APP_CONFIG?.proxy ?? '')).replace(/\/+$/, ''); }
export const liveAvailable = () => !!proxyBase();

async function withTimeout(url, opts = {}, ms = 15000) {
  const ac = new AbortController(); const t = setTimeout(() => ac.abort(), ms);
  try { return await fetch(url, { ...opts, signal: ac.signal, cache: 'no-store' }); } finally { clearTimeout(t); }
}

/** 공급원 주소를 프록시로 조회 */
export async function live(url, { text = false, retries = 1 } = {}) {
  const base = proxyBase(); if (!base) throw new NoLive();
  let last;
  for (let a = 0; a <= retries; a++) {
    try {
      const r = await withTimeout(`${base}/?u=${encodeURIComponent(url)}`);
      if (r.ok) return text ? r.text() : r.json();
      last = new Error(`HTTP ${r.status}`); last.code = r.status;
      if (r.status !== 429 && r.status < 500) break;
    } catch (e) { last = e; }
    if (a < retries) await new Promise((res) => setTimeout(res, 800 * (a + 1)));
  }
  throw last;
}

/** 같은 사이트의 정적 파일 (스냅샷·종목 목록) */
export async function local(path, { optional = false } = {}) {
  const r = await withTimeout(path, {}, 20000);
  if (!r.ok) { if (optional) return null; throw new Error(`${path} HTTP ${r.status}`); }
  return r.json();
}

/** CORS 를 허용하는 공급원(Finnhub·OpenAI 등) 직접 호출 */
export async function direct(url, opts = {}) {
  const r = await withTimeout(url, opts, 60000);
  const body = await r.text();
  if (!r.ok) { const e = new Error(`HTTP ${r.status}: ${body.slice(0, 160)}`); e.code = r.status; throw e; }
  return JSON.parse(body);
}

export function friendly(e) {
  if (e?.noLive) return '실시간 프록시가 설정되지 않아 스냅샷 데이터를 사용합니다.';
  if (e?.name === 'AbortError') return '서버 응답이 늦습니다. 잠시 후 다시 시도하세요.';
  if (e?.code === 429) return '데이터 제공처의 호출 한도에 잠시 걸렸습니다. 1~2분 뒤 다시 시도하세요.';
  if (e?.code === 403) return '데이터 제공처 또는 프록시가 요청을 거부했습니다.';
  if (e instanceof TypeError) return '네트워크 오류가 발생했습니다. 인터넷 연결을 확인하세요.';
  return e?.message || String(e);
}
