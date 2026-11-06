import XCTest
@testable import AIStockCore

final class QuoteLogicTests: XCTestCase {
    // 2026-11-06 14:49 KST 실측 응답 — SNDK: 정규장 종가 806.43, 애프터마켓 844.99
    let sndk = #"{"pollingInterval":70000,"datas":[{"reutersCode":"SNDK.O","stockName":"샌디스크","symbolCode":"SNDK","stockExchangeType":{"code":"NSQ","zoneId":"EST5EDT","delayTime":0},"closePrice":"806.43","compareToPreviousClosePrice":"-230.97","compareToPreviousPrice":{"code":"5"},"fluctuationsRatio":"-22.26","openPrice":"962.25","highPrice":"967.99","lowPrice":"803.97","accumulatedTradingVolume":"30,722,648","localTradedAt":"2026-11-05T16:00:00-05:00","marketStatus":"CLOSE","overMarketPriceInfo":{"tradingSessionType":"AFTER_MARKET","overMarketStatus":"CLOSE","overPrice":"844.99","compareToPreviousPrice":{"code":"2"},"compareToPreviousClosePrice":"38.56","fluctuationsRatio":"4.78","localTradedAt":"2026-11-05T20:00:00-05:00"},"currencyType":{"code":"USD"},"closePriceRaw":"806.43","compareToPreviousClosePriceRaw":"-230.97","fluctuationsRatioRaw":"-22.26","marketValueFullRaw":"114746412617"}]}"#
    let samsung = #"{"pollingInterval":7000,"datas":[{"itemCode":"005930","stockName":"삼성전자","stockExchangeType":{"code":"KS","zoneId":"Asia/Seoul","delayTime":0},"closePrice":"191,100","compareToPreviousClosePrice":"-10,400","compareToPreviousPrice":{"code":"5"},"fluctuationsRatio":"-5.16","openPrice":"180,000","highPrice":"196,500","lowPrice":"179,200","accumulatedTradingVolume":"30,061,960","marketStatus":"OPEN","localTradedAt":"2026-11-06T14:49:14.101783+09:00","overMarketPriceInfo":{"tradingSessionType":"REGULAR_MARKET","overMarketStatus":"OPEN","overPrice":"191,100","compareToPreviousPrice":{"code":"5"},"compareToPreviousClosePrice":"-10,400","fluctuationsRatio":"-5.16","localTradedAt":"2026-11-06T14:49:14.101881+09:00"}}]}"#
    let brk = #"{"pollingInterval":70000,"datas":[{"reutersCode":"BRKb","stockName":"버크셔 해서웨이 Class B","symbolCode":"BRK B","stockExchangeType":{"code":"NYS","zoneId":"EST5EDT","delayTime":0},"closePrice":"572.33","compareToPreviousClosePrice":"-6.76","compareToPreviousPrice":{"code":"5"},"fluctuationsRatio":"-1.17","marketStatus":"CLOSE","localTradedAt":"2026-11-05T16:00:00-05:00"}]}"#

    func testUsRegularAndAfterMarketSeparate() throws {
        let s = try NaverSource.parse(Data(sndk.utf8), kr: false)
        XCTAssertEqual(s.price, 806.43, accuracy: 1e-9)
        XCTAssertEqual(s.prevClose!, 1037.40, accuracy: 1e-6)
        XCTAssertFalse(s.isOpen)
        XCTAssertEqual(s.zone, "America/New_York")
        XCTAssertEqual(s.ext?.session, .post)
        XCTAssertEqual(s.ext!.price, 844.99, accuracy: 1e-9)
        XCTAssertEqual(MarketClock.formatKst(s.tradedAt), "2026-11-06 06:00")
        XCTAssertEqual(s.marketCap!, 114_746_412_617, accuracy: 1)
    }

    func testKrCommasNxtAndFractionalTime() throws {
        let s = try NaverSource.parse(Data(samsung.utf8), kr: true)
        XCTAssertEqual(s.price, 191_100)
        XCTAssertEqual(s.change!, -10_400)
        XCTAssertEqual(s.volume!, 30_061_960)
        XCTAssertTrue(s.isOpen)
        XCTAssertEqual(s.ext?.session, .nxt)
        XCTAssertEqual(MarketClock.formatKst(s.tradedAt), "2026-11-06 14:49")
    }

