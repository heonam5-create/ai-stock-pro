import Foundation
import UserNotifications
import BackgroundTasks
import AIStockCore

/**
 iPhone 주가 알림.
 - 앱 사용 중: 시세 새로고침 때·'지금 확인' 때 즉시 판정
 - 앱 종료 후: BGAppRefreshTask (실행 시점은 iOS 가 결정 — 보통 15분 이상, 보장되지 않음)
 - 지연·오류·공급원 불일치 시세로는 알림을 보내지 않음
 - 24시간 확실한 감시는 서버 감시 + APNs 푸시가 필요 (이 앱에는 미포함, 설치 설명서 참고)
 */
enum AlertMonitor {
    static let taskId = "com.aistock.pro.alertcheck"

    static func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            schedule()
            let work = Task {
                _ = await checkNow()
                task.setTaskCompleted(success: true)
            }
            task.expirationHandler = { work.cancel() }
        }
    }

    static func schedule() {
        guard Prefs.shared.alertsEnabled, Prefs.shared.alerts.contains(where: \.enabled) else { return }
        let r = BGAppRefreshTaskRequest(identifier: taskId)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(r)
    }

    /// 등록된 알림 조건을 지금 확인. 반환: 보낸 알림 수
    @discardableResult
    static func checkNow() async -> Int {
        let prefs = Prefs.shared
        guard prefs.alertsEnabled else { return 0 }
        var all = prefs.alerts
        let active = all.filter(\.enabled)
        if active.isEmpty { return 0 }
        let repo = Repository.shared
        var sent = 0
        let now = Date()
        for (sym, rules) in Dictionary(grouping: active, by: \.symbol) {
            if Task.isCancelled { break }
            guard let q = try? await repo.quote(StockRef(symbol: sym, name: rules[0].name, nameKr: rules[0].name)), !q.unreliable else { continue }
            let bars = (try? await repo.alignedDaily(q, maxAge: 0)) ?? []
            let avg20: Double? = bars.count > 21 ? bars[(bars.count - 21)..<(bars.count - 1)].map(\.v).reduce(0, +) / 20 : nil
            let kr = q.ref.market == .KR
            for r in rules {
                guard let msg = AlertLogic.check(r, price: q.price, changePct: q.changePct, volume: q.volume, avgVol20: avg20, kr: kr, marketOpen: q.marketOpen) else { continue }
                if !AlertLogic.isOneShot(r.type) && now.timeIntervalSince(r.lastFired) < 6 * 3600 { continue }
                let c = UNMutableNotificationContent()
                c.title = "\(r.name) · \(msg)"
                c.body = "\(MarketClock.format(q.marketTime, q.ref.market)) 기준 · \(q.statusText) · \(q.source)"
                c.sound = .default
                try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "\(r.id)-\(Int(now.timeIntervalSince1970))", content: c, trigger: nil))
                sent += 1
                if let i = all.firstIndex(where: { $0.id == r.id }) { all[i].lastFired = now; if AlertLogic.isOneShot(r.type) { all[i].enabled = false } }
            }
        }
        prefs.alerts = all
        return sent
    }
}
