package com.aistock.pro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistock.pro.analysis.AnalysisResult
import com.aistock.pro.analysis.Fmt
import com.aistock.pro.analysis.PriceLevel
import com.aistock.pro.analysis.Strategy
import com.aistock.pro.data.AlertType
import com.aistock.pro.data.Interval
import com.aistock.pro.data.Market
import com.aistock.pro.data.MarketClock
import com.aistock.pro.data.NewsItem
import com.aistock.pro.data.Quote
import com.aistock.pro.data.Sentiment
import com.aistock.pro.data.StockRef
import com.aistock.pro.data.UiState
import com.aistock.pro.ui.MainViewModel
import com.aistock.pro.ui.components.AppHeader
import com.aistock.pro.ui.components.Badge
import com.aistock.pro.ui.components.ChipRow
import com.aistock.pro.ui.components.Disclaimer
import com.aistock.pro.ui.components.EmptyText
import com.aistock.pro.ui.components.ErrorBox
import com.aistock.pro.ui.components.KeyValue
import com.aistock.pro.ui.components.LoadingBox
import com.aistock.pro.ui.components.MA_COLORS
import com.aistock.pro.ui.components.PrimaryButton
import com.aistock.pro.ui.components.SearchField
import com.aistock.pro.ui.components.SearchResults
import com.aistock.pro.ui.components.SectionCard
import com.aistock.pro.ui.components.SelectChip
import com.aistock.pro.ui.components.StockChart
import com.aistock.pro.ui.components.SubPanel
import com.aistock.pro.ui.theme.AppColors
import com.aistock.pro.ui.theme.changeColor
import com.aistock.pro.ui.theme.scoreColor
import com.aistock.pro.ui.theme.verdictColor
import java.time.ZoneId

@Composable
fun AnalysisScreen(vm: MainViewModel) {
    val ref = vm.selected
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader(ref?.let { "${it.displayName} · ${it.symbol}" } ?: "종목 분석") {
            if (ref != null) {
                IconButton(onClick = { vm.toggleWatch(ref) }) {
                    val w = vm.isWatched(ref.symbol)
                    Icon(if (w) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (w) "관심 해제" else "관심 등록", tint = if (w) AppColors.Up else AppColors.Sub)
                }
                IconButton(onClick = { vm.loadSelected() }) { Icon(Icons.Filled.Refresh, "새로고침", tint = AppColors.Sub) }
            }
        }
        SearchField(vm.query, vm::onQuery)
        SearchResults(vm.searchState, vm::open) { vm.onQuery(vm.query) }

        if (ref == null) {
            EmptyText("   위 검색창에 종목명(한글/영문), 6자리 코드, 티커를 입력하세요.")
            Disclaimer()
        } else {
            AnalysisBody(vm, ref)
        }
    }
}