    func testSpecialTickerMappingAndCompanyCheck() throws {
        XCTAssertEqual(NaverSource.usCandidates("BRK.B", known: nil).first, "BRKb")
        XCTAssertEqual(NaverSource.usCandidates("NVDA", known: nil), ["NVDA.O", "NVDA", "NVDA.K"])
        XCTAssertEqual(NaverSource.usCandidates("JPM", known: "JPM"), ["JPM"])
        let s = try NaverSource.parse(Data(brk.utf8), kr: false)
        XCTAssertTrue(NaverSource.matches(ticker: "BRK.B", snap: s))
        XCTAssertFalse(NaverSource.matches(ticker: "BRK.A", snap: s))   // 다른 종목 가격 연결 방지
    }

    func testFreshnessNeverClaimsRealtimeForOldOrUnknownDelay() {
        let now: Int64 = 1_800_000_000
        XCTAssertEqual(QuoteLogic.freshness(open: true, tradedAt: now - 30, nowSec: now, delayMin: 0, sourceRealtime: true), .realtime)
        XCTAssertEqual(QuoteLogic.freshness(open: true, tradedAt: now - 30, nowSec: now, delayMin: nil, sourceRealtime: false), .delayed)
        XCTAssertEqual(QuoteLogic.freshness(open: true, tradedAt: now - 30, nowSec: now, delayMin: 15, sourceRealtime: true), .delayed)
        XCTAssertEqual(QuoteLogic.freshness(open: true, tradedAt: now - 1200, nowSec: now, delayMin: 0, sourceRealtime: true), .stale)
        XCTAssertEqual(QuoteLogic.freshness(open: false, tradedAt: now - 9 * 3600, nowSec: now, delayMin: 0, sourceRealtime: true), .closed)
        XCTAssertEqual(QuoteLogic.freshness(open: false, tradedAt: now - 6 * 86400, nowSec: now, delayMin: 0, sourceRealtime: true), .stale)
    }

    func testCrossCheck() {
        let t: Int64 = 1_800_000_000
        let skipped = QuoteLogic.crossCheck(primaryPrice: 100, primaryTime: t, primaryOpen: true, source: "Yahoo", otherPrice: 97, otherTime: t - 1200)
        XCTAssertFalse(skipped.mismatch); XCTAssertTrue(skipped.message.contains("비교 생략"))
        XCTAssertFalse(QuoteLogic.crossCheck(primaryPrice: 100, primaryTime: t, primaryOpen: false, source: "Yahoo", otherPrice: 100.1, otherTime: t).mismatch)
        XCTAssertTrue(QuoteLogic.crossCheck(primaryPrice: 806.43, primaryTime: t, primaryOpen: false, source: "Yahoo", otherPrice: 850.96, otherTime: t).mismatch)
        XCTAssertFalse(QuoteLogic.crossCheck(primaryPrice: 100, primaryTime: t, primaryOpen: true, source: "Yahoo", otherPrice: nil, otherTime: nil).mismatch)
    }

    func testAlignDaily() {
        let z = MarketClock.seoul
        let d1: Int64 = 1_793_000_000
        let bars = [Bar(t: d1 - 86400, o: 10, h: 11, l: 9, c: 10.5, v: 100), Bar(t: d1, o: 10.5, h: 11, l: 10, c: 10.8, v: 100)]
        let same = QuoteLogic.alignDaily(bars, price: 12, time: d1 + 3600, zone: z)
        XCTAssertEqual(same.count, 2); XCTAssertEqual(same.last!.c, 12); XCTAssertEqual(same.last!.h, 12)
        XCTAssertEqual(QuoteLogic.alignDaily(bars, price: 9.5, time: d1 + 86400, zone: z).count, 3)
        XCTAssertEqual(QuoteLogic.alignDaily(bars, price: 9.5, time: d1 - 3 * 86400, zone: z), bars)
    }

    func testKoreanAmount() {
        XCTAssertEqual(NaverSource.koreanAmount("1,103조 1,928억원")!, 1103e12 + 1928e8, accuracy: 1)
    }

