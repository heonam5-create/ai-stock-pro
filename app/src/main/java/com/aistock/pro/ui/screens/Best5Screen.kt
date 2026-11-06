package com.aistock.pro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.aistock.pro.analysis.Fmt
import com.aistock.pro.analysis.Recommendation
import com.aistock.pro.data.Market
import com.aistock.pro.data.MarketClock
import com.aistock.pro.data.Repository
import com.aistock.pro.data.StockDirectory
import com.aistock.pro.data.UiState
import com.aistock.pro.ui.MainViewModel
import com.aistock.pro.ui.components.AppHeader
import com.aistock.pro.ui.components.Badge
import com.aistock.pro.ui.components.ChipRow
import com.aistock.pro.ui.components.Disclaimer
import com.aistock.pro.ui.components.EmptyText
import com.aistock.pro.ui.components.ErrorBox
import com.aistock.pro.ui.components.KeyValue
import com.aistock.pro.ui.components.PrimaryButton
import com.aistock.pro.ui.components.SectionCard
import com.aistock.pro.ui.components.SelectChip
import com.aistock.pro.ui.theme.AppColors
import com.aistock.pro.ui.theme.verdictColor

@Composable
fun Best5Screen(vm: MainViewModel) {
    val market = vm.bestMarket
    val state = if (market == Market.KR) vm.bestKr else vm.bestUs
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader("실제 데이터 점수 기반 추천 BEST 5")
        Row(Modifier.padding(horizontal = 12.dp)) {
            ChipRow {
                SelectChip("🇰🇷 한국 주식 BEST 5", market == Market.KR) { vm.bestMarket = Market.KR }
                SelectChip("🇺🇸 미국 주식 BEST 5", market == Market.US) { vm.bestMarket = Market.US }
            }
        }
        Spacer(Modifier.height(8.dp))
        val universe = if (market == Market.KR) StockDirectory.krUniverse.size else StockDirectory.usUniverse.size
        Box(Modifier.padding(horizontal = 12.dp)) {
            PrimaryButton(
                if (state is UiState.Loading) "분석 중… ${vm.bestProgress.first}/${vm.bestProgress.second}"
                else if (state is UiState.Success) "다시 분석 (최신 데이터)" else "BEST 5 분석 시작",
                enabled = state !is UiState.Loading,
            ) { vm.runBest(market) }
        }
        Text(
            "후보 ${universe}종목${if (vm.prefs.includeWatchInBest) " + 관심 종목" else ""}의 일봉 2년 데이터를 모두 분석해 9개 평가항목 점수로 순위를 매깁니다. " +
                "상위 10종목은 최신 뉴스까지 반영해 재평가합니다. (무료 API 호출 한도 때문에 전 종목 스캔 대신 대표 후보군을 사용)",
            color = AppColors.Sub, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when (state) {
            is UiState.Idle -> EmptyText("   위 버튼을 눌러 분석을 시작하세요. (약 20~60초 소요)")
            is UiState.Loading -> {
                val (d, t) = vm.bestProgress
                LinearProgressIndicator(progress = { if (t == 0) 0f else d / t.toFloat() }, modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
            is UiState.Error -> ErrorBox(state.message) { vm.runBest(market) }
            is UiState.Success -> BestResultView(vm, state.data, market)
        }
        Disclaimer()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun BestResultView(vm: MainViewModel, res: Repository.BestResult, market: Market) {
    Text(
        "분석 완료 ${MarketClock.formatKst(res.at)} · 분석 ${res.analyzed}종목 · 추천 제외(매수 금지·분할매도·분석 불가) ${res.excluded}종목" +
            (if (res.staleExcluded > 0) " · 시세 지연·불일치로 제외 ${res.staleExcluded}종목" else "") +
            (if (res.failed.isNotEmpty()) " · 데이터 수집 실패 ${res.failed.size}종목(${res.failed.take(5).joinToString()})" else ""),
        color = AppColors.Sub, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 16.dp),
    )
    if (res.picks.isEmpty()) {
        SectionCard("추천 결과") { EmptyText("현재 추천 조건(매수 판단 + 손익비)을 충족한 종목이 없습니다. 시장이 약세일 때는 추천하지 않는 것이 정상 동작입니다.") }
        return
    }
    if (res.picks.size < 5) Text("   조건을 충족한 종목이 ${res.picks.size}개뿐이라 ${res.picks.size}개만 표시합니다.", color = AppColors.Orange, fontSize = 12.sp)
    res.picks.forEachIndexed { idx, rec -> RecoCard(idx + 1, rec, market) { vm.open(rec.ref) } }
}

@Composable
private fun RecoCard(rank: Int, rec: Recommendation, market: Market, onClick: () -> Unit) {
    val kr = market == Market.KR
    var expanded by remember { mutableStateOf(false) }
    val s = rec.result.strategy
    Surface(
        color = AppColors.Card, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).background(if (rank == 1) AppColors.Gold else AppColors.Card2, CircleShape), contentAlignment = Alignment.Center) {
                    Text("$rank", color = if (rank == 1) Color.Black else AppColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(rec.ref.displayName, color = AppColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${rec.ref.symbol} · 현재가 ${Fmt.price(rec.price, kr)}", color = AppColors.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${rec.total}점", color = AppColors.Gold, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Badge(rec.result.verdict.label, verdictColor(rec.result.verdict))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("추천 이유: ${rec.reason}", color = AppColors.Text, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(6.dp))
            if (s != null) {
                KeyValue("1차 매수가", "${Fmt.price(s.buys.first().price, kr)} (${Fmt.pct(s.buys.first().diffPct)})", AppColors.Up)
                KeyValue("1차 목표가", "${Fmt.price(s.targets.first().price, kr)} (${Fmt.pct(s.targets.first().diffPct)})", AppColors.Down)
                KeyValue("손절가", "${Fmt.price(s.stop.price, kr)} (${Fmt.pct(s.stop.diffPct)})", Color(0xFFE11D48))
            }
            KeyValue("위험 등급", rec.riskGrade, when (rec.riskGrade) { "낮음" -> AppColors.Green; "보통" -> AppColors.Gold; else -> AppColors.Orange })
            KeyValue("데이터 기준 시각", MarketClock.format(rec.dataTime, market))
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "평가항목 접기" else "9개 평가항목 점수 보기", fontSize = 13.sp) }
            if (expanded) rec.parts.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, color = AppColors.Sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    LinearProgressIndicator(progress = { v / 10f }, modifier = Modifier.width(110.dp), color = AppColors.Accent, trackColor = AppColors.Card2)
                    Text(" $v", color = AppColors.Text, fontSize = 12.sp, modifier = Modifier.width(26.dp))
                }
            }
            Text("눌러서 상세 분석 보기 ›", color = AppColors.Accent, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
