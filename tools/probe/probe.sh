#!/usr/bin/env bash
# 시세 공급원 실측 스크립트 (GitHub Actions 에서 실행). 비밀값 없음.
set -u
UA="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
echo "NOW UTC: $(date -u '+%F %T')  KST: $(TZ=Asia/Seoul date '+%F %T')  NY: $(TZ=America/New_York date '+%F %T')"
kst() { TZ=Asia/Seoul date -d "@$1" '+%m-%d %H:%M:%S'; }
for s in SNDK NVDA MU TSLA AAPL 005930.KS 000660.KS 035420.KS 035720.KS; do
  echo "=== Yahoo chart $s"
  j=$(curl -sS -A "$UA" "https://query1.finance.yahoo.com/v8/finance/chart/$s?interval=1m&range=1d&includePrePost=true")
  echo "$j" | jq -c '.chart.result[0].meta | {regularMarketPrice, regularMarketTime, chartPreviousClose, previousClose, hasPrePostMarketData, tz:.exchangeTimezoneName, ctp:.currentTradingPeriod}' 2>&1 | head -c 1500; echo
  echo "$j" | jq -r '.chart.result[0] | [.timestamp, .indicators.quote[0].close] | transpose | map(select(.[1]!=null)) | .[-3:][] | "\(.[0]) \(.[1])"' 2>/dev/null | while read t c; do echo "  bar $(kst $t) KST close=$c"; done
done
echo "=== Yahoo v7 quote (crumb)"
CJ=$(mktemp); curl -sS -A "$UA" -c $CJ -o /dev/null https://fc.yahoo.com/ ; CR=$(curl -sS -A "$UA" -b $CJ https://query1.finance.yahoo.com/v1/test/getcrumb); echo "crumb len ${#CR}"
curl -sS -A "$UA" -b $CJ "https://query1.finance.yahoo.com/v7/finance/quote?symbols=SNDK,NVDA,005930.KS&crumb=$CR" | jq -c '.quoteResponse.result[]? | with_entries(select(.key|test("[Mm]arket(Price|Time|State)|^overnight|^post|^pre|regularMarketPreviousClose|quoteSourceName|exchangeDataDelayedBy|symbol")))' 2>&1 | head -c 4000; echo
echo "=== Naver domestic polling"
curl -sS -A "$UA" "https://polling.finance.naver.com/api/realtime/domestic/stock/005930" | head -c 1500; echo
echo "=== Naver m.stock basic 005930"
curl -sS -A "$UA" "https://m.stock.naver.com/api/stock/005930/basic" | head -c 2000; echo
for s in SNDK.O NVDA.O AAPL.O TSLA.O MU.O; do
  echo "=== Naver US basic $s"
  curl -sS -A "$UA" "https://api.stock.naver.com/stock/$s/basic" | head -c 2500; echo
done
echo "=== Naver US polling"
curl -sS -A "$UA" "https://polling.finance.naver.com/api/realtime/worldstock/stock/SNDK.O" | head -c 2500; echo
