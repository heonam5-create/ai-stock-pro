#!/usr/bin/env python3
"""
PWA 스냅샷 수집기 (GitHub Actions 에서 실행, 표준 라이브러리만 사용).
브라우저는 네이버·Yahoo 를 직접 호출할 수 없으므로(CORS·403 차단, 2026-11 실측) 서버 측에서 모아 정적 JSON 으로 배포한다.
- quotes.json : 한국(코스피·코스닥)·미국(나스닥·NYSE·AMEX) 전 종목 현재가 (네이버 시가총액 순위 목록 전체)
- indices.json: 코스피·코스닥·S&P500·나스닥·원달러
- bars/<key>.json : 시가총액 상위 종목 일봉 약 300개 (하루 1회 갱신, 캐시 재사용)
- news/<key>.json : 상위 종목 네이버 뉴스, macro.json: 경제 뉴스(Google 뉴스 RSS)
모든 값에 수집 시각을 기록하며, 앱은 '지연 (스냅샷)' 으로 표시한다.
"""
import json, os, re, sys, time, datetime, urllib.request, urllib.parse, concurrent.futures as cf
from xml.etree import ElementTree as ET

UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
OUT = sys.argv[1] if len(sys.argv) > 1 else "site/data/snapshot"
LISTS = sys.argv[2] if len(sys.argv) > 2 else "data"
TOP_KR = int(os.environ.get("TOP_KR", "300"))
TOP_US = int(os.environ.get("TOP_US", "300"))
NEWS_TOP = int(os.environ.get("NEWS_TOP", "60"))
KST = datetime.timezone(datetime.timedelta(hours=9))

