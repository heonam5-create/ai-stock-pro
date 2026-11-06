package com.aistock.pro.data

// ※ 이 파일은 안드로이드 의존성이 없는 순수 Kotlin 입니다 (단위 테스트에서 그대로 사용).

enum class Market { KR, US }

/** 종목 식별 정보. symbol 은 Yahoo 형식 (005930.KS, 247540.KQ, NVDA) */
data class StockRef(
    val symbol: String,
    val name: String,
    val nameKr: String? = null,
    val exchange: String = "",
    val market: Market = if (symbol.endsWith(".KS") || symbol.endsWith(".KQ")) Market.KR else Market.US,
) {
    val displayName: String get() = nameKr ?: name
    val code: String get() = symbol.substringBefore(".")
}

/** 캔들 1개. t = epoch 초 */
data class Bar(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

enum class Interval(val label: String, val yahoo: String, val range: String, val intraday: Boolean) {
    M1("1분", "1m", "5d", true),
    M5("5분", "5m", "1mo", true),
    M15("15분", "15m", "1mo", true),
    M30("30분", "30m", "1mo", true),
    H1("1시간", "60m", "3mo", true),
    D1("일봉", "1d", "2y", false),
    W1("주봉", "1wk", "10y", false),
    MO1("월봉", "1mo", "max", false),
}

data class ChartMeta(
    val symbol: String,
    val currency: String,
    val exchange: String,
    val longName: String?,
    val price: Double?,
    val marketTime: Long,          // epoch 초 (마지막 체결 시각)
    val timezone: String,          // 예: Asia/Seoul, America/New_York
    val high52: Double?,
    val low52: Double?,
    val dayHigh: Double?,
    val dayLow: Double?,
    val volume: Double?,
    val instrumentType: String?,
)

data class ChartData(val meta: ChartMeta, val bars: List<Bar>, val interval: Interval, val fetchedAt: Long)

/** 거래 세션 */
enum class Session(val label: String) {
    PRE("프리마켓"), REGULAR("정규장"), POST("애프터마켓"), NXT("NXT 대체거래소"), CLOSED("장 마감"), UNKNOWN("세션 확인 불가")
}

/** 시세 신선도 — 화면에 그대로 표시. 실시간이 아닌 값을 실시간으로 표시하지 않기 위한 단일 기준 */
enum class Freshness(val label: String) {
    REALTIME("실시간"), DELAYED("지연 시세"), CLOSED("장 마감"), STALE("⚠ 시세 지연"), ERROR("⚠ 데이터 오류")
}

/** 정규장 외 가격 (미국 프리·애프터마켓, 한국 NXT) — 정규장 가격과 섞지 않고 별도로 표시 */
data class ExtPrice(
    val session: Session,
    val label: String,             // 예: "애프터마켓", "NXT 애프터마켓"
    val price: Double,
    val change: Double?,           // 정규장 기준가 대비
    val changePct: Double?,
    val time: Long,                // epoch 초
    val active: Boolean,           // 해당 세션이 현재 진행 중인지
)

/** 복수 공급원 교차검증 결과 */
data class CrossCheck(
    val source: String,
    val price: Double?,
    val time: Long?,
    val diffPct: Double?,
    val mismatch: Boolean,
    val message: String,
)

data class Quote(
    val ref: StockRef,
    val currency: String,
    val price: Double,             // 정규장 가격 (장중=현재가, 장 마감=정규장 종가). 분석·손익·알림의 단일 기준
    val prevClose: Double?,
    val change: Double?,
    val changePct: Double?,
    val volume: Double?,
    val dayHigh: Double?,
    val dayLow: Double?,
    val high52: Double?,
    val low52: Double?,
    val marketCap: Double?,        // 통화 단위 그대로 (원 / 달러)
    val marketCapSource: String?,
    val marketTime: Long,          // price 의 체결(기준) 시각, epoch 초
    val timezone: String,
    val marketOpen: Boolean,       // 정규장 진행 중
    val statusText: String,        // 예: "실시간 · 정규장", "장 마감 · 정규장 종가"
    val session: Session = if (marketOpen) Session.REGULAR else Session.CLOSED,
    val freshness: Freshness = Freshness.DELAYED,
    val source: String = "Yahoo Finance",
    val delayText: String = "",    // 예: "실시간(지연 0분)", "최대 20분 지연 가능"
    val ext: ExtPrice? = null,
    val check: CrossCheck? = null,
    val fetchedAt: Long = System.currentTimeMillis(),   // 앱이 마지막으로 받아온 시각(ms)
    val warning: String? = null,   // 시세 신뢰도 경고 (있으면 화면 상단에 표시)
) {
    /** 매수·매도 판단에 쓰면 안 되는 상태 */
    val unreliable: Boolean get() = freshness == Freshness.STALE || freshness == Freshness.ERROR || check?.mismatch == true
}

enum class Sentiment(val label: String) { POSITIVE("호재"), NEGATIVE("악재"), NEUTRAL("중립") }

data class NewsItem(
    val title: String,
    val source: String,
    val link: String,
    val publishedAt: Long,          // epoch 초
    val relatedSymbol: String?,
    val relatedName: String?,
    val sentiment: Sentiment = Sentiment.NEUTRAL,
    val categories: List<String> = emptyList(),
)

data class EventItem(val date: String, val title: String, val detail: String, val daysLeft: Long)

sealed class UiState<out T> {
    data object Idle : UiState<Nothing>()
    data object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

/** 사용자에게 그대로 보여줄 수 있는 한국어 오류 */
class DataException(message: String) : Exception(message)
