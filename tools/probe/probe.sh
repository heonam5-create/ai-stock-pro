#!/usr/bin/env bash
# 시세 공급원 실측 스크립트 (GitHub Actions 에서 실행). 비밀값 없음.
set -u
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
echo "NOW UTC: $(date -u '+%F %T')  KST: $(TZ=Asia/Seoul date '+%F %T')  NY: $(TZ=America/New_York date '+%F %T')"
for s in SNDK 005930.KS; do
  for h in query1 query2; do
    echo "=== Yahoo chart $h $s"
    curl -sS -A "$UA" -w "\nHTTP %{http_code}\n" "https://$h.finance.yahoo.com/v8/finance/chart/$s?interval=1d&range=5d&includePrePost=true" | head -c 1200; echo
  done
done
echo "=== Yahoo v7 quote (crumb)"
CJ=$(mktemp); curl -sS -A "$UA" -c $CJ -o /dev/null https://fc.yahoo.com/ ; CR=$(curl -sS -A "$UA" -b $CJ https://query1.finance.yahoo.com/v1/test/getcrumb); echo "crumb: $(echo "$CR" | head -c 60)"
curl -sS -G -A "$UA" -b $CJ --data-urlencode "crumb=$CR" "https://query1.finance.yahoo.com/v7/finance/quote?symbols=SNDK,005930.KS" | head -c 2500; echo
for s in 000660 035420 035720; do
  echo "=== Naver KR $s"
  curl -sS -A "$UA" "https://polling.finance.naver.com/api/realtime/domestic/stock/$s" | jq -c '.datas[0] | {stockName,closePrice,marketStatus,localTradedAt,delay:.stockExchangeType.delayTime,over:.overMarketPriceInfo}'
done
for s in JPM KO SPY BRK.B AMD; do for x in O N K A ""; do
  c="$s${x:+.$x}"
  r=$(curl -sS -A "$UA" "https://polling.finance.naver.com/api/realtime/worldstock/stock/$c")
  echo "Naver US $c -> $(echo "$r" | jq -c '.datas[0]? | {reutersCode,closePrice,marketStatus,localTradedAt,ex:.stockExchangeType.code}' 2>/dev/null | head -c 300)"
done; done
echo "=== Naver US multi"
curl -sS -A "$UA" "https://polling.finance.naver.com/api/realtime/worldstock/stock/NVDA.O,AAPL.O" | head -c 300; echo
echo "=== Naver chart KR day"
curl -sS -A "$UA" "https://api.stock.naver.com/chart/domestic/item/005930/day?startDateTime=202610200000&endDateTime=202611062359" | head -c 700; echo
echo "=== Naver chart US day"
curl -sS -A "$UA" "https://api.stock.naver.com/chart/foreign/item/SNDK.O/day?startDateTime=202610200000&endDateTime=202611062359" | head -c 700; echo
echo "=== Naver US search-ish"
curl -sS -A "$UA" "https://api.stock.naver.com/stock/SNDK.O/integration" | head -c 400; echo