def get(url, tries=3, raw=False):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json, */*"})
            with urllib.request.urlopen(req, timeout=25) as r:
                b = r.read()
                return b if raw else json.loads(b.decode("utf-8"))
        except Exception as e:
            if i == tries - 1:
                print("fail", url, e, file=sys.stderr)
                return None
            time.sleep(1.5 * (i + 1))

def num(s):
    if s is None: return None
    if isinstance(s, (int, float)): return s
    try: return float(str(s).replace(",", "").replace("+", ""))
    except ValueError: return None

def signed(v, o):
    if v is None: return None
    code = ((o or {}).get("compareToPreviousPrice") or {}).get("code")
    return -v if code in ("4", "5") and v > 0 else v

def compact(d):
    """네이버 항목 → 압축 레코드 (앱의 parseNaverItem 이 읽는 원본 필드 일부 그대로 보존)"""
    keep = ["itemCode", "reutersCode", "symbolCode", "stockName", "closePrice", "closePriceRaw", "compareToPreviousClosePrice",
            "compareToPreviousClosePriceRaw", "compareToPreviousPrice", "fluctuationsRatio", "fluctuationsRatioRaw", "openPrice", "openPriceRaw",
            "highPrice", "highPriceRaw", "lowPrice", "lowPriceRaw", "accumulatedTradingVolume", "accumulatedTradingVolumeRaw",
            "accumulatedTradingValueRaw", "marketValueRaw", "localTradedAt", "marketStatus", "overMarketPriceInfo", "currencyType"]
    r = {k: d[k] for k in keep if k in d and d[k] is not None}
    ex = d.get("stockExchangeType") or {}
    r["stockExchangeType"] = {"code": ex.get("code"), "zoneId": ex.get("zoneId"), "delayTime": ex.get("delayTime", 0)}
    if isinstance(r.get("compareToPreviousPrice"), dict): r["compareToPreviousPrice"] = {"code": r["compareToPreviousPrice"].get("code")}
    o = r.get("overMarketPriceInfo")
    if isinstance(o, dict):
        r["overMarketPriceInfo"] = {k: o.get(k) for k in ["tradingSessionType", "overMarketStatus", "overPrice", "overPriceRaw", "compareToPreviousPrice",
                                                           "compareToPreviousClosePrice", "fluctuationsRatio", "localTradedAt"] if o.get(k) is not None}
    if isinstance(r.get("currencyType"), dict): r["currencyType"] = {"code": r["currencyType"].get("code")}
    return r

def ranking(market):
    dom = market in ("KOSPI", "KOSDAQ")
    out, page = [], 1
    while page < 120:
        url = (f"https://m.stock.naver.com/api/stocks/marketValue/{market}?page={page}&pageSize=100" if dom
               else f"https://api.stock.naver.com/stock/exchange/{market}/marketValue?page={page}&pageSize=100")
        j = get(url)
        st = (j or {}).get("stocks") or []
        if not st: break
        out += st
        if not dom and len(out) >= int(j.get("totalCount") or 0): break
        page += 1
        time.sleep(0.15)
    return out

def bars_for(key, kind, code):
    now = datetime.datetime.now(KST)
    start = (now - datetime.timedelta(days=460)).strftime("%Y%m%d0000")
    end = (now + datetime.timedelta(days=1)).strftime("%Y%m%d2359")
    url = f"https://api.stock.naver.com/chart/{kind}/item/{urllib.parse.quote(code)}/day?startDateTime={start}&endDateTime={end}"
    j = get(url)
    if not isinstance(j, list): return key, None
    rows = []
    for o in j:
        c = num(o.get("closePrice"))
        if not c or c <= 0: continue
        op = num(o.get("openPrice")) or c
        rows.append([o.get("localDate"), op, num(o.get("highPrice")) or max(op, c), num(o.get("lowPrice")) or min(op, c), c, num(o.get("accumulatedTradingVolume")) or 0])
    return key, rows[-300:]

def news_for(key, kr, code, name):
    items = []
    if kr:
        j = get(f"https://m.stock.naver.com/api/news/stock/{code}?pageSize=15&page=1")
        for grp in (j or []):
            for o in grp.get("items", []):
                items.append({"title": o.get("titleFull") or o.get("title"), "source": o.get("officeName") or "네이버 뉴스",
                              "link": o.get("mobileNewsUrl") or "", "dt": o.get("datetime")})
    else:
        j = get(f"https://api.stock.naver.com/news/worldStock/{urllib.parse.quote(code)}?pageSize=15&page=1")
        for o in (j or []):
            items.append({"title": o.get("tit"), "source": o.get("ohnm") or "네이버 해외증시",
                          "link": f"https://m.stock.naver.com/worldstock/news/{o.get('oid')}/{o.get('aid')}", "dt": o.get("dt")})
    return key, [i for i in items if i["title"] and i["dt"]]

def macro_news():
    out = []
    for q, ko in [("FOMC OR 금리 OR CPI OR 고용지표 OR 연준", True), ("반도체 업황 OR HBM OR 메모리 가격", True), ('Fed OR CPI OR "jobs report" stocks', False)]:
        qs = urllib.parse.quote(q + " when:7d")
        url = f"https://news.google.com/rss/search?q={qs}&hl=ko&gl=KR&ceid=KR:ko" if ko else f"https://news.google.com/rss/search?q={qs}&hl=en-US&gl=US&ceid=US:en"
        b = get(url, raw=True)
        if not b: continue
        try:
            root = ET.fromstring(b)
        except ET.ParseError:
            continue
        for it in root.iter("item"):
            title = (it.findtext("title") or "").strip(); src = (it.findtext("source") or "").strip()
            if src and title.endswith(" - " + src): title = title[: -len(src) - 3]
            try:
                ts = int(datetime.datetime.strptime(it.findtext("pubDate").strip(), "%a, %d %b %Y %H:%M:%S %Z").replace(tzinfo=datetime.timezone.utc).timestamp())
            except Exception:
                continue
            out.append({"title": title, "source": src or "Google 뉴스", "link": (it.findtext("link") or "").strip(), "ts": ts})
    return out

def main():
    t0 = time.time()
    os.makedirs(f"{OUT}/bars", exist_ok=True); os.makedirs(f"{OUT}/news", exist_ok=True)
    at = int(time.time())
    quotes = {}
    for m in ("KOSPI", "KOSDAQ"):
        for d in ranking(m):
            code = (d.get("itemCode") or "").upper()
            if re.fullmatch(r"[0-9A-Z]{6}", code): quotes[f"{code}.{'KS' if m == 'KOSPI' else 'KQ'}"] = compact(d)
    nkr = len(quotes)
    for m in ("NASDAQ", "NYSE", "AMEX"):
        for d in ranking(m):
            sym = (d.get("symbolCode") or "").strip().replace(" ", ".")
            if sym: quotes[sym] = compact(d)
    print(f"quotes KR {nkr} US {len(quotes) - nkr}")
    if nkr < 1000 or len(quotes) - nkr < 2000:
        raise SystemExit("시세 수집 결과가 비정상적으로 적습니다")
    with open(f"{OUT}/quotes.json", "w", encoding="utf-8") as f:
        json.dump({"at": at, "source": "네이버 금융(서버 수집 스냅샷)", "items": quotes}, f, ensure_ascii=False, separators=(",", ":"))

    idx = {}
    for code, name, dom in [("KOSPI", "코스피", True), ("KOSDAQ", "코스닥", True), (".INX", "S&P 500", False), (".IXIC", "나스닥", False)]:
        j = get(f"https://polling.finance.naver.com/api/realtime/{'domestic' if dom else 'worldstock'}/index/{urllib.parse.quote(code)}")
        d = ((j or {}).get("datas") or [None])[0]
        if d: idx[name] = {"price": num(d.get("closePriceRaw") or d.get("closePrice")), "pct": signed(num(d.get("fluctuationsRatioRaw") or d.get("fluctuationsRatio")), d), "localTradedAt": d.get("localTradedAt"), "kr": dom}
    y = get("https://query1.finance.yahoo.com/v8/finance/chart/KRW=X?interval=1d&range=5d")
    try:
        m = y["chart"]["result"][0]["meta"]; idx["원/달러"] = {"price": m["regularMarketPrice"], "pct": None, "ts": m["regularMarketTime"], "kr": True, "source": "Yahoo Finance"}
    except Exception:
        pass
    with open(f"{OUT}/indices.json", "w", encoding="utf-8") as f:
        json.dump({"at": at, "items": idx}, f, ensure_ascii=False)

    # 상위 종목: 종목 목록의 시가총액 순위 기준
    kr_list = json.load(open(f"{LISTS}/stocks_kr.json", encoding="utf-8"))["items"]
    us_list = json.load(open(f"{LISTS}/stocks_us.json", encoding="utf-8"))["items"]
    ks = sorted([x for x in kr_list if x[3] < 2000], key=lambda x: x[3]); kq = sorted([x for x in kr_list if 2000 <= x[3] < 9999], key=lambda x: x[3])
    n_ks = int(TOP_KR * 0.72)
    top_kr = [f"{x[0]}.{x[2]}" for x in ks[:n_ks] + kq[:TOP_KR - n_ks]]   # 코스피 72% + 코스닥 28%
    top_us = [(x[0], x[4]) for x in sorted(us_list, key=lambda x: x[6]) if x[4]][:TOP_US]

    # 일봉: 오늘(KST) 이미 받은 파일은 재사용 (캐시)
    today = datetime.datetime.now(KST).strftime("%Y%m%d")
    stamp = f"{OUT}/bars/_day.txt"
    fresh = os.path.exists(stamp) and open(stamp).read().strip() == today and os.environ.get("FORCE_BARS") != "1"
    jobs = [] if fresh else [(k, "domestic", k.split(".")[0]) for k in top_kr] + [(t, "foreign", nc) for t, nc in top_us]
    done = 0
    with cf.ThreadPoolExecutor(max_workers=6) as ex:
        for key, rows in ex.map(lambda a: bars_for(*a), jobs):
            if rows and len(rows) >= 20:
                with open(f"{OUT}/bars/{key}.json", "w") as f: json.dump(rows, f, separators=(",", ":"))
                done += 1
    if not fresh: open(stamp, "w").write(today)
    bar_keys = sorted(os.path.basename(p)[:-5] for p in os.listdir(f"{OUT}/bars") if p.endswith(".json"))
    print(f"bars updated {done} (cached={fresh}) total {len(bar_keys)}")

    njobs = [(k, True, k.split(".")[0], None) for k in top_kr[:NEWS_TOP]] + [(t, False, nc, None) for t, nc in top_us[:NEWS_TOP]]
    with cf.ThreadPoolExecutor(max_workers=6) as ex:
        for key, items in ex.map(lambda a: news_for(*a), njobs):
            if items:
                with open(f"{OUT}/news/{key}.json", "w", encoding="utf-8") as f: json.dump(items, f, ensure_ascii=False, separators=(",", ":"))
    with open(f"{OUT}/macro.json", "w", encoding="utf-8") as f:
        json.dump({"at": at, "items": macro_news()}, f, ensure_ascii=False, separators=(",", ":"))
    news_keys = sorted(os.path.basename(p)[:-5] for p in os.listdir(f"{OUT}/news") if p.endswith(".json"))
    with open(f"{OUT}/meta.json", "w", encoding="utf-8") as f:
        json.dump({"at": at, "quotes": len(quotes), "bars": bar_keys, "news": news_keys, "topKr": TOP_KR, "topUs": TOP_US}, f, ensure_ascii=False, separators=(",", ":"))
    print(f"done in {time.time() - t0:.0f}s")

if __name__ == "__main__":
    main()
