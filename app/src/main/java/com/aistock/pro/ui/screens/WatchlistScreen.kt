package com.aistock.pro.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistock.pro.analysis.Fmt
import com.aistock.pro.data.AlertType
import com.aistock.pro.data.Market
import com.aistock.pro.data.MarketClock
import com.aistock.pro.data.WatchItem
import com.aistock.pro.ui.MainViewModel
import com.aistock.pro.ui.components.AppHeader
import com.aistock.pro.ui.components.Badge
import com.aistock.pro.ui.components.ChipRow
import com.aistock.pro.ui.components.Disclaimer
import com.aistock.pro.ui.components.EmptyText
import com.aistock.pro.ui.components.KeyValue
import com.aistock.pro.ui.components.LoadingBox
import com.aistock.pro.ui.components.SearchField
import com.aistock.pro.ui.components.SearchResults
import com.aistock.pro.ui.components.SectionCard
import com.aistock.pro.ui.components.SelectChip
import com.aistock.pro.ui.theme.AppColors
import com.aistock.pro.ui.theme.changeColor

private fun String.num(): Double? = replace(",", "").trim().toDoubleOrNull()

@Composable
fun WatchlistScreen(vm: MainViewModel) {
    var sub by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { vm.refreshWatch(); vm.reloadAlerts() }
    // 화면이 보이는 동안 30초마다 시세 자동 갱신 (무료 공급원 호출 제한 고려)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) { kotlinx.coroutines.delay(30_000L); vm.refreshWatch() }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader("관심 종목 · 보유 주식 · 알림") {
            IconButton(onClick = { vm.refreshWatch(); vm.reloadAlerts() }) { Icon(Icons.Filled.Refresh, "새로고침", tint = AppColors.Sub) }
        }
        Row(Modifier.padding(horizontal = 12.dp)) {
            ChipRow {
                SelectChip("관심·보유 종목 (${vm.watchlist.size})", sub == 0) { sub = 0 }
                SelectChip("주가 알림 (${vm.alerts.count { it.enabled }})", sub == 1) { sub = 1 }
            }
        }
        if (sub == 0) WatchTab(vm) else AlertTab(vm)
        Disclaimer()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun WatchTab(vm: MainViewModel) {
    Text("종목 추가", color = AppColors.Sub, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
    SearchField(vm.query, vm::onQuery, "추가할 종목 검색")
    SearchResults(vm.searchState, { vm.addWatch(it); vm.clearSearch() }) { vm.onQuery(vm.query) }

    // ── 포트폴리오 합계 (원화 환산) ──
    val fx = vm.fx
    var evalKrw = 0.0; var costKrw = 0.0; var missingFx = false
    vm.watchlist.filter { it.qty > 0 && it.avgPrice > 0 }.forEach { w ->
        val q = vm.watchQuotes[w.symbol] ?: return@forEach
        val rate = if (w.market == Market.KR) 1.0 else (fx ?: run { missingFx = true; return@forEach })
        evalKrw += q.price * w.qty * rate; costKrw += w.avgPrice * w.qty * rate
    }
    if (costKrw > 0) SectionCard("보유 주식 합계 (원화 환산)") {
        val pl = evalKrw - costKrw
        KeyValue("총 평가금액", Fmt.won(evalKrw))
        KeyValue("총 매수금액", Fmt.won(costKrw))
        KeyValue("총 평가손익", "${Fmt.won(pl)} (${Fmt.pct(pl / costKrw * 100)})", changeColor(pl))
        KeyValue("적용 환율", fx?.let { "1달러 = ${Fmt.won(it)} (현재 환율, 매수 시점 환율 아님)" } ?: "환율 조회 실패")
        if (missingFx) Text("환율을 불러오지 못해 미국 주식은 합계에서 제외되었습니다.", color = AppColors.Orange, fontSize = 11.sp)
    }

    if (vm.watchLoading && vm.watchQuotes.isEmpty()) LoadingBox("관심 종목 시세 불러오는 중…")
    if (vm.watchlist.isEmpty()) {
        SectionCard("관심 종목") { EmptyText("등록된 관심 종목이 없습니다. 위에서 검색해 추가하거나, 종목 분석 화면의 ♡ 버튼을 누르세요.") }
    }
    vm.watchlist.forEach { w -> WatchCard(vm, w) }
}

@Composable
private fun WatchCard(vm: MainViewModel, w: WatchItem) {
    val kr = w.market == Market.KR
    val q = vm.watchQuotes[w.symbol]
    var edit by remember { mutableStateOf(false) }
    var calc by remember { mutableStateOf(false) }
    SectionCard(w.name, action = {
        IconButton(onClick = { vm.removeWatch(w.symbol) }) { Icon(Icons.Filled.Delete, "삭제", tint = AppColors.Sub) }
    }) {
        Text(w.symbol + if (q != null) " · ${q.statusText}" else "", color = AppColors.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (q != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.price(q.price, kr), color = changeColor(q.changePct), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(Fmt.pct(q.changePct), color = changeColor(q.changePct), fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
            Text("기준 ${MarketClock.format(q.marketTime, w.market)} · ${q.source} · 갱신 ${MarketClock.relative(q.fetchedAt / 1000)}", color = AppColors.Sub, fontSize = 11.sp)
            q.ext?.let { e ->
                Text("${e.label}${if (e.active) "(진행 중)" else "(종료)"} ${Fmt.price(e.price, kr)} ${Fmt.pct(e.changePct)}",
                    color = changeColor(e.changePct), fontSize = 12.sp)
            }
            q.warning?.let { Text("⚠ $it", color = AppColors.Orange, fontSize = 11.sp, lineHeight = 15.sp) }
        } else {
            val err = vm.watchErrors[w.symbol]
            Text(err ?: if (vm.watchLoading) "시세 불러오는 중…" else "시세 없음", color = if (err != null) AppColors.Orange else AppColors.Sub, fontSize = 13.sp)
        }
        if (w.qty > 0 && w.avgPrice > 0) {
            Spacer(Modifier.height(6.dp))
            KeyValue("보유 수량", "${Fmt.num(w.qty, true)}주")
            KeyValue("평균 매수가", Fmt.price(w.avgPrice, kr))
            if (q != null) {
                val eval = q.price * w.qty; val cost = w.avgPrice * w.qty; val pl = eval - cost
                KeyValue("평가금액", Fmt.price(eval, kr))
                KeyValue("평가손익", "${Fmt.price(pl, kr)} (${Fmt.pct(pl / cost * 100)})", changeColor(pl))
                q.ext?.let { e ->
                    val pl2 = e.price * w.qty - cost
                    KeyValue("${e.label} 기준", "${Fmt.price(pl2, kr)} (${Fmt.pct(pl2 / cost * 100)}) · 참고용", changeColor(pl2))
                }
                Text("평가 기준: ${q.statusText} 가격 (${MarketClock.format(q.marketTime, w.market)})" +
                    if (q.unreliable) " · ⚠ 시세 신뢰도 낮음" else "", color = if (q.unreliable) AppColors.Orange else AppColors.Sub, fontSize = 11.sp)
                if (!kr) vm.fx?.let { fx -> KeyValue("원화 환산 손익", "${Fmt.won(pl * fx)} (현재 환율 ${Fmt.won(fx)})", changeColor(pl)) }
            }
        }
        w.targetPrice?.let { t -> KeyValue("목표 매도가", "${Fmt.price(t, kr)}" + (q?.let { " (${Fmt.pct((t / it.price - 1) * 100)})" } ?: "")) }
        w.stopPrice?.let { s -> KeyValue("손절가", "${Fmt.price(s, kr)}" + (q?.let { " (${Fmt.pct((s / it.price - 1) * 100)})" } ?: "")) }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.openSymbol(w.symbol, w.name) }, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("분석", fontSize = 13.sp) }
            OutlinedButton(onClick = { edit = true }, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("보유 입력", fontSize = 13.sp, maxLines = 1) }
            OutlinedButton(onClick = { calc = true }, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("계산기", fontSize = 13.sp) }
        }
    }
    if (edit) EditDialog(w, onDismiss = { edit = false }) { vm.updateWatch(it); edit = false }
    if (calc) CalcDialog(w, q?.price, kr) { calc = false }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    )
}