    func testCandlesParse() throws {
        let j = #"[{"localDate":"20261020","closePrice":209000.0,"openPrice":219500.0,"highPrice":219500.0,"lowPrice":208000.0,"accumulatedTradingVolume":16561224},{"localDate":"20261021","closePrice":223500.0,"openPrice":217000.0,"highPrice":228000.0,"lowPrice":217000.0,"accumulatedTradingVolume":17268762}]"#
        let b = try NaverSource.parseCandles(Data(j.utf8), zone: MarketClock.seoul, openHour: 9, openMinute: 0)
        XCTAssertEqual(b.count, 2); XCTAssertEqual(b[1].c, 223_500)
        XCTAssertEqual(MarketClock.formatKst(b[0].t), "2026-10-20 09:00")
    }
}

final class DirectoryTests: XCTestCase {
    let dir = StockDirectory.shared

    func testFullListsLoaded() {
        let c = dir.counts
        XCTAssertGreaterThan(c.kr, 2500, "한국 전체 종목 목록이 내장되어야 함")
        XCTAssertGreaterThan(c.us, 5000, "미국 전체 종목 목록이 내장되어야 함")
    }

    func testKoreanSearch() {
        XCTAssertEqual(dir.search("삼성전자").first?.symbol, "005930.KS")
        XCTAssertEqual(dir.search("한미반도체").first?.symbol, "042700.KS")
        XCTAssertEqual(dir.search("042700").first?.symbol, "042700.KS")
        XCTAssertEqual(dir.search("삼전").first?.symbol, "005930.KS")              // 별명
        XCTAssertEqual(dir.search("ㅅㅅㅈㅈ").first?.symbol, "005930.KS")          // 초성
        XCTAssertTrue(dir.search("셀트").contains { $0.symbol == "068270.KS" })      // 부분 검색
        XCTAssertTrue(dir.search("한미반도채").contains { $0.symbol == "042700.KS" })  // 오타 1자
    }

    func testUsSearch() {
        XCTAssertEqual(dir.search("엔비디아").first?.symbol, "NVDA")
        XCTAssertEqual(dir.search("NVDA").first?.symbol, "NVDA")
        XCTAssertTrue(dir.search("nvidia").contains { $0.symbol == "NVDA" })
        let brk = dir.bySymbol("BRK.B")
        XCTAssertEqual(brk?.naverCode, "BRKb"); XCTAssertEqual(brk?.yahoo, "BRK-B")
        XCTAssertEqual(dir.search("샌디스크").first?.symbol, "SNDK")
    }
}

final class AnalysisTests: XCTestCase {
    func series(_ n: Int, start: Double, drift: Double, vol: Double) -> [Bar] {
        var seed: UInt64 = 7
        func rnd() -> Double { seed = seed &* 6364136223846793005 &+ 1442695040888963407; return Double(seed >> 11) / Double(1 << 53) }
        var p = start
        let now: Int64 = 1_793_000_000
        return (0..<n).map { i in
            let o = p
            let r = drift + vol * (rnd() * 2 - 1) + 0.002 * sin(Double(i) / 6)
            let c = o * (1 + r)
            let h = max(o, c) * (1 + vol * 0.5 * rnd()), l = min(o, c) * (1 - vol * 0.5 * rnd())
            p = c
            return Bar(t: now - Int64(n - i) * 86400, o: o, h: h, l: l, c: c, v: 1_000_000 * (0.6 + rnd()))
        }
    }

    func testNotEnoughData() {
        let r = AnalysisEngine.analyze(AnalysisInput(bars: series(60, start: 100, drift: 0, vol: 0.01), price: 100, isKr: true, news: [], events: [], nowSec: 1_793_000_000))
        XCTAssertEqual(r.verdict, .na)
    }

    func testUptrendProducesStrategyWithReachableBuy() throws {
        let bars = series(400, start: 50_000, drift: 0.004, vol: 0.012)
        let price = bars.last!.c
        let r = AnalysisEngine.analyze(AnalysisInput(bars: bars, price: price, isKr: true, news: [], events: [], nowSec: 1_793_000_000))
        XCTAssertNotEqual(r.verdict, .na)
        XCTAssertEqual(r.factors.count, 10)
        XCTAssertTrue((0...100).contains(r.score))
        let st = try XCTUnwrap(r.strategy)
        XCTAssertEqual(st.buys.count, 3); XCTAssertEqual(st.targets.count, 3)
        XCTAssertGreaterThanOrEqual(st.buys[0].price, price - 1.3 * r.atr, "1차 매수가는 현재가에서 ATR 1.2배 이내")
        XCTAssertLessThan(st.stop.price, st.buys[2].price)
        XCTAssertGreaterThan(st.targets[0].price, price)
    }

