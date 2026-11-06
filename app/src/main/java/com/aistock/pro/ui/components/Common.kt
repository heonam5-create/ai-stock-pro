package com.aistock.pro.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistock.pro.analysis.Fmt
import com.aistock.pro.data.Market
import com.aistock.pro.data.StockRef
import com.aistock.pro.data.UiState
import com.aistock.pro.ui.theme.AppColors

@Composable
fun AppHeader(subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("AI 주식 분석센터 PRO", color = AppColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = AppColors.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        color = AppColors.Card, shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = AppColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            Spacer(Modifier.size(8.dp))
            content()
        }
    }
}

@Composable
fun LoadingBox(text: String = "불러오는 중…") {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = AppColors.Sub, fontSize = 14.sp)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = AppColors.Orange, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(message, color = AppColors.Text, fontSize = 14.sp)
        }
        if (onRetry != null) {
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 44.dp)) {
                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("다시 시도")
            }
        }
    }
}

@Composable
fun EmptyText(text: String) {
    Text(text, color = AppColors.Sub, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
}

/** 선택형 칩 (가로 스크롤 줄에서 사용) */
@Composable
fun SelectChip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = when { !enabled -> AppColors.Card2.copy(alpha = 0.4f); selected -> AppColors.Accent; else -> AppColors.Card2 }
    val fg = when { !enabled -> AppColors.Sub.copy(alpha = 0.5f); selected -> Color.White; else -> AppColors.Text }
    Surface(
        color = bg, shape = RoundedCornerShape(18.dp),
        border = if (selected) null else BorderStroke(1.dp, AppColors.Line),
        modifier = Modifier.padding(end = 6.dp).heightIn(min = 36.dp).clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(text, color = fg, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun Badge(text: String, color: Color) {
    Box(Modifier.background(color.copy(alpha = 0.18f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun KeyValue(k: String, v: String, vColor: Color = AppColors.Text, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = AppColors.Sub, fontSize = 13.sp, modifier = Modifier.weight(0.42f))
        Text(v, color = vColor, fontSize = 13.sp, modifier = Modifier.weight(0.58f))
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = AppColors.Accent, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String = "종목명·코드·티커 (예: 삼성전자, 엔비디아, NVDA)") {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Clear, "지우기") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AppColors.Accent, unfocusedBorderColor = AppColors.Line,
            focusedContainerColor = AppColors.Card, unfocusedContainerColor = AppColors.Card,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
fun SearchResults(state: UiState<List<StockRef>>, onPick: (StockRef) -> Unit, onRetry: () -> Unit) {
    when (state) {
        is UiState.Idle -> {}
        is UiState.Loading -> LoadingBox("검색 중…")
        is UiState.Error -> ErrorBox(state.message, onRetry)
        is UiState.Success -> {
            if (state.data.isEmpty()) {
                EmptyText("  검색 결과가 없습니다. 6자리 종목코드나 영문 티커로도 검색해 보세요. (설정에서 공공데이터 키를 등록하면 한국 전 종목 한글 검색 가능)")
            } else Surface(color = AppColors.Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column {
                    state.data.forEach { r -> StockRow(r) { onPick(r) } }
                }
            }
        }
    }
}

@Composable
fun StockRow(r: StockRef, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.displayName, color = AppColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(r.symbol, r.exchange.ifBlank { null }, if (r.nameKr != null && r.name != r.nameKr) r.name else null).joinToString(" · ")
            Text(sub, color = AppColors.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing() else Badge(if (r.market == Market.KR) "한국" else "미국", if (r.market == Market.KR) AppColors.Accent else AppColors.Gold)
    }
}

@Composable
fun Disclaimer() {
    Text(
        "※ 본 앱의 분석·추천은 공개 시세 데이터로 계산한 참고 정보이며 투자 권유가 아닙니다. 무료 시세는 지연될 수 있고, " +
            "제시 가격의 체결이나 수익을 보장하지 않습니다. 투자 판단과 책임은 본인에게 있습니다.",
        color = AppColors.Sub, fontSize = 11.sp, lineHeight = 16.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

fun priceText(p: Double?, market: Market) = Fmt.price(p, market == Market.KR)
