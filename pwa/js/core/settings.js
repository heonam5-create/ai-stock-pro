// 기기 내 저장 (localStorage). API 키는 이 기기 브라우저에만 저장되며 서버·저장소로 전송되지 않음
const mem = {};
const ls = (() => { try { const t = '__t'; localStorage.setItem(t, t); localStorage.removeItem(t); return localStorage; } catch { return null; } })();
export const Settings = {
  get(k, def = null) { try { const v = ls ? ls.getItem('aisp.' + k) : mem[k]; return v === null || v === undefined ? def : JSON.parse(v); } catch { return def; } },
  set(k, v) { const s = JSON.stringify(v); if (ls) { try { ls.setItem('aisp.' + k, s); } catch { /* 용량 초과 */ } } else mem[k] = s; },
  remove(k) { if (ls) ls.removeItem('aisp.' + k); else delete mem[k]; },
};
