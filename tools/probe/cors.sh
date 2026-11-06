#!/usr/bin/env bash
O="https://heonam5-create.github.io"
UA="Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1"
for u in \
 "https://polling.finance.naver.com/api/realtime/domestic/stock/005930" \
 "https://polling.finance.naver.com/api/realtime/worldstock/stock/NVDA.O" \
 "https://api.stock.naver.com/chart/domestic/item/005930/day?startDateTime=202610010000&endDateTime=202611062359" \
 "https://api.stock.naver.com/chart/foreign/item/NVDA.O/day?startDateTime=202610010000&endDateTime=202611062359" \
 "https://ac.stock.naver.com/ac?q=abc&target=stock" \
 "https://m.stock.naver.com/api/stocks/marketValue/KOSPI?page=1&pageSize=5" \
 "https://api.stock.naver.com/stock/exchange/NASDAQ/marketValue?page=1&pageSize=5" \
 "https://m.stock.naver.com/api/news/stock/005930?pageSize=2&page=1" \
 "https://api.stock.naver.com/news/worldStock/NVDA.O?pageSize=2&page=1" \
 "https://polling.finance.naver.com/api/realtime/domestic/index/KOSPI" \
 "https://m.stock.naver.com/api/stock/005930/integration" \
 "https://query1.finance.yahoo.com/v8/finance/chart/AAPL?interval=1d&range=5d" \
 "https://news.google.com/rss/search?q=samsung&hl=ko&gl=KR&ceid=KR:ko" \
 "https://finnhub.io/api/v1/quote?symbol=AAPL&token=x" \
 "https://api.openai.com/v1/models" ; do
  echo "=== $u"
  curl -s -o /dev/null -D - -A "$UA" -H "Origin: $O" "$u" | grep -i -E "^HTTP|access-control" | tr -d '\r'
  echo "--- preflight"
  curl -s -o /dev/null -D - -X OPTIONS -A "$UA" -H "Origin: $O" -H "Access-Control-Request-Method: GET" -H "Access-Control-Request-Headers: authorization" "$u" | grep -i -E "^HTTP|access-control" | tr -d '\r'
done