    func testDowntrendIsNotStrongBuy() {
        let bars = series(400, start: 50_000, drift: -0.004, vol: 0.012)
        let r = AnalysisEngine.analyze(AnalysisInput(bars: bars, price: bars.last!.c, isKr: true, news: [], events: [], nowSec: 1_793_000_000))
        XCTAssertNotEqual(r.verdict, .strongBuy)
    }

    func testIndicatorsBasic() {
        XCTAssertEqual(Indicators.sma([1, 2, 3, 4], 2).last, 3.5)
        let rsi = Indicators.rsi((0..<30).map { Double($0) }, 14)
        XCTAssertEqual(rsi.last, 100)
        XCTAssertEqual(Indicators.roundPrice(191_234, kr: true), 191_200)
    }

    func testNewsClassifier() {
        let n = NewsClassifier.classify(NewsItem(title: "삼성전자, 사상 최대 실적 호실적 발표", source: "x", link: "y", publishedAt: 0))
        XCTAssertEqual(n.sentiment, .positive)
        XCTAssertTrue(n.categories.contains("기업 실적"))
        XCTAssertEqual(NewsClassifier.classify(NewsItem(title: "Nvidia shares plunge on probe", source: "x", link: "y", publishedAt: 0)).sentiment, .negative)
    }
}

/// 실제 시세 검증 — CI 에서 LIVE_QUOTE_TEST=1 일 때만 실행. 결과는 LIVE_QUOTE_OUT 파일에 표로 기록
final class LiveQuoteTests: XCTestCase {
    func testLivePrices() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["LIVE_QUOTE_TEST"] == "1", "CI 실시세 검증에서만 실행")
        let targets = [("SNDK", "샌디스크"), ("NVDA", "엔비디아"), ("MU", "마이크론"), ("TSLA", "테슬라"), ("AAPL", "애플"), ("BRK.B", "버크셔B"),
                       ("005930.KS", "삼성전자"), ("000660.KS", "SK하이닉스"), ("035420.KS", "NAVER"), ("035720.KS", "카카오"), ("042700.KS", "한미반도체")]
        let repo = Repository(prefs: Prefs(UserDefaults(suiteName: "live-test")!))
        var md = "검증 시각: \(MarketClock.formatKst(Int64(Date().timeIntervalSince1970))) KST (iOS 코어 · macOS 러너)\n\n| 종목 | 공급원 | 정규장 가격 | 기준 시각 | 상태 | 시간외 | 교차검증 |\n|---|---|---|---|---|---|---|\n"
        var ok = 0
        for (sym, name) in targets {
            do {
                let q = try await repo.quote(StockRef(symbol: sym, name: name))
                ok += 1
                let ext = q.ext.map { "\($0.label) \(Fmt.price($0.price, kr: q.ref.market == .KR)) \($0.active ? "진행중" : "종료")" } ?? "-"
                md += "| \(name) (\(sym)) | \(q.source)\(q.fallback ? " (대체)" : "") | \(Fmt.price(q.price, kr: q.ref.market == .KR)) | \(MarketClock.formatKst(q.marketTime)) | \(q.statusText) | \(ext) | \(q.check?.message ?? "-") |\n"
            } catch {
                md += "| \(name) (\(sym)) | 실패 | \(Http.friendly(error)) | | | | |\n"
            }
        }
        let k: [StockRef]? = await repo.search("한미반")
        md += "\n검색 '한미반' → \(k?.prefix(3).map { "\($0.displayName)(\($0.symbol))" }.joined(separator: ", ") ?? "-")\n"
        print(md)
        if let out = env["LIVE_QUOTE_OUT"] { try md.write(toFile: out, atomically: true, encoding: .utf8) }
        XCTAssertGreaterThan(ok, 0)
    }
}
