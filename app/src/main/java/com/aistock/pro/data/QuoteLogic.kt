package com.aistock.pro.data

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

// ※ 안드로이드 의존성 없는 순수 Kotlin — 단위 테스트 대상.

/** 시세 신선도·교차검증·일봉 정렬 규칙 */
object QuoteLogic {

    /**
     * 신선도 판정.
     * - 정규장 진행 중: 체결 기준 시각이 (공급원 지연 + 허용치)보다 오래되면 STALE.
     *   공급원이 지연 0분을 명시한 경우에만 REALTIME, 아니면 DELAYED.
     * - 장 마감: 마지막 체결이 5일 이상 지났으면 STALE(거래정지·데이터 중단 의심), 아니면 CLOSED.
     */
    fun freshness(open: Boolean, tradedAt: Long, nowSec: Long, delayMin: Int?, sourceRealtime: Boolean): Freshness {
        val age = nowSec - tradedAt
        return if (open) {
            val allowed = ((delayMin ?: 20) + 15) * 60L
            when {
                age > allowed -> Freshness.STALE
                sourceRealtime && (delayMin ?: 1) == 0 -> Freshness.REALTIME
                else -> Freshness.DELAYED
            }
        } else {
            if (age > 5 * 86400L) Freshness.STALE else Freshness.CLOSED
        }
    }

    fun delayText(source: String, delayMin: Int?, kr: Boolean): String = when {
        delayMin == 0 -> "$source · 지연 0분(공급원 표기 기준)"
        delayMin != null -> "$source · 약 ${delayMin}분 지연"
        else -> "$source · " + if (kr) "최대 20분 지연 가능" else "수 분~15분 지연 가능"
    }

    fun statusText(f: Freshness, session: Session, open: Boolean, ageMin: Long): String = when (f) {
        Freshness.REALTIME -> "실시간 · ${session.label}"
        Freshness.DELAYED -> "지연 시세 · ${session.label}"
        Freshness.CLOSED -> "장 마감 · 정규장 종가"
        Freshness.STALE -> if (open) "⚠ 시세 지연 (${ageMin}분 전 체결가)" else "⚠ 오래된 데이터 (${ageMin / 1440}일 전)"
        Freshness.ERROR -> "⚠ 데이터 오류 · 마지막 성공 데이터"
    }

    /**
     * 두 공급원의 정규장 가격 비교.
     * 기준 시각 차이가 [maxTimeGapSec] 를 넘으면(한쪽이 지연 시세 등) 가격이 달라도 불일치로 판단하지 않고 '비교 생략'.
     * 가격이 다르다고 높은/낮은 쪽을 고르지 않는다 — 기본 공급원 값을 유지하고 경고만 낸다.
     */
    fun crossCheck(
        primaryPrice: Double, primaryTime: Long, primaryOpen: Boolean,
        source: String, otherPrice: Double?, otherTime: Long?,
        maxTimeGapSec: Long = 300,
    ): CrossCheck {
        if (otherPrice == null || otherPrice <= 0 || otherTime == null || otherTime <= 0)
            return CrossCheck(source, otherPrice, otherTime, null, false, "$source 응답 없음 — 교차검증 불가")
        val gap = abs(primaryTime - otherTime)
        val diff = (otherPrice / primaryPrice - 1) * 100
        // 장 마감 후에는 양쪽 모두 같은 종가여야 하므로 기준 시각 차이를 넓게 허용 (집계 시각 표기 차이)
        val gapOk = if (primaryOpen) gap <= maxTimeGapSec else gap <= 6 * 3600
        if (!gapOk) {
            return CrossCheck(source, otherPrice, otherTime, diff, false,
                "$source 기준 시각이 ${gap / 60}분 달라 비교 생략 (지연 시세 차이)")
        }
        val limit = if (primaryOpen) 1.0 else 0.3
        return if (abs(diff) > limit)
            CrossCheck(source, otherPrice, otherTime, diff, true,
                "⚠ 시세 불일치: $source ${"%.2f".format(otherPrice)} (${"%+.2f".format(diff)}%) — 원인 확인 전까지 매매 판단 보류")
        else
            CrossCheck(source, otherPrice, otherTime, diff, false, "$source 와 일치 (차이 ${"%+.2f".format(diff)}%)")
    }

    /**
     * 일봉 마지막 봉을 시세 스냅샷과 일치시킨다 — 분석이 서로 다른 시각의 가격을 섞지 않도록.
     * - 같은 거래일: 종가를 스냅샷 가격으로, 고가/저가를 확장
     * - 스냅샷이 더 최근 거래일: 새 봉 추가
     * - 스냅샷이 더 과거(일봉이 더 최신): 그대로 둔다
     */
    fun alignDaily(bars: List<Bar>, price: Double, time: Long, zone: ZoneId,
                   open: Double? = null, high: Double? = null, low: Double? = null, volume: Double? = null): List<Bar> {
        if (bars.isEmpty() || price <= 0 || time <= 0) return bars
        fun day(t: Long) = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
        val last = bars.last()
        val qd = day(time); val ld = day(last.t)
        return when {
            qd == ld -> bars.dropLast(1) + last.copy(
                c = price,
                h = maxOf(last.h, price, high ?: price),
                l = minOf(last.l, price, low ?: price),
                v = volume?.takeIf { it > 0 } ?: last.v,
            )
            qd.isAfter(ld) -> {
                val o = open?.takeIf { it > 0 } ?: price
                bars + Bar(time, o, maxOf(o, price, high ?: price), minOf(o, price, low ?: price), price, volume ?: 0.0)
            }
            else -> bars
        }
    }

    /** 자동 새로고침 주기(ms): 장중/시간외 진행 중이면 짧게, 마감이면 길게. 공급원 권장 주기보다 짧게 하지 않음 */
    fun refreshMs(q: Quote): Long {
        val live = q.marketOpen || q.ext?.active == true
        val base = if (!live) 60_000L else if (q.ref.market == Market.KR) 10_000L else 15_000L
        return base
    }
}
