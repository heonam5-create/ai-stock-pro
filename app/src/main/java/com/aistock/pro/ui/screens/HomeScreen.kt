package com.aistock.pro.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistock.pro.analysis.Fmt
import com.aistock.pro.data.Events
import com.aistock.pro.data.MarketClock
import com.aistock.pro.data.NewsItem
import com.aistock.pro.data.Sentiment
import com.aistock.pro.data.StockDirectory
import com.aistock.pro.data.UiState
import com.aistock.pro.ui.MainViewModel
import com.aistock.pro.ui.components.AppHeader
import com.aistock.pro.ui.components.Badge
import com.aistock.pro.ui.components.ChipRow
import com.aistock.pro.ui.components.Disclaimer
import com.aistock.pro.ui.components.EmptyText
import com.aistock.pro.ui.components.ErrorBox
import com.aistock.pro.ui.components.LoadingBox
import com.aistock.pro.ui.components.SearchField
import com.aistock.pro.ui.components.SearchResults
import com.aistock.pro.ui.components.SectionCard
import com.aistock.pro.ui.components.SelectChip
import com.aistock.pro.ui.components.StockRow
import com.aistock.pro.ui.theme.AppColors
import com.aistock.pro.ui.theme.changeColor

@Composable
fun HomeScreen(vm: MainViewModel) {
    LaunchedEffect(Unit) { vm.loadHome() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader("한국·미국 주식 실시간(지연) 시세 · AI 분석") {
            IconButton(onClick = { vm.loadHome(force = true) }) { Icon(Icons.Filled.Refresh, "새로고침", tint = AppColors.Sub) }
        }
        SearchField(vm.query, vm::onQuery)
        SearchResults(vm.searchState, vm::open) { vm.onQuery(vm.query) }

        if (vm.query.isBlank()) {
            Text("빠른 검색", color = AppColors.Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp))
            Row(Modifier.padding(start = 12.dp)) {
                ChipRow {
                    listOf("005930.KS", "000660.KS", "035420.KS", "NVDA", "MU", "SNDK", "TSLA", "AAPL").forEach { s ->
                        val e = StockDirectory.bySymbol(s)!!
                        SelectChip(e.nameKr, false) { vm.open(e.toRef()) }
                    }
                }
            }
        }

        SectionCard("시장 지수") {
            when (val st = vm.indicesState) {
                is UiState.Loading, is UiState.Idle -> LoadingBox()
                is UiState.Error -> ErrorBox(st.message) { vm.loadHome(true) }
                is UiState.Success -> st.data.forEach { iq ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(iq.name, color = AppColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(Fmt.num(iq.price, false), color = AppColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(10.dp))
                        Text(Fmt.pct(iq.changePct), color = changeColor(iq.changePct), fontSize = 14.sp, modifier = Modifier.width(72.dp))
                    }
                }
            }
            Text("지수·환율은 무료 지연 데이터입니다.", color = AppColors.Sub, fontSize = 11.sp)
        }

        if (vm.recent.isNotEmpty()) {
            SectionCard("최근 본 종목") {
                vm.recent.take(5).forEach { r -> StockRow(r) { vm.open(r) } }
            }
        }

        SectionCard("주요 경제 일정") {
            Events.upcomingMacro().forEach { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Badge(if (e.daysLeft in 0..998) "D-${e.daysLeft}" else "-", AppColors.Gold)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${e.date}  ${e.title}", color = AppColors.Text, fontSize = 14.sp)
                        Text(e.detail, color = AppColors.Sub, fontSize = 12.sp)
                    }
                }
            }
            Text("CPI·고용지표 발표일은 무료 API가 없어 아래 경제 뉴스로 확인하세요 (미국 노동통계국 BLS 공식 일정 기준).", color = AppColors.Sub, fontSize = 11.sp)
        }

        SectionCard("경제·금리·반도체 뉴스 (최근 7일)") {
            when (val st = vm.macroState) {
                is UiState.Loading, is UiState.Idle -> LoadingBox()
                is UiState.Error -> ErrorBox(st.message) { vm.loadHome(true) }
                is UiState.Success -> if (st.data.isEmpty()) EmptyText("최근 7일 내 관련 뉴스가 없습니다.") else st.data.take(10).forEach { NewsRow(it) }
            }
        }
        Disclaimer()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun NewsRow(n: NewsItem) {
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { runCatching { uri.openUri(n.link) } }.padding(vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(n.sentiment.label, when (n.sentiment) { Sentiment.POSITIVE -> AppColors.Up; Sentiment.NEGATIVE -> AppColors.Down; else -> AppColors.Sub })
            n.categories.take(2).forEach { Spacer(Modifier.width(4.dp)); Badge(it, AppColors.Purple) }
        }
        Spacer(Modifier.height(3.dp))
        Text(n.title, color = AppColors.Text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
        Text(
            "${n.source} · ${MarketClock.relative(n.publishedAt)} (${MarketClock.formatKst(n.publishedAt)})" +
                (n.relatedName?.let { " · 관련: $it" } ?: ""),
            color = AppColors.Sub, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}
