import SwiftUI
import UserNotifications
import AIStockCore

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        AlertMonitor.register()
        return true
    }
    // 앱 사용 중에도 알림 배너 표시
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions { [.banner, .sound, .list] }
}

@main
struct AIStockProApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .preferredColorScheme(.dark)      // 다크모드 기본
                .task {
                    // 종목 목록 최신본 확인: 하루 1회 (실패해도 내장본 사용)
                    let key = "stocklist_checked"
                    let last = UserDefaults.standard.double(forKey: key)
                    if Date().timeIntervalSince1970 - last > 86400 {
                        _ = await StockDirectory.shared.updateFromRemote()
                        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: key)
                    }
                }
        }
        .onChange(of: phase) { p in
            if p == .background { AlertMonitor.schedule() }
            if p == .active { Task { await AlertMonitor.checkNow(); model.reloadAlerts() } }
        }
    }
}

struct RootView: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        TabView(selection: $m.tab) {
            HomeView().tabItem { Label("홈", systemImage: "house.fill") }.tag(Tab.home)
            AnalysisView().tabItem { Label("종목 분석", systemImage: "chart.xyaxis.line") }.tag(Tab.analysis)
            Best5View().tabItem { Label("추천 BEST 5", systemImage: "star.fill") }.tag(Tab.best)
            WatchlistView().tabItem { Label("관심 종목", systemImage: "heart.fill") }.tag(Tab.watch)
            SettingsView().tabItem { Label("설정", systemImage: "gearshape.fill") }.tag(Tab.settings)
        }
        .tint(AppColors.accent)
        .onAppear {
            // 자동 점검(CI 스크린샷)용 실행 인자: -startTab best|watch|settings|analysis -openSymbol 005930.KS -runAnalysis 1
            let d = UserDefaults.standard
            if let s = d.string(forKey: "openSymbol"), !s.isEmpty {
                m.open(StockDirectory.shared.bySymbol(s)?.ref ?? StockRef(symbol: s, name: s))
                if d.bool(forKey: "runAnalysis") { DispatchQueue.main.asyncAfter(deadline: .now() + 3) { m.runAnalysis() } }
            }
            switch d.string(forKey: "startTab") {
            case "best": m.tab = .best; if d.bool(forKey: "runBest") { m.runBest(.KR) }
            case "watch": m.tab = .watch
            case "settings": m.tab = .settings
            case "analysis": m.tab = .analysis
            default: break
            }
        }
        .background(AppColors.bg.ignoresSafeArea())
        .scrollDismissesKeyboard(.interactively)
    }
}