@Composable
private fun AnalysisBody(vm: MainViewModel, ref: StockRef) {
        val kr = ref.market == Market.KR

        // ── 시세 ──
        SectionCard("현재가") {
            when (val st = vm.quoteState) {
                is UiState.Idle, is UiState.Loading -> LoadingBox("시세 불러오는 중…")
                is UiState.Error -> ErrorBox(st.message) { vm.refreshQuote() }
                is UiState.Success -> QuoteBlock(st.data)
            }
        }

        // ── 차트 ──
        SectionCard("차트") {
            ChipRow {
                Interval.entries.forEach { itv ->
                    val reason = vm.unsupported[itv]
                    SelectChip(itv.label, vm.interval == itv, enabled = reason == null) { vm.setInterval(itv) }
                }
            }
            vm.unsupported.values.firstOrNull()?.let { Text("※ $it (비활성화됨)", color = AppColors.Orange, fontSize = 11.sp) }
            Spacer(Modifier.height(6.dp))
            ChipRow {
                listOf(5, 10, 20, 60, 120, 200).forEach { m ->
                    SelectChip("MA$m", m in vm.overlays.ma) {
                        vm.overlays = vm.overlays.copy(ma = if (m in vm.overlays.ma) vm.overlays.ma - m else vm.overlays.ma + m)
                    }
                }
                SelectChip("볼린저", vm.overlays.bb) { vm.overlays = vm.overlays.copy(bb = !vm.overlays.bb) }
                SelectChip("VWAP", vm.overlays.vwap) { vm.overlays = vm.overlays.copy(vwap = !vm.overlays.vwap) }
                SelectChip("추세선", vm.overlays.trend) { vm.overlays = vm.overlays.copy(trend = !vm.overlays.trend) }
                SelectChip("지지/저항", vm.overlays.levels) { vm.overlays = vm.overlays.copy(levels = !vm.overlays.levels) }
                SelectChip("매매구간", vm.overlays.zones) { vm.overlays = vm.overlays.copy(zones = !vm.overlays.zones) }
            }
            Spacer(Modifier.height(4.dp))
            ChipRow { SubPanel.entries.forEach { sp -> SelectChip(sp.label, vm.subPanel == sp) { vm.subPanel = sp } } }
            Spacer(Modifier.height(8.dp))
            when (val st = vm.chartState) {
                is UiState.Idle, is UiState.Loading -> Box(Modifier.fillMaxWidth().height(380.dp), contentAlignment = Alignment.Center) { LoadingBox("차트 불러오는 중…") }
                is UiState.Error -> ErrorBox(st.message) { vm.retryChart() }
                is UiState.Success -> {
                    val cd = st.data
                    val zone = runCatching { ZoneId.of(cd.meta.timezone) }.getOrDefault(ZoneId.of("Asia/Seoul"))
                    StockChart(cd.bars, cd.interval.intraday, zone, kr, vm.overlays, vm.subPanel, vm.chartLevels())
                    Text(
                        "두 손가락으로 확대·축소, 한 손가락 좌우로 이동, 탭하면 해당 봉 정보. " +
                            (if (vm.chartLevels() == null) "AI 분석 후 지지(청록)/저항(주황)/손절(빨강 점선)·매수구간(붉은 띠)·매도구간(푸른 띠)이 표시됩니다." else "청록=지지, 주황=저항, 빨강 점선=손절, 붉은 띠=매수구간, 푸른 띠=매도구간."),
                        color = AppColors.Sub, fontSize = 11.sp, lineHeight = 15.sp,
                    )
                    Row { vm.overlays.ma.sorted().forEach { m -> Text("MA$m ", color = MA_COLORS[m] ?: Color.White, fontSize = 11.sp) } }
                    Text("데이터: ${cd.bars.size}개 봉 · 주식분할 반영 가격 · 마지막 봉 ${MarketClock.format(cd.bars.last().t, ref.market)}", color = AppColors.Sub, fontSize = 11.sp)
                }
            }
        }

        // ── AI 분석 ──
        Spacer(Modifier.height(6.dp))
        val loading = vm.analysisState is UiState.Loading
        Box(Modifier.padding(horizontal = 12.dp)) {
            PrimaryButton(if (loading) "AI 종합 분석 중…" else "AI 종합 분석", enabled = !loading, color = AppColors.Up) { vm.runAnalysis() }
        }
        when (val st = vm.analysisState) {
            is UiState.Idle -> Text("   버튼을 누르면 일봉 2년 데이터·뉴스·일정을 종합해 10개 항목을 분석합니다.", color = AppColors.Sub, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            is UiState.Loading -> LoadingBox("시세·뉴스 수집 및 분석 중… (5~15초)")
            is UiState.Error -> ErrorBox(st.message) { vm.runAnalysis() }
            is UiState.Success -> {
                val fa = st.data
                VerdictCard(fa.result, fa.quote)
                FactorsCard(fa.result)
                fa.result.strategy?.let { StrategyCard(it, fa.result, kr) }
                LevelsCard(fa.result, kr)
                AlertShortcuts(vm, fa.result, fa.quote)
                ExternalAiCard(vm)
                if (fa.newsError != null) Text("   뉴스 조회 실패: ${fa.newsError}", color = AppColors.Orange, fontSize = 12.sp)
                SectionCard("주요 일정") {
                    if (fa.events.isEmpty()) EmptyText("확인된 일정이 없습니다.")
                    fa.events.take(5).forEach { e -> KeyValue("${e.date} (D-${e.daysLeft})", "${e.title} · ${e.detail}") }
                    if (kr) Text("한국 기업 실적 발표일은 무료 API가 없어 표시하지 않습니다 (DART 전자공시 확인).", color = AppColors.Sub, fontSize = 11.sp)
                    else if (!vm.prefs.finnhubKey.isNotBlank()) Text("설정에서 무료 Finnhub 키를 등록하면 미국 실적 발표일이 표시됩니다.", color = AppColors.Sub, fontSize = 11.sp)
                }
            }
        }

        NewsCard(vm)
        Disclaimer()
        Spacer(Modifier.height(12.dp))
}

@Composable
private fun QuoteBlock(q: Quote) {
    val kr = q.ref.market == Market.KR
    Row(verticalAlignment = Alignment.Bottom) {
        Text(Fmt.price(q.price, kr), color = changeColor(q.changePct), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text("${q.change?.let { (if (it > 0) "▲" else if (it < 0) "▼" else "") + Fmt.num(kotlin.math.abs(it), kr) } ?: "-"}  ${Fmt.pct(q.changePct)}",
            color = changeColor(q.changePct), fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Badge(q.statusText, if (q.statusText.contains("⚠")) AppColors.Orange else if (q.marketOpen) AppColors.Green else AppColors.Sub)
    }
    Spacer(Modifier.height(6.dp))
    KeyValue("종목명", q.ref.displayName + if (q.ref.nameKr != null && q.ref.name != q.ref.nameKr) " (${q.ref.name})" else "")
    KeyValue("티커 · 거래소", "${q.ref.symbol} · ${q.ref.exchange.ifBlank { "-" }}")
    KeyValue("거래량", Fmt.volume(q.volume, kr))
    KeyValue("시가총액", q.marketCap?.let { Fmt.marketCap(it, kr) + " (${q.marketCapSource})" } ?: "미제공 (설정에서 무료 키 등록 시 표시)")
    KeyValue("당일 고가 / 저가", "${Fmt.price(q.dayHigh, kr)} / ${Fmt.price(q.dayLow, kr)}")
    KeyValue("52주 최고 / 최저", "${Fmt.price(q.high52, kr)} / ${Fmt.price(q.low52, kr)}")
    KeyValue("전일 종가", Fmt.price(q.prevClose, kr))
    KeyValue("데이터 기준 시각", MarketClock.format(q.marketTime, q.ref.market))
    Text("공휴일은 장 운영 판단에 반영되지 않습니다. 시세 출처: Yahoo Finance (무료·지연)", color = AppColors.Sub, fontSize = 11.sp)
}

@Composable
private fun VerdictCard(r: AnalysisResult, q: Quote) {
    SectionCard("AI 종합 판단") {
        val c = verdictColor(r.verdict)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(c.copy(alpha = 0.18f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(r.verdict.label, color = c, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.width(12.dp))
            if (r.verdict != com.aistock.pro.analysis.Verdict.NA) Column(Modifier.weight(1f)) {
                Text("종합 점수 ${r.score} / 100", color = AppColors.Text, fontSize = 14.sp)
                LinearProgressIndicator(progress = { r.score / 100f }, color = c, trackColor = AppColors.Card2, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(r.summary, color = AppColors.Text, fontSize = 14.sp, lineHeight = 20.sp)
        if (r.reasons.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("판단 근거", color = AppColors.Up, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            r.reasons.forEach { Text("• $it", color = AppColors.Text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        if (r.risks.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("위험 요인", color = AppColors.Down, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            r.risks.forEach { Text("• $it", color = AppColors.Text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
        r.warnings.forEach { Text("⚠ $it", color = AppColors.Orange, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        Text("분석 기준: 일봉 · 현재가 ${Fmt.price(q.price, q.ref.market == Market.KR)} · ${MarketClock.format(q.marketTime, q.ref.market)}",
            color = AppColors.Sub, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun FactorsCard(r: AnalysisResult) {
    if (r.factors.isEmpty()) return
    SectionCard("10개 항목 상세 분석") {
        r.factors.forEach { f ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${f.no}. ${f.title}", color = AppColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Badge(f.status, if (f.available) scoreColor(f.score) else AppColors.Sub)
                }
                Text(f.detail, color = AppColors.Sub, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun LevelRow(lv: PriceLevel, kr: Boolean, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(lv.label, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
            Text(Fmt.price(lv.price, kr), color = AppColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(Fmt.pct(lv.diffPct), color = changeColor(lv.diffPct), fontSize = 13.sp)
        }
        Text("근거: ${lv.basis}", color = AppColors.Sub, fontSize = 12.sp, lineHeight = 16.sp)
        Text("대응: ${lv.action}", color = AppColors.Text, fontSize = 12.sp, lineHeight = 16.sp)
        if (lv.extra.isNotBlank()) Text(lv.extra, color = AppColors.Sub, fontSize = 11.sp)
    }
}

@Composable
private fun StrategyCard(s: Strategy, r: AnalysisResult, kr: Boolean) {
    SectionCard("매수·매도 전략 (자동 계산)") {
        Text("현재가 대비 차이(%)는 오른쪽에 표시. 매수가는 실제 지지 근거가 있는 가격 중 현재가에서 가까운 순으로 선정합니다.", color = AppColors.Sub, fontSize = 11.sp)
        Text("매수 전략", color = AppColors.Up, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
        s.buys.forEach { LevelRow(it, kr, AppColors.Up) }
        Box(Modifier.fillMaxWidth().background(Color(0x33E11D48), RoundedCornerShape(8.dp)).padding(10.dp)) {
            Text("매수 금지 가격대: ${s.noBuyText}", color = AppColors.Text, fontSize = 13.sp)
        }
        Text("매도 전략", color = AppColors.Down, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
        s.targets.forEach { LevelRow(it, kr, AppColors.Down) }
        LevelRow(s.stop, kr, Color(0xFFE11D48))
        LevelRow(s.trendBreak, kr, AppColors.Purple)
        Text(
            "손익비(계획 평균단가→2차 목표가 / 손절까지): ${s.riskReward?.let { "%.2f : 1".format(it) } ?: "-"} · ATR ${Fmt.price(r.atr, kr)} (${"%.2f".format(r.atrPct)}%)",
            color = AppColors.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Text("※ 제시 가격은 과거 데이터 기반 계산값이며 체결·수익을 보장하지 않습니다.", color = AppColors.Sub, fontSize = 11.sp)
    }
}

@Composable
private fun LevelsCard(r: AnalysisResult, kr: Boolean) {
    if (r.supports.isEmpty() && r.resistances.isEmpty()) return
    SectionCard("지지선 · 저항선") {
        Text("저항선 (위)", color = AppColors.Orange, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (r.resistances.isEmpty()) EmptyText("가까운 저항선 없음 (신고가 영역)")
        r.resistances.forEach { KeyValue(Fmt.price(it.price, kr) + "  " + Fmt.pct((it.price / r.price - 1) * 100), it.basis) }
        Spacer(Modifier.height(6.dp))
        Text("지지선 (아래)", color = AppColors.Teal, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (r.supports.isEmpty()) EmptyText("가까운 지지선 없음")
        r.supports.forEach { KeyValue(Fmt.price(it.price, kr) + "  " + Fmt.pct((it.price / r.price - 1) * 100), it.basis) }
    }
}

@Composable
private fun AlertShortcuts(vm: MainViewModel, r: AnalysisResult, q: Quote) {
    val s = r.strategy ?: return
    var done by remember(r) { mutableStateOf(setOf<String>()) }
    SectionCard("이 가격으로 알림 설정") {
        val items = listOfNotNull(
            Triple("1차 매수가", AlertType.BUY_PRICE, s.buys.first().price),
            Triple("1차 목표가", AlertType.SELL_PRICE, s.targets.first().price),
            Triple("손절가", AlertType.STOP_LOSS, s.stop.price),
            r.supports.firstOrNull()?.let { Triple("지지선 이탈", AlertType.SUPPORT_BREAK, it.price) },
            r.resistances.firstOrNull()?.let { Triple("저항선 돌파", AlertType.RESIST_BREAK, it.price) },
        )
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, type, price) ->
                    val added = label in done
                    OutlinedButton(
                        onClick = { if (!added) { vm.addAlert(q.ref.symbol, q.ref.displayName, type, price); done = done + label } },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Notifications, null, modifier = Modifier.padding(end = 4.dp))
                        Text(if (added) "$label ✓" else label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
        }
        Text("등록한 알림은 '관심 종목 > 알림' 탭에서 관리합니다.", color = AppColors.Sub, fontSize = 11.sp)
    }
}

@Composable
private fun ExternalAiCard(vm: MainViewModel) {
    SectionCard("외부 AI 의견 (선택 기능)") {
        if (!vm.externalAiConfigured) {
            Text("기본 분석은 휴대폰 안의 무료 로컬 엔진이 수행합니다. 설정에서 외부 AI(OpenAI 호환) 주소·모델·키를 입력하면 이 결과에 대한 서술형 의견을 추가로 받을 수 있습니다 (사용 요금은 해당 서비스 기준).",
                color = AppColors.Sub, fontSize = 12.sp, lineHeight = 17.sp)
        } else when (val st = vm.aiState) {
            is UiState.Idle -> OutlinedButton(onClick = { vm.runExternalAi() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("외부 AI 의견 받기") }
            is UiState.Loading -> LoadingBox("외부 AI 응답 대기 중…")
            is UiState.Error -> ErrorBox(st.message) { vm.runExternalAi() }
            is UiState.Success -> Text(st.data, color = AppColors.Text, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun NewsCard(vm: MainViewModel) {
    var filter by remember { mutableStateOf<Sentiment?>(null) }
    SectionCard("호재·악재 뉴스 (최근 7일)", action = {
        IconButton(onClick = { vm.refreshNews() }) { Icon(Icons.Filled.Refresh, "뉴스 새로고침", tint = AppColors.Sub) }
    }) {
        ChipRow {
            SelectChip("전체", filter == null) { filter = null }
            SelectChip("호재", filter == Sentiment.POSITIVE) { filter = Sentiment.POSITIVE }
            SelectChip("악재", filter == Sentiment.NEGATIVE) { filter = Sentiment.NEGATIVE }
            SelectChip("중립", filter == Sentiment.NEUTRAL) { filter = Sentiment.NEUTRAL }
        }
        when (val st = vm.newsState) {
            is UiState.Idle, is UiState.Loading -> LoadingBox("뉴스 불러오는 중…")
            is UiState.Error -> ErrorBox(st.message) { vm.refreshNews() }
            is UiState.Success -> {
                val list: List<NewsItem> = st.data.filter { filter == null || it.sentiment == filter }
                if (st.data.isEmpty()) EmptyText("최근 7일 내 관련 뉴스가 없습니다.")
                else if (list.isEmpty()) EmptyText("해당 분류의 뉴스가 없습니다.")
                else {
                    val pos = st.data.count { it.sentiment == Sentiment.POSITIVE }; val neg = st.data.count { it.sentiment == Sentiment.NEGATIVE }
                    Text("총 ${st.data.size}건 · 호재 $pos · 악재 $neg · 중립 ${st.data.size - pos - neg} (제목 키워드 자동 분류)", color = AppColors.Sub, fontSize = 11.sp)
                    list.take(20).forEach { NewsRow(it) }
                }
            }
        }
        Text("출처: Google 뉴스 RSS" + if (vm.prefs.finnhubKey.isNotBlank()) " + Finnhub" else "", color = AppColors.Sub, fontSize = 11.sp)
    }
}
