package com.aistock.pro.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aistock.pro.MainActivity
import com.aistock.pro.R
import com.aistock.pro.analysis.Fmt
import com.aistock.pro.data.AlertRule
import com.aistock.pro.data.AlertType
import com.aistock.pro.data.Interval
import com.aistock.pro.data.Market
import com.aistock.pro.data.MarketClock
import com.aistock.pro.data.Prefs
import com.aistock.pro.data.Repository
import com.aistock.pro.data.StockRef
import java.util.concurrent.TimeUnit

/** 알림 조건 판정 (순수 로직) */
object AlertLogic {
    /** 조건 충족 시 알림 문구, 아니면 null */
    fun check(r: AlertRule, price: Double, changePct: Double?, volume: Double?, avgVol20: Double?, kr: Boolean, marketOpen: Boolean): String? {
        val p = Fmt.price(price, kr)
        val v = Fmt.price(r.value, kr)
        return when (r.type) {
            AlertType.BUY_PRICE -> if (price <= r.value) "목표 매수가 $v 도달 (현재 $p)" else null
            AlertType.SELL_PRICE -> if (price >= r.value) "목표 매도가 $v 도달 (현재 $p)" else null
            AlertType.SUPPORT_BREAK -> if (price < r.value) "지지선 $v 이탈 (현재 $p)" else null
            AlertType.RESIST_BREAK -> if (price > r.value) "저항선 $v 돌파 (현재 $p)" else null
            AlertType.STOP_LOSS -> if (price <= r.value) "⚠ 손절 기준가 $v 도달 (현재 $p)" else null
            AlertType.SURGE -> if (marketOpen && changePct != null && changePct >= r.value) "급등 ${Fmt.pct(changePct)} (현재 $p)" else null
            AlertType.PLUNGE -> if (marketOpen && changePct != null && changePct <= -r.value) "급락 ${Fmt.pct(changePct)} (현재 $p)" else null
            AlertType.VOLUME_SPIKE -> if (marketOpen && volume != null && avgVol20 != null && avgVol20 > 0 && volume >= avgVol20 * r.value)
                "거래량 급증: 20일 평균의 ${"%.1f".format(volume / avgVol20)}배 (현재 $p)" else null
        }
    }

    /** 가격 도달형은 1회 알림 후 자동 꺼짐, 급등락·거래량은 같은 날 6시간 간격 */
    fun isOneShot(t: AlertType) = t != AlertType.SURGE && t != AlertType.PLUNGE && t != AlertType.VOLUME_SPIKE
}

class AlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.alertsEnabled) return Result.success()
        val all = prefs.loadAlerts()
        val active = all.filter { it.enabled }
        if (active.isEmpty()) return Result.success()
        val repo = Repository(prefs)
        val now = System.currentTimeMillis()
        val updated = all.associateBy { it.id }.toMutableMap()

        for ((sym, rules) in active.groupBy { it.symbol }) {
            try {
                val cd = repo.chart(sym, Interval.D1, maxAgeMs = 0L)
                val ref = StockRef(sym, rules.first().name, rules.first().name)
                val q = repo.buildQuote(ref, cd)
                val bars = cd.bars
                val avg20 = if (bars.size > 21) bars.subList(bars.size - 21, bars.size - 1).map { it.v }.average() else null
                val kr = ref.market == Market.KR
                for (r in rules) {
                    val msg = AlertLogic.check(r, q.price, q.changePct, q.volume, avg20, kr, q.marketOpen) ?: continue
                    if (!AlertLogic.isOneShot(r.type) && now - r.lastFired < 6 * 3600_000L) continue
                    notify(applicationContext, r, "${r.name} · $msg", MarketClock.format(q.marketTime, ref.market) + " 기준 · ${q.statusText}")
                    updated[r.id] = r.copy(lastFired = now, enabled = !AlertLogic.isOneShot(r.type))
                }
            } catch (_: Exception) {
                // 네트워크 실패 시 다음 주기에 재시도
            }
        }
        prefs.saveAlerts(all.map { updated[it.id] ?: it })
        return Result.success()
    }

    companion object {
        const val CHANNEL = "price_alerts"

        fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CHANNEL, "주가 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "목표가·손절가·급등락·거래량 급증 알림"
                }
                ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
            }
        }

        fun notify(ctx: Context, r: AlertRule, title: String, text: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("symbol", r.symbol); putExtra("name", r.name)
            }
            val pi = PendingIntent.getActivity(ctx, r.id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            try { NotificationManagerCompat.from(ctx).notify(r.id.toInt(), n) } catch (_: SecurityException) { }
        }
    }
}

object AlertScheduler {
    private const val NAME = "price_alert_periodic"

    fun apply(ctx: Context, prefs: Prefs) {
        val wm = WorkManager.getInstance(ctx)
        if (!prefs.alertsEnabled || prefs.loadAlerts().none { it.enabled }) {
            wm.cancelUniqueWork(NAME); return
        }
        val minutes = prefs.alertIntervalMin.coerceAtLeast(15).toLong()   // 안드로이드 최소 주기 15분
        val req = PeriodicWorkRequestBuilder<AlertWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueue(
            OneTimeWorkRequestBuilder<AlertWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }
}
