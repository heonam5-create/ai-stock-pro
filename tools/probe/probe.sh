#!/usr/bin/env bash
set -u
UA="Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148"
g() { echo "=== $1"; curl -sS -A "$UA" -w "\nHTTP %{http_code}\n" "$1" | head -c "${2:-1500}"; echo; }
g "https://ac.stock.naver.com/ac?q=%EC%97%94%EB%B9%84%EB%94%94%EC%95%84&target=stock" 2000
g "https://ac.stock.naver.com/ac?q=BRK&target=stock" 2500
g "https://ac.stock.naver.com/ac?q=%ED%95%9C%EB%AF%B8%EB%B0%98&target=stock" 1500
g "https://m.stock.naver.com/api/stocks/marketValue/KOSPI?page=1&pageSize=3" 2500
g "https://m.stock.naver.com/api/stocks/marketValue/KOSDAQ?page=1&pageSize=2" 1200
g "https://api.stock.naver.com/stock/exchange/NASDAQ/marketValue?page=1&pageSize=2" 2500
g "https://api.stock.naver.com/stock/exchange/NYSE/marketValue?page=1&pageSize=2" 1500
g "https://api.stock.naver.com/stock/exchange/AMEX/marketValue?page=1&pageSize=2" 800
g "https://polling.finance.naver.com/api/realtime/worldstock/stock/BRKb" 600
g "https://polling.finance.naver.com/api/realtime/worldstock/stock/BRK_b" 600
g "https://api.stock.naver.com/chart/domestic/item/005930/minute?startDateTime=202611060900&endDateTime=202611061530" 600
g "https://api.stock.naver.com/chart/foreign/item/NVDA.O/minute?startDateTime=202611050930&endDateTime=202611051600" 600
g "https://api.stock.naver.com/chart/domestic/item/005930/week?startDateTime=202501010000&endDateTime=202611062359" 400
g "https://api.stock.naver.com/chart/domestic/item/005930/month?startDateTime=201501010000&endDateTime=202611062359" 400
g "https://m.stock.naver.com/api/news/stock/005930?pageSize=3&page=1" 1500
g "https://api.stock.naver.com/news/worldStock/NVDA.O?pageSize=2&page=1" 1200
g "https://m.stock.naver.com/front-api/marketIndex/prices?category=exchange&reutersCode=FX_USDKRW&page=1&pageSize=1" 800
g "https://polling.finance.naver.com/api/realtime/domestic/index/KOSPI" 800
g "https://polling.finance.naver.com/api/realtime/worldstock/index/.IXIC" 800
echo "=== KRX corpList"
curl -sS -A "$UA" -o krx.html -w "HTTP %{http_code} size %{size_download}\n" "https://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13"
iconv -f euc-kr -t utf-8 krx.html 2>/dev/null | head -c 1500; echo; iconv -f euc-kr -t utf-8 krx.html | grep -c "<tr" 
echo "=== KRX data.krx (OTP-less finder)"
curl -sS -A "$UA" -X POST -H "Referer: http://data.krx.co.kr/" --data "bld=dbms/comm/finder/finder_stkisu&mktsel=ALL&searchText=&typeNo=0" "http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd" | head -c 800; echo
echo "=== Nasdaq trader"
curl -sS -A "$UA" "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt" | head -5
curl -sS -A "$UA" "https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt" | (head -5; grep "^BRK" )
curl -sS -A "$UA" "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt" | wc -l
