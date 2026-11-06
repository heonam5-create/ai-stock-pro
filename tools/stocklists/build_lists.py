#!/usr/bin/env python3
"""
한국·미국 전체 종목 목록 생성 (GitHub Actions 에서 매주 실행, 표준 라이브러리만 사용).

한국: KRX KIND 상장법인목록(공식) + 네이버 시가총액 순위 목록(우선주 등 보완, 시총 순위)
미국: 네이버 거래소별 전체 목록(한글명·네이버 코드) + Nasdaq Trader 공식 심볼 목록(티커 검증)

출력(JSON, 압축 배열):
  stocks_kr.json: {"updated": "...", "source": "...", "items": [[code, name, market(KS/KQ), rank], ...]}
  stocks_us.json: {"updated": "...", "items": [[ticker, nameKr, nameEn, exchange, naverCode, yahooTicker, rank], ...]}
"""
import html, json, re, sys, time, urllib.request, urllib.parse, datetime, os

UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

def get(url, data=None, enc="utf-8", tries=3):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, data=data, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=40) as r:
                return r.read().decode(enc, errors="replace")
        except Exception as e:
            print(f"retry {i+1} {url}: {e}", file=sys.stderr)
            time.sleep(2 * (i + 1))
    raise RuntimeError("failed " + url)

def krx_official():
    txt = get("https://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13", enc="euc-kr")
    out = {}
    for row in re.findall(r"<tr>(.*?)</tr>", txt, re.S):
        tds = [html.unescape(re.sub(r"<[^>]+>", "", t)).strip() for t in re.findall(r"<td[^>]*>(.*?)</td>", row, re.S)]
        if len(tds) < 3:
            continue
        name, market, code = tds[0], re.sub(r"\s+", "", tds[1]), tds[2].strip().upper()
        mk = {"유가": "KS", "코스닥": "KQ"}.get(market)
        if mk and re.fullmatch(r"[0-9A-Z]{6}", code):
            out[code] = [code, name, mk]
    return out

def naver_kr(market):
    items, page = [], 1
    while page < 80:
        j = json.loads(get(f"https://m.stock.naver.com/api/stocks/marketValue/{market}?page={page}&pageSize=100"))
        st = j.get("stocks") or []
        if not st:
            break
        items += st
        page += 1
        time.sleep(0.25)
    return items

def naver_us(exchange):
    items, page = [], 1
    while page < 120:
        j = json.loads(get(f"https://api.stock.naver.com/stock/exchange/{exchange}/marketValue?page={page}&pageSize=100"))
        st = j.get("stocks") or []
        if not st:
            break
        items += st
        if len(items) >= int(j.get("totalCount") or 0):
            break
        page += 1
        time.sleep(0.25)
    return items

def nasdaq_official():
    syms = {}
    t = get("https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt")
    for line in t.splitlines()[1:]:
        p = line.split("|")
        if len(p) > 6 and p[3] == "N" and not line.startswith("File Creation"):
            syms[p[0]] = (p[1], "NASDAQ", p[6] == "Y")
    t = get("https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt")
    exmap = {"N": "NYSE", "A": "AMEX", "P": "NYSE ARCA", "Z": "BATS", "V": "IEX"}
    for line in t.splitlines()[1:]:
        p = line.split("|")
        if len(p) > 6 and p[6] == "N" and not line.startswith("File Creation"):
            syms[p[0]] = (p[1], exmap.get(p[2], p[2]), p[4] == "Y")
    return syms

def main(outdirs):
    now = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).strftime("%Y-%m-%d %H:%M KST")

    # ── 한국 ──
    official = krx_official()
    print("KRX official:", len(official))
    kr = dict(official)
    rank = {}
    for mk, sfx in (("KOSPI", "KS"), ("KOSDAQ", "KQ")):
        lst = naver_kr(mk)
        print("naver", mk, len(lst))
        for i, s in enumerate(lst):
            code = (s.get("itemCode") or "").upper()
            if not re.fullmatch(r"[0-9A-Z]{6}", code):
                continue
            rank[code] = i + 1 if sfx == "KS" else 2000 + i + 1
            if code not in kr:                    # 우선주 등 공식 법인목록에 없는 상장 종목
                kr[code] = [code, s.get("stockName") or code, sfx]
    if len(kr) < 2000:
        raise SystemExit(f"한국 종목 수가 비정상적으로 적습니다: {len(kr)}")
    kr_items = sorted(([c, n, m, rank.get(c, 9999)] for c, n, m in kr.values()), key=lambda x: (x[3], x[1]))

    # ── 미국 ──
    off = nasdaq_official()
    print("nasdaq trader official:", len(off))
    us, seen = [], set()
    for ex in ("NASDAQ", "NYSE", "AMEX"):
        lst = naver_us(ex)
        print("naver", ex, len(lst))
        for i, s in enumerate(lst):
            sym = (s.get("symbolCode") or "").strip()
            ncode = s.get("reutersCode") or ""
            if not sym or not ncode:
                continue
            ticker = sym.replace(" ", ".")           # 네이버 "BRK B" → 공식 "BRK.B"
            if ticker not in off:                    # 공식 목록에 없는 코드는 잘못 연결될 수 있어 제외
                continue
            if ticker in seen:
                continue
            seen.add(ticker)
            yahoo = ticker.replace(".", "-")         # Yahoo: BRK-B
            us.append([ticker, s.get("stockName") or "", s.get("stockNameEng") or off[ticker][0], ex, ncode, yahoo, len(us) + 1])
    # 네이버 목록에 없는 공식 종목도 검색은 가능하게 (시세는 Yahoo 대체)
    for t, (name, ex, etf) in off.items():
        if t in seen or ex not in ("NASDAQ", "NYSE", "AMEX", "NYSE ARCA"):
            continue
        us.append([t, "", name, ex, "", t.replace(".", "-"), 99999])
    if len(us) < 4000:
        raise SystemExit(f"미국 종목 수가 비정상적으로 적습니다: {len(us)}")

    kr_doc = {"updated": now, "source": "KRX KIND 상장법인목록 + 네이버 금융", "items": kr_items}
    us_doc = {"updated": now, "source": "네이버 금융 + Nasdaq Trader 심볼 목록", "items": us}
    for d in outdirs:
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "stocks_kr.json"), "w", encoding="utf-8") as f:
            json.dump(kr_doc, f, ensure_ascii=False, separators=(",", ":"))
        with open(os.path.join(d, "stocks_us.json"), "w", encoding="utf-8") as f:
            json.dump(us_doc, f, ensure_ascii=False, separators=(",", ":"))
    print(f"KR {len(kr_items)}  US {len(us)} (naver-matched {len(seen)})")

if __name__ == "__main__":
    main(sys.argv[1:] or ["data"])
