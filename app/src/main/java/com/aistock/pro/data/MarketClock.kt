package com.aistock.pro.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 장 운영 시간 판단 + 시각 표시 (공휴일은 반영하지 않음 → 화면에 안내) */
object MarketClock {
    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    private val NY: ZoneId = ZoneId.of("America/New_York")

    fun isOpen(market: Market, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val zone = if (market == Market.KR) SEOUL else NY
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return false
        val t = now.toLocalTime()
        return if (market == Market.KR) t >= LocalTime.of(9, 0) && t < LocalTime.of(15, 30)
        else t >= LocalTime.of(9, 30) && t < LocalTime.of(16, 0)
    }

    /**
     * 시세 상태 문구. 장중인데 마지막 체결이 40분 이상 오래되었으면 "갱신 지연" 경고.
     * Yahoo 무료 시세는 한국 약 20분, 미국 거래소 체결 기준 실시간~수분 지연이 있을 수 있음.
     */
    fun status(market: Market, marketTime: Long, nowMillis: Long = System.currentTimeMillis()): Pair<Boolean, String> {
        val open = isOpen(market, nowMillis)
        val ageMin = (nowMillis / 1000 - marketTime) / 60
        val text = when {
            open && ageMin > 40 -> "장중 · ⚠ 시세 갱신 지연(${ageMin}분 전 체결가)"
            open -> if (market == Market.KR) "장중 · 지연 시세(약 20분)" else "장중 · 무료 시세(지연 가능)"
            else -> "장 마감 · 최근 체결가 기준"
        }
        return open to text
    }

    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 한국 시간 + (미국이면) 현지 시간 함께 표시 */
    fun format(epochSec: Long, market: Market): String {
        val inst = Instant.ofEpochSecond(epochSec)
        val kst = fmt.format(inst.atZone(SEOUL))
        return if (market == Market.US) "$kst (한국) / ${fmt.format(inst.atZone(NY))} (뉴욕)" else "$kst (한국)"
    }

    fun formatKst(epochSec: Long): String = fmt.format(Instant.ofEpochSecond(epochSec).atZone(SEOUL))

    fun relative(epochSec: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val diff = nowMillis / 1000 - epochSec
        return when {
            diff < 60 -> "방금"
            diff < 3600 -> "${diff / 60}분 전"
            diff < 86400 -> "${diff / 3600}시간 전"
            else -> "${diff / 86400}일 전"
        }
    }
}
