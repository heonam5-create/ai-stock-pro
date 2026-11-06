package com.aistock.pro.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 주요 경제 일정 (무료 경제캘린더 API가 없어 공식 발표 일정을 내장).
 * FOMC: 미 연준(federalreserve.gov) 공표 일정, 금리 결정은 둘째 날 오후 2시(뉴욕) = 한국 다음날 새벽 3~4시.
 * 2028년 이후 일정은 앱 업데이트 시 추가하세요 (docs/05_유지보수_방법.md).
 */
object Events {
    private val fomcDecisionDays = listOf(
        "2026-01-28", "2026-03-18", "2026-04-29", "2026-06-17", "2026-07-29", "2026-09-16", "2026-10-28", "2026-12-09",
        "2027-01-27", "2027-03-17", "2027-04-28", "2027-06-09", "2027-07-28", "2027-09-15", "2027-10-27", "2027-12-08",
    ).map { LocalDate.parse(it) }

    fun upcomingMacro(today: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul")), limit: Int = 3): List<EventItem> {
        val out = fomcDecisionDays.filter { !it.isBefore(today.minusDays(1)) }.take(limit).map { d ->
            EventItem(d.toString(), "FOMC 금리 결정", "뉴욕 오후 2시 발표 (한국 다음날 새벽) · 결과에 따라 변동성 확대 가능",
                ChronoUnit.DAYS.between(today, d))
        }
        return if (out.isEmpty()) listOf(EventItem("-", "FOMC 일정 갱신 필요", "내장 일정이 끝났습니다. 앱 업데이트가 필요합니다.", 999)) else out
    }
}