private fun Double?.str(): String = if (this == null || this == 0.0) "" else if (this % 1.0 == 0.0) this.toLong().toString() else this.toString()

@Composable
private fun EditDialog(w: WatchItem, onDismiss: () -> Unit, onSave: (WatchItem) -> Unit) {
    var qty by remember { mutableStateOf(w.qty.str()) }
    var avg by remember { mutableStateOf(w.avgPrice.str()) }
    var target by remember { mutableStateOf(w.targetPrice.str()) }
    var stop by remember { mutableStateOf(w.stopPrice.str()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${w.name} 보유 정보") },
        text = {
            Column {
                NumField("보유 수량 (주)", qty) { qty = it }
                NumField("평균 매수가 (${if (w.market == Market.KR) "원" else "달러"})", avg) { avg = it }
                NumField("목표 매도가 (선택)", target) { target = it }
                NumField("손절가 (선택)", stop) { stop = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(w.copy(qty = qty.num() ?: 0.0, avgPrice = avg.num() ?: 0.0, targetPrice = target.num(), stopPrice = stop.num()))
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 추가 매수 평균단가 / 분할 매도 수익 계산기 */
@Composable
private fun CalcDialog(w: WatchItem, current: Double?, kr: Boolean, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(0) }
    var q1 by remember { mutableStateOf(w.qty.str()) }
    var p1 by remember { mutableStateOf(w.avgPrice.str()) }
    var q2 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf(current?.let { if (kr) it.toLong().toString() else "%.2f".format(it) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${w.name} 계산기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ChipRow {
                    SelectChip("추가 매수 평단", mode == 0) { mode = 0 }
                    SelectChip("분할 매도 수익", mode == 1) { mode = 1 }
                }
                NumField("현재 보유 수량", q1) { q1 = it }
                NumField("현재 평균단가", p1) { p1 = it }
                NumField(if (mode == 0) "추가 매수 수량" else "매도 수량", q2) { q2 = it }
                NumField(if (mode == 0) "추가 매수 가격" else "매도 가격", p2) { p2 = it }
                val a = q1.num(); val b = p1.num(); val c = q2.num(); val d = p2.num()
                Spacer(Modifier.height(6.dp))
                if (a != null && b != null && c != null && d != null && c > 0) {
                    if (mode == 0) {
                        val newQty = a + c
                        val newAvg = (a * b + c * d) / newQty
                        KeyValue("매수 후 수량", "${Fmt.num(newQty, true)}주")
                        KeyValue("새 평균단가", Fmt.price(newAvg, kr), AppColors.Gold)
                        KeyValue("평단 변화", Fmt.pct((newAvg / b - 1) * 100))
                        KeyValue("추가 투입금", Fmt.price(c * d, kr))
                    } else {
                        if (c > a) Text("매도 수량이 보유 수량보다 많습니다.", color = AppColors.Orange, fontSize = 12.sp)
                        else {
                            val profit = (d - b) * c
                            KeyValue("실현 손익", Fmt.price(profit, kr), changeColor(profit))
                            KeyValue("수익률", Fmt.pct((d / b - 1) * 100), changeColor(profit))
                            KeyValue("매도 금액", Fmt.price(c * d, kr))
                            KeyValue("잔여 수량", "${Fmt.num(a - c, true)}주 (평단 ${Fmt.price(b, kr)} 유지)")
                            Text("※ 세금·수수료 제외 금액", color = AppColors.Sub, fontSize = 11.sp)
                        }
                    }
                } else Text("숫자를 모두 입력하면 결과가 표시됩니다.", color = AppColors.Sub, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun AlertTab(vm: MainViewModel) {
    var adding by remember { mutableStateOf(false) }
    SectionCard("알림 동작 안내") {
        Text(
            "• 앱을 꺼도 안드로이드 백그라운드 작업으로 약 ${vm.prefs.alertIntervalMin.coerceAtLeast(15)}분마다 시세를 확인합니다 (안드로이드 최소 주기 15분).\n" +
                "• 무료 지연 시세 + 절전(Doze) 모드 때문에 24시간 실시간 감시는 보장되지 않으며, 수 분~수십 분 늦게 울릴 수 있습니다.\n" +
                "• 가격 도달형 알림은 한 번 울리면 자동으로 꺼집니다. 급등락·거래량 알림은 장중에만, 6시간 간격으로 울립니다.\n" +
                "• 정확도를 높이려면 설정 > 배터리 최적화 제외를 권장합니다.",
            color = AppColors.Sub, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.weight(1f).heightIn(min = 46.dp)) { Text("+ 알림 추가") }
            OutlinedButton(onClick = { vm.checkAlertsNow() }, modifier = Modifier.weight(1f).heightIn(min = 46.dp)) { Text("지금 확인") }
        }
    }
    if (vm.alerts.isEmpty()) SectionCard("등록된 알림") { EmptyText("등록된 알림이 없습니다. 종목 분석 후 '이 가격으로 알림 설정' 버튼으로도 추가할 수 있습니다.") }
    vm.alerts.forEach { a ->
        val kr = a.symbol.endsWith(".KS") || a.symbol.endsWith(".KQ")
        SectionCard(a.name, action = {
            Switch(checked = a.enabled, onCheckedChange = { vm.toggleAlert(a.id) })
            IconButton(onClick = { vm.removeAlert(a.id) }) { Icon(Icons.Filled.Delete, "삭제", tint = AppColors.Sub) }
        }) {
            Text(a.type.label, color = AppColors.Text, fontSize = 14.sp)
            val v = if (a.type.unit == "가격") Fmt.price(a.value, kr) else "${a.value}${a.type.unit}"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(v, AppColors.Gold)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (a.lastFired > 0) "마지막 알림 ${MarketClock.formatKst(a.lastFired / 1000)}" else if (a.enabled) "감시 중" else "꺼짐",
                    color = AppColors.Sub, fontSize = 12.sp,
                )
            }
        }
    }
    if (adding) AddAlertDialog(vm) { adding = false }
}

@Composable
private fun AddAlertDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val candidates = (listOfNotNull(vm.selected?.let { it.symbol to it.displayName }) + vm.watchlist.map { it.symbol to it.name }).distinctBy { it.first }
    var sym by remember { mutableStateOf(candidates.firstOrNull()) }
    var type by remember { mutableStateOf(AlertType.BUY_PRICE) }
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("주가 알림 추가") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) Text("먼저 관심 종목을 등록하거나 종목을 분석하세요.", color = AppColors.Orange)
                Text("종목", color = AppColors.Sub, fontSize = 12.sp)
                ChipRow { candidates.forEach { c -> SelectChip(c.second, sym == c) { sym = c } } }
                Text("조건", color = AppColors.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                AlertType.entries.forEach { t ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = type == t, onClick = { type = t })
                        Text(t.label, fontSize = 13.sp)
                    }
                }
                NumField(
                    when (type.unit) { "%" -> "등락률 기준 (%) 예: 5"; "배" -> "평균 대비 배수 예: 2"; else -> "가격" },
                    value,
                ) { value = it }
                val q = sym?.let { vm.watchQuotes[it.first] }
                if (q != null) Text("현재가 ${Fmt.price(q.price, q.ref.market == Market.KR)}", color = AppColors.Sub, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = sym; val v = value.num()
                if (s != null && v != null && v > 0) { vm.addAlert(s.first, s.second, type, v); onDismiss() }
            }) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
