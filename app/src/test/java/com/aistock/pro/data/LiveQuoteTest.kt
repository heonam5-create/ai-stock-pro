package com.aistock.pro.data

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 실제 시세 검증 (네트워크 사용). CI 에서 LIVE_QUOTE_TEST=1 일 때만 실행되며 결과를 표로 남긴다.
 * 기본 공급원(네이버)과 보조 공급원(Yahoo)의 가격·기준 시각·세션을 같은 규칙(QuoteLogic)으로 비교한다.
 */
class LiveQuoteTest {
    private val targets = listOf(
        StockRef("SNDK", "샌디스크"), StockRef("NVDA", "엔비디아"), StockRef("MU", "마이크론"),
        StockRef("TSLA", "테슬라"), StockRef("AAPL", "애플"),
        StockRef("005930.KS", "삼성전자"), StockRef("000660.KS", "SK하이닉스"),
        StockRef("035420.KS", "NAVER"), StockRef("035720.KS", "카카오"),
    )

    @Test fun livePrices() = runBlocking {
        assumeTrue(System.getenv("LIVE_QUOTE_TEST") == "1")
        val now = System.currentTimeMillis() / 1000
        val sb = StringBuilder()
        sb.append("검증 시각: ${MarketClock.formatKst(now)} KST\n\n")
        sb.append("| 종목 | 기본(네이버) 정규장 가격 | 체결 기준 시각 | 상태 | 시간외 | 보조(Yahoo) | 교차검증 |\n|---|---|---|---|---|---|---|\n")
        var okCount = 0
        for (r in targets) {
            val nv = runCatching { NaverSource.quote(r) }
            val y = runCatching { YahooSource.chart(r.symbol, Interval.D1) }
            val s = nv.getOrNull()
            val yp = y.getOrNull()?.meta
            if (s == null) {
                sb.append("| ${r.name} (${r.symbol}) | 실패: ${nv.exceptionOrNull()?.message?.take(60)} | | | | ${yp?.price ?: "실패"} | |\n")
                continue
            }
            okCount++
            val fr = QuoteLogic.freshness(s.open_, s.tradedAt, now, s.delayMin, true)
            val st = QuoteLogic.statusText(fr, if (s.open_) Session.REGULAR else Session.CLOSED, s.open_, (now - s.tradedAt) / 60)
            val ext = s.ext?.let { "${it.label} ${it.price} (${it.changePct}%) ${MarketClock.formatKst(it.time)}${if (it.active) " 진행중" else " 종료"}" } ?: "-"
            val chk = QuoteLogic.crossCheck(s.price, s.tradedAt, s.open_, "Yahoo", yp?.price, yp?.marketTime?.takeIf { it > 0 })
            val ytxt = if (yp != null) "${yp.price} @ ${MarketClock.formatKst(yp.marketTime)}" else "실패: ${y.exceptionOrNull()?.message?.take(40)}"
            sb.append("| ${r.name} (${r.symbol}) | ${s.price} | ${MarketClock.formatKst(s.tradedAt)} KST | $st | $ext | $ytxt | ${chk.message} |\n")
        }
        val out = System.getenv("LIVE_QUOTE_OUT")
        if (out != null) File(out).writeText(sb.toString())
        println(sb)
        check(okCount > 0) { "기본 공급원에서 한 종목도 시세를 받지 못했습니다" }
    }
}
