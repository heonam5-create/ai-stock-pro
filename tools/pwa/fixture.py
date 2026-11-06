# 로컬 UI 점검용 가짜 스냅샷 (실데이터 아님 — CI 에서는 snapshot.py 가 실제 데이터 생성)
import json, math, os, random, sys, time, datetime
out = sys.argv[1]; os.makedirs(f"{out}/bars", exist_ok=True); os.makedirs(f"{out}/news", exist_ok=True)
random.seed(1); at = int(time.time())
syms = {"005930.KS": ("삼성전자", 190000, True), "042700.KS": ("한미반도체", 160000, True), "NVDA": ("엔비디아", 180, False), "SNDK": ("샌디스크", 806, False)}
quotes = {}
for s, (n, p, kr) in syms.items():
    rows, px = [], p * 0.7
    d = datetime.date.today() - datetime.timedelta(days=440)
    while len(rows) < 300:
        d += datetime.timedelta(days=1)
        if d.weekday() >= 5: continue
        o = px; px = px * (1 + 0.0015 + 0.02 * (random.random() - 0.5)); h = max(o, px) * 1.01; l = min(o, px) * 0.99
        rows.append([d.strftime("%Y%m%d"), round(o, 2), round(h, 2), round(l, 2), round(px, 2), random.randint(1_000_000, 5_000_000)])
    json.dump(rows, open(f"{out}/bars/{s}.json", "w"))
    last = rows[-1][4]
    quotes[s] = {"itemCode" if kr else "symbolCode": s.split(".")[0], "stockName": n, "closePriceRaw": str(last), "compareToPreviousClosePriceRaw": str(round(last - rows[-2][4], 2)),
                 "fluctuationsRatioRaw": str(round((last / rows[-2][4] - 1) * 100, 2)), "accumulatedTradingVolumeRaw": "3000000", "accumulatedTradingValueRaw": "900000000000" if kr else "20000000000",
                 "localTradedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "marketStatus": "CLOSE", "stockExchangeType": {"zoneId": "Asia/Seoul" if kr else "EST5EDT", "delayTime": 0}}
    json.dump([{"title": f"{n} 사상 최대 실적 기대", "source": "테스트", "link": "https://example.com", "dt": datetime.datetime.now().strftime("%Y%m%d%H%M")}], open(f"{out}/news/{s}.json", "w"), ensure_ascii=False)
json.dump({"at": at, "items": quotes}, open(f"{out}/quotes.json", "w"), ensure_ascii=False)
json.dump({"at": at, "items": {"코스피": {"price": 5000, "pct": -1.2, "localTradedAt": None, "kr": True}}}, open(f"{out}/indices.json", "w"), ensure_ascii=False)
json.dump({"at": at, "items": []}, open(f"{out}/macro.json", "w"))
json.dump({"at": at, "quotes": 4, "bars": list(syms), "news": list(syms), "topKr": 2, "topUs": 2}, open(f"{out}/meta.json", "w"))
