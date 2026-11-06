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

data class Quote(
    val ref: StockRef,
    val currency: String,
    val price: Double,
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
    val marketTime: Long,
    val timezone: String,
    val marketOpen: Boolean,
    val statusText: String,        // "장중 · 지연 시세" / "장 마감 · 최근 종가" 등
)

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
