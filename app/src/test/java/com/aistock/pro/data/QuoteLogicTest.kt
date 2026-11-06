package com.aistock.pro.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class QuoteLogicTest {

    // 2026-11-06 14:49 KST 실측 응답 (GitHub Actions) — SNDK: 정규장 종가 806.43, 애프터마켓 844.99
    private val sndk = """{"pollingInterval":70000,"datas":[{"reutersCode":"SNDK.O","stockName":"샌디스크","symbolCode":"SNDK",
        "stockExchangeType":{"code":"NSQ","zoneId":"EST5EDT","delayTime":0},"closePrice":"806.43","compareToPreviousClosePrice":"-230.97",
        "compareToPreviousPrice":{"code":"5"},"fluctuationsRatio":"-22.26","openPrice":"962.25","highPrice":"967.99","lowPrice":"803.97",
        "accumulatedTradingVolume":"30,722,648","localTradedAt":"2026-11-05T16:00:00-05:00","marketStatus":"CLOSE",
        "overMarketPriceInfo":{"tradingSessionType":"AFTER_MARKET","overMarketStatus":"CLOSE","overPrice":"844.99","compareToPreviousPrice":{"code":"2"},
        "compareToPreviousClosePrice":"38.56","fluctuationsRatio":"4.78","localTradedAt":"2026-11-05T20:00:00-05:00"},
        "currencyType":{"code":"USD"},"closePriceRaw":"806.43","compareToPreviousClosePriceRaw":"-230.97","fluctuationsRatioRaw":"-22.26"}],"time":"20261106144920"}"""

    private val samsung = """{"pollingInterval":7000,"datas":[{"itemCode":"005930","stockName":"삼성전자",
        "stockExchangeType":{"code":"KS","zoneId":"Asia/Seoul","delayTime":0},"closePrice":"191,100","compareToPreviousClosePrice":"-10,400",
        "compareToPreviousPrice":{"code":"5"},"fluctuationsRatio":"-5.16","openPrice":"180,000","highPrice":"196,500","lowPrice":"179,200",
        "accumulatedTradingVolume":"30,061,960","marketStatus":"OPEN","localTradedAt":"2026-11-06T14:49:14.101783+09:00",
        "overMarketPriceInfo":{"tradingSessionType":"REGULAR_MARKET","overMarketStatus":"OPEN","overPrice":"191,100","compareToPreviousPrice":{"code":"5"},
        "compareToPreviousClosePrice":"-10,400","fluctuationsRatio":"-5.16","localTradedAt":"2026-11-06T14:49:14.101881+09:00"}}]}"""

    @Test fun parsesUsRegularCloseAndAfterMarketSeparately() {
        val s = NaverSource.parse(sndk, kr = false)
        assertEquals(806.43, s.price, 1e-9)
        assertEquals(1037.40, s.prevClose!!, 1e-6)
        assertFalse(s.open_)
        assertEquals(0, s.delayMin)
        assertEquals("America/New_York", s.zone)
        val e = s.ext!!
        assertEquals(Session.POST, e.session)
        assertEquals(844.99, e.price, 1e-9)
        assertEquals(4.78, e.changePct!!, 1e-9)
        assertFalse(e.active)
        // 정규장 종가 = 2026-11-06 06:00 KST
        assertEquals("2026-11-06 06:00", MarketClock.formatKst(s.tradedAt))
    }

    @Test fun parsesKrWithCommasAndNxt() {
        val s = NaverSource.parse(samsung, kr = true)
        assertEquals(191100.0, s.price, 1e-9)
        assertEquals(-10400.0, s.change!!, 1e-9)
        assertEquals(30_061_960.0, s.volume!!, 1e-9)
        assertTrue(s.open_)
        assertEquals(Session.NXT, s.ext!!.session)
    }

    @Test fun freshnessNeverClaimsRealtimeForOldOrUnknownDelay() {
        val now = 1_800_000_000L
        assertEquals(Freshness.REALTIME, QuoteLogic.freshness(true, now - 30, now, 0, true))
        assertEquals(Freshness.DELAYED, QuoteLogic.freshness(true, now - 30, now, null, false))   // Yahoo: 지연 미상
        assertEquals(Freshness.DELAYED, QuoteLogic.freshness(true, now - 30, now, 15, true))
        assertEquals(Freshness.STALE, QuoteLogic.freshness(true, now - 20 * 60, now, 0, true))
        assertEquals(Freshness.CLOSED, QuoteLogic.freshness(false, now - 9 * 3600, now, 0, true))
        assertEquals(Freshness.STALE, QuoteLogic.freshness(false, now - 6 * 86400, now, 0, true))
    }

    @Test fun crossCheckSkipsDifferentTimestampsAndFlagsRealMismatch() {
        val t = 1_800_000_000L
        val skipped = QuoteLogic.crossCheck(100.0, t, true, "Yahoo", 97.0, t - 20 * 60)
        assertFalse(skipped.mismatch)
        assertTrue(skipped.message.contains("비교 생략"))
        val same = QuoteLogic.crossCheck(100.0, t, false, "Yahoo", 100.1, t)
        assertFalse(same.mismatch)
        val bad = QuoteLogic.crossCheck(806.43, t, false, "Yahoo", 850.96, t)
        assertTrue(bad.mismatch)
        assertNotNull(bad.diffPct)
        val none = QuoteLogic.crossCheck(100.0, t, true, "Yahoo", null, null)
        assertFalse(none.mismatch)
    }

    @Test fun alignDailyReplacesSameDayAndAppendsNewDay() {
        val z = ZoneId.of("Asia/Seoul")
        val d1 = 1_793_000_000L
        val bars = listOf(Bar(d1 - 86400, 10.0, 11.0, 9.0, 10.5, 100.0), Bar(d1, 10.5, 11.0, 10.0, 10.8, 100.0))
        val same = QuoteLogic.alignDaily(bars, 12.0, d1 + 3600, z)
        assertEquals(2, same.size); assertEquals(12.0, same.last().c, 0.0); assertEquals(12.0, same.last().h, 0.0)
        val next = QuoteLogic.alignDaily(bars, 9.5, d1 + 86400, z)
        assertEquals(3, next.size); assertEquals(9.5, next.last().c, 0.0)
        val older = QuoteLogic.alignDaily(bars, 9.5, d1 - 3 * 86400, z)
        assertEquals(bars, older)
    }

    @Test fun parsesNaverDaily() {
        val j = """[{"localDate":"20261020","closePrice":209000.0,"openPrice":219500.0,"highPrice":219500.0,"lowPrice":208000.0,"accumulatedTradingVolume":16561224},
                    {"localDate":"20261021","closePrice":223500.0,"openPrice":217000.0,"highPrice":228000.0,"lowPrice":217000.0,"accumulatedTradingVolume":17268762}]"""
        val b = NaverSource.parseDaily(j, ZoneId.of("Asia/Seoul"), java.time.LocalTime.of(9, 0))
        assertEquals(2, b.size); assertEquals(223500.0, b[1].c, 0.0)
    }

    @Test fun nxtAfterMarketIsNotRegularSession() {
        // 2026-11-06 15:58 KST: 네이버 marketStatus=OPEN(NXT 애프터마켓)이지만 KRX 정규장은 15:30 마감
        assertFalse(QuoteLogic.regularOpen(true, Market.KR, 1_793_948_280_000L))
        assertTrue(QuoteLogic.regularOpen(true, Market.KR, 1_793_940_000_000L))    // 13:40 KST
        assertFalse(QuoteLogic.regularOpen(false, Market.KR, 1_793_940_000_000L))  // 휴장 등
    }
}
