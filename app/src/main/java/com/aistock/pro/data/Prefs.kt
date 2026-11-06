package com.aistock.pro.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class WatchItem(
    val symbol: String,
    val name: String,
    val qty: Double = 0.0,
    val avgPrice: Double = 0.0,
    val targetPrice: Double? = null,
    val stopPrice: Double? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val market: Market get() = if (symbol.endsWith(".KS") || symbol.endsWith(".KQ")) Market.KR else Market.US
}

enum class AlertType(val label: String, val needsValue: Boolean, val unit: String) {
    BUY_PRICE("목표 매수가 도달 (이하)", true, "가격"),
    SELL_PRICE("목표 매도가 도달 (이상)", true, "가격"),
    SUPPORT_BREAK("지지선 이탈 (이하)", true, "가격"),
    RESIST_BREAK("저항선 돌파 (이상)", true, "가격"),
    STOP_LOSS("손절 기준가 도달 (이하)", true, "가격"),
    SURGE("급등 (등락률 이상)", true, "%"),
    PLUNGE("급락 (등락률 이하, 양수로 입력)", true, "%"),
    VOLUME_SPIKE("거래량 급증 (20일 평균 대비 배수)", true, "배"),
}

data class AlertRule(
    val id: Long,
    val symbol: String,
    val name: String,
    val type: AlertType,
    val value: Double,
    val enabled: Boolean = true,
    val lastFired: Long = 0L,
)

/** 기기 보안칩(Android Keystore) AES-GCM 으로 API 키 암호화 저장 — 코드에 키를 하드코딩하지 않음 */
class SecureStore(context: Context) {
    private val sp = context.getSharedPreferences("aistock_secure", Context.MODE_PRIVATE)
    private val alias = "aistock_api_keys"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        if (value.isBlank()) { sp.edit().remove(name).apply(); return }
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val enc = c.doFinal(value.trim().toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
            sp.edit().putString(name, packed).apply()
        } catch (_: Exception) { /* 보안칩 오류 시 저장하지 않음 */ }
    }

    fun get(name: String): String {
        val packed = sp.getString(name, null) ?: return ""
        return try {
            val (iv, data) = packed.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(data), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }
}

/** 관심종목·알림·설정 저장 (앱 업데이트 후에도 유지) */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("aistock_prefs", Context.MODE_PRIVATE)
    val secure = SecureStore(context)

    // ── 관심 종목 ──
    fun loadWatchlist(): List<WatchItem> = try {
        val arr = JSONArray(sp.getString("watchlist", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            WatchItem(
                symbol = o.getString("symbol"), name = o.optString("name"),
                qty = o.optDouble("qty", 0.0), avgPrice = o.optDouble("avg", 0.0),
                targetPrice = if (o.has("target")) o.optDouble("target") else null,
                stopPrice = if (o.has("stop")) o.optDouble("stop") else null,
                addedAt = o.optLong("added", 0L),
            )
        }
    } catch (_: Exception) { emptyList() }

    fun saveWatchlist(list: List<WatchItem>) {
        val arr = JSONArray()
        list.forEach { w ->
            arr.put(JSONObject().apply {
                put("symbol", w.symbol); put("name", w.name); put("qty", w.qty); put("avg", w.avgPrice)
                w.targetPrice?.let { put("target", it) }; w.stopPrice?.let { put("stop", it) }; put("added", w.addedAt)
            })
        }
        sp.edit().putString("watchlist", arr.toString()).apply()
    }

    // ── 알림 ──
    fun loadAlerts(): List<AlertRule> = try {
        val arr = JSONArray(sp.getString("alerts", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val type = runCatching { AlertType.valueOf(o.getString("type")) }.getOrNull() ?: return@mapNotNull null
            AlertRule(o.getLong("id"), o.getString("symbol"), o.optString("name"), type,
                o.optDouble("value", 0.0), o.optBoolean("enabled", true), o.optLong("last", 0L))
        }
    } catch (_: Exception) { emptyList() }

    fun saveAlerts(list: List<AlertRule>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id); put("symbol", a.symbol); put("name", a.name); put("type", a.type.name)
                put("value", a.value); put("enabled", a.enabled); put("last", a.lastFired)
            })
        }
        sp.edit().putString("alerts", arr.toString()).apply()
    }

    // ── 최근 본 종목 ──
    fun loadRecent(): List<StockRef> = try {
        val arr = JSONArray(sp.getString("recent", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            StockRef(o.getString("s"), o.optString("n"), o.optString("k").ifBlank { null }, o.optString("e"))
        }
    } catch (_: Exception) { emptyList() }

    fun addRecent(ref: StockRef) {
        val list = (listOf(ref) + loadRecent().filter { it.symbol != ref.symbol }).take(10)
        val arr = JSONArray()
        list.forEach { r -> arr.put(JSONObject().apply { put("s", r.symbol); put("n", r.name); put("k", r.nameKr ?: ""); put("e", r.exchange) }) }
        sp.edit().putString("recent", arr.toString()).apply()
    }

    // ── 설정 ──
    var alertsEnabled: Boolean
        get() = sp.getBoolean("alerts_enabled", true)
        set(v) = sp.edit().putBoolean("alerts_enabled", v).apply()

    var alertIntervalMin: Int
        get() = sp.getInt("alert_interval", 15)
        set(v) = sp.edit().putInt("alert_interval", v).apply()

    var includeWatchInBest: Boolean
        get() = sp.getBoolean("best_include_watch", true)
        set(v) = sp.edit().putBoolean("best_include_watch", v).apply()

    var aiEndpoint: String
        get() = sp.getString("ai_endpoint", "") ?: ""
        set(v) = sp.edit().putString("ai_endpoint", v.trim()).apply()

    var aiModel: String
        get() = sp.getString("ai_model", "") ?: ""
        set(v) = sp.edit().putString("ai_model", v.trim()).apply()

    var finnhubKey: String
        get() = secure.get("finnhub")
        set(v) = secure.put("finnhub", v)

    var dataGoKrKey: String
        get() = secure.get("datagokr")
        set(v) = secure.put("datagokr", v)

    var aiKey: String
        get() = secure.get("ai_key")
        set(v) = secure.put("ai_key", v)
}
