import SwiftUI
import AIStockCore

struct SettingsView: View {
    @EnvironmentObject var m: AppModel
    @State private var alertsOn = Prefs.shared.alertsEnabled
    @State private var includeWatch = Prefs.shared.includeWatchInBest
    @State private var finnhub = ""
    @State private var aiEndpoint = Prefs.shared.aiEndpoint
    @State private var aiModel = Prefs.shared.aiModel
    @State private var aiKey = ""
    @State private var aiTest: String?
    @State private var saved: String?
    @State private var listMsg: String?
    @State private var updating = false

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Header(subtitle: "설정 · 데이터 출처 · 외부 AI") { EmptyView() }

                SectionCard("알림 · 추천") {
                    Toggle("주가 알림 사용", isOn: $alertsOn).onChange(of: alertsOn) { v in Prefs.shared.alertsEnabled = v; if v { AlertMonitor.requestPermission(); AlertMonitor.schedule() } }
                    Toggle("BEST 5 후보에 관심 종목도 포함", isOn: $includeWatch).onChange(of: includeWatch) { Prefs.shared.includeWatchInBest = $0 }
                }.foregroundColor(AppColors.text)

                SectionCard("종목 목록 (전체 검색용)") {
                    let c = StockDirectory.shared.counts
                    KV("한국", "\(c.kr)종목 · 기준 \(StockDirectory.shared.krUpdated)")
                    KV("미국", "\(c.us)종목 · 기준 \(StockDirectory.shared.usUpdated)")
                    Text("KRX 공식 상장법인목록·네이버 금융·Nasdaq Trader 목록으로 매주 자동 생성됩니다. 신규 상장·상장폐지·이름 변경은 아래 버튼(또는 앱 실행 시 자동)으로 반영됩니다.")
                        .font(.system(size: 12)).foregroundColor(AppColors.sub)
                    PrimaryButton(title: updating ? "갱신 중…" : "종목 목록 지금 갱신", enabled: !updating) {
                        updating = true
                        Task { listMsg = await StockDirectory.shared.updateFromRemote(); updating = false }
                    }
                    if let msg = listMsg { Text(msg).font(.system(size: 12)).foregroundColor(AppColors.sub) }
                }

                SectionCard("무료 API 키 (선택)") {
                    Text("Finnhub 무료 키: 미국 종목 교차검증·시가총액·실적 발표일·회사 뉴스 (finnhub.io 가입 → API Key). 키는 iPhone 키체인에만 저장되며 앱·저장소에 포함되지 않습니다.")
                        .font(.system(size: 12)).foregroundColor(AppColors.sub)
                    SecureField(Prefs.shared.finnhubKey.isEmpty ? "Finnhub API 키" : "등록됨 (변경하려면 새 키 입력)", text: $finnhub).textFieldStyle(.roundedBorder)
                }

                SectionCard("외부 AI 연결 (OpenAI 호환, 선택)") {
                    Text("예: https://api.openai.com/v1 · 모델 gpt-4o-mini. 연결 실패와 관계없이 앱 내장 분석 엔진은 항상 동작합니다. 외부 AI는 시세를 직접 조회하지 않고 앱이 계산한 결과만 전달받습니다.")
                        .font(.system(size: 12)).foregroundColor(AppColors.sub)
                    TextField("API 주소", text: $aiEndpoint).textFieldStyle(.roundedBorder).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    TextField("모델 이름", text: $aiModel).textFieldStyle(.roundedBorder).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField(Prefs.shared.aiKey.isEmpty ? "API 키" : "등록됨 (변경하려면 새 키 입력)", text: $aiKey).textFieldStyle(.roundedBorder)
                    HStack {
                        Button("저장") { save() }.buttonStyle(.borderedProminent)
                        Button("연결 테스트") {
                            save(); aiTest = "테스트 중…"
                            let p = Prefs.shared
                            Task {
                                do { aiTest = "✅ 연결 성공: " + String(try await ExternalAi.test(endpoint: p.aiEndpoint, model: p.aiModel, key: p.aiKey).prefix(40)) }
                                catch { aiTest = "❌ " + Http.friendly(error) }
                            }
                        }.buttonStyle(.bordered).disabled(aiEndpoint.isEmpty || aiModel.isEmpty)
                        Button("키 삭제", role: .destructive) { Prefs.shared.aiKey = ""; Prefs.shared.finnhubKey = ""; saved = "저장된 키를 삭제했습니다." }.buttonStyle(.bordered)
                    }
                    if let t = aiTest { Text(t).font(.system(size: 13)).foregroundColor(AppColors.text) }
                    if let s = saved { Text(s).font(.system(size: 13)).foregroundColor(AppColors.green) }
                }

                SectionCard("데이터 출처 · 지연 안내") {
                    Text("""
                    • 현재가(기본): 네이버 금융 — 공급원이 지연 0분으로 표기. 한국 KRX + NXT, 미국 정규장 + 프리/애프터마켓
                    • 보조·대체: Yahoo Finance — 교차검증, 미국 분봉, 네이버 실패 시 '지연 가능 / 대체 데이터 사용 중' 표시
                    • 미국 '주간거래'(야간 대체거래소) 가격은 무료 공급원이 제공하지 않습니다
                    • 뉴스: 네이버 금융 뉴스 · Google 뉴스 RSS (제목 키워드로 호재/악재 자동 분류)
                    • 모두 비공식 공개 데이터로 개인·비상업 용도로만 사용하세요. 공급원 정책 변경 시 조회가 실패할 수 있습니다
                    """).font(.system(size: 13)).foregroundColor(AppColors.sub)
                    Button("캐시 비우기") { Task { await m.repo.clearCache() } }.buttonStyle(.bordered)
                }

                SectionCard("앱 정보") {
                    KV("버전", "\(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "-") (\(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "-"))")
                    KV("분석 엔진", "앱 내장 규칙 기반 다요인 모델 (안드로이드판과 동일)")
                }
                Disclaimer()
            }
        }.background(AppColors.bg)
    }

    func save() {
        let p = Prefs.shared
        p.aiEndpoint = aiEndpoint; p.aiModel = aiModel
        if !aiKey.isEmpty { p.aiKey = aiKey; aiKey = "" }
        if !finnhub.isEmpty { p.finnhubKey = finnhub; finnhub = "" }
        Task { await m.repo.clearCache() }
        saved = "저장했습니다."
    }
}
