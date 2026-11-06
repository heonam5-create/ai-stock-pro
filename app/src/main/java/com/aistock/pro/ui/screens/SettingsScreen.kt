package com.aistock.pro.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistock.pro.ui.MainViewModel
import com.aistock.pro.ui.components.AppHeader
import com.aistock.pro.ui.components.ChipRow
import com.aistock.pro.ui.components.Disclaimer
import com.aistock.pro.ui.components.KeyValue
import com.aistock.pro.ui.components.PrimaryButton
import com.aistock.pro.ui.components.SectionCard
import com.aistock.pro.ui.components.SelectChip
import com.aistock.pro.ui.theme.AppColors

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val p = vm.prefs
    var alertsOn by remember { mutableStateOf(p.alertsEnabled) }
    var interval by remember { mutableStateOf(p.alertIntervalMin) }
    var includeWatch by remember { mutableStateOf(p.includeWatchInBest) }
    var finnhub by remember { mutableStateOf(p.finnhubKey) }
    var dataGo by remember { mutableStateOf(p.dataGoKrKey) }
    var aiEndpoint by remember { mutableStateOf(p.aiEndpoint) }
    var aiModel by remember { mutableStateOf(p.aiModel) }
    var aiKey by remember { mutableStateOf(p.aiKey) }
    var saved by remember { mutableStateOf("") }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        saved = if (ok) "알림 권한이 허용되었습니다." else "알림 권한이 거부되었습니다. 휴대폰 설정 > 애플리케이션에서 허용할 수 있습니다."
    }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "-"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader("설정")
        if (saved.isNotBlank()) Text("   ✓ $saved", color = AppColors.Green, fontSize = 13.sp)

        SectionCard("주가 알림") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("백그라운드 알림 사용", color = AppColors.Text, modifier = Modifier.weight(1f))
                Switch(checked = alertsOn, onCheckedChange = { alertsOn = it })
            }
            Text("확인 주기", color = AppColors.Sub, fontSize = 12.sp)
            ChipRow { listOf(15, 30, 60).forEach { m -> SelectChip("${m}분", interval == m) { interval = m } } }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS) else saved = "이 안드로이드 버전은 별도 알림 권한이 필요 없습니다."
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) { Text("알림 권한 허용하기") }
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) } }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) { Text("배터리 최적화 제외 설정 열기") }
            Text("삼성 갤럭시: 설정 > 애플리케이션 > AI 주식 분석센터 PRO > 배터리 > '제한 없음' 선택 시 알림이 더 정확합니다.", color = AppColors.Sub, fontSize = 11.sp)
        }

        SectionCard("추천 BEST 5") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("관심 종목도 추천 후보에 포함", color = AppColors.Text, modifier = Modifier.weight(1f))
                Switch(checked = includeWatch, onCheckedChange = { includeWatch = it })
            }
        }
        Spacer(Modifier.height(4.dp))
        Column(Modifier.padding(horizontal = 12.dp)) {
            PrimaryButton("설정 저장") { vm.saveSettings(alertsOn, interval, includeWatch); saved = "설정을 저장했습니다." }
        }

        SectionCard("무료 API 키 (선택 · 기기에 암호화 저장)") {
            Text("키 없이도 시세·차트·분석·추천·뉴스는 모두 작동합니다. 아래 키는 추가 정보용입니다.", color = AppColors.Sub, fontSize = 12.sp)
            KeyField("Finnhub 키 (미국 시가총액·실적일·뉴스)", finnhub) { finnhub = it }
            Text("finnhub.io 무료 가입 → Dashboard 의 API Key 복사", color = AppColors.Sub, fontSize = 11.sp)
            KeyField("공공데이터포털 인증키 (한국 전 종목 한글 검색·시가총액)", dataGo) { dataGo = it }
            Text("data.go.kr 가입 → '금융위원회_주식시세정보' 활용신청 → 일반 인증키(Decoding) 복사", color = AppColors.Sub, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Text("외부 AI (선택 · OpenAI 호환 API)", color = AppColors.Text, fontSize = 14.sp)
            OutlinedTextField(aiEndpoint, { aiEndpoint = it }, label = { Text("API 주소 예: https://api.openai.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
            OutlinedTextField(aiModel, { aiModel = it }, label = { Text("모델 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
            KeyField("외부 AI API 키", aiKey) { aiKey = it }
            Text("외부 AI 사용 요금은 해당 서비스 정책을 따릅니다. 비워 두면 무료 로컬 엔진만 사용합니다.", color = AppColors.Sub, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            PrimaryButton("키 저장") { vm.saveKeys(finnhub, dataGo, aiEndpoint, aiModel, aiKey); saved = "API 키를 암호화해 저장했습니다." }
        }

        SectionCard("데이터 출처 및 제한") {
            KeyValue("시세·차트", "Yahoo Finance 공개 차트 데이터 (키 불필요, 한국 약 20분 지연, 개인 비상업 용도)")
            KeyValue("뉴스", "Google 뉴스 RSS (최근 7일)")
            KeyValue("한국 시가총액", "공공데이터포털 (선택, 전 거래일 기준)")
            KeyValue("미국 부가정보", "Finnhub 무료 키 (선택)")
            KeyValue("AI 분석", "휴대폰 내 로컬 엔진 (무료·오프라인 계산)")
            KeyValue("경제 일정", "FOMC 공식 일정 내장 (2027년 12월까지)")
            OutlinedButton(onClick = { vm.clearCache(); saved = "임시 저장 데이터(캐시)를 비웠습니다." }, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) { Text("캐시 비우기") }
        }
        SectionCard("앱 정보") {
            KeyValue("버전", version)
            KeyValue("저장 위치", "관심 종목·보유 정보·알림은 휴대폰에만 저장되며 업데이트 설치 후에도 유지됩니다.")
        }
        Disclaimer()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, fontSize = 12.sp) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    )
}
