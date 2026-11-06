package com.aistock.pro.analysis

import java.util.Locale
import kotlin.math.abs

/** 숫자 표시 형식 (원화 / 달러) */
object Fmt {
    fun price(p: Double?, kr: Boolean): String {
        if (p == null || p.isNaN()) return "-"
        return if (kr) String.format(Locale.US, "%,.0f원", p)
        else if (abs(p) < 1) String.format(Locale.US, "$%.4f", p) else String.format(Locale.US, "$%,.2f", p)
    }

    fun num(p: Double?, kr: Boolean): String {
        if (p == null || p.isNaN()) return "-"
        return if (kr) String.format(Locale.US, "%,.0f", p) else String.format(Locale.US, "%,.2f", p)
    }

    fun won(p: Double?): String = if (p == null || p.isNaN()) "-" else String.format(Locale.US, "%,.0f원", p)

    fun pct(p: Double?, sign: Boolean = true): String {
        if (p == null || p.isNaN()) return "-"
        return if (sign) String.format(Locale.US, "%+.2f%%", p) else String.format(Locale.US, "%.2f%%", p)
    }

    fun volume(v: Double?, kr: Boolean): String {
        if (v == null || v.isNaN()) return "-"
        return if (kr) when {
            v >= 1e8 -> String.format(Locale.US, "%.2f억주", v / 1e8)
            v >= 1e4 -> String.format(Locale.US, "%,.0f만주", v / 1e4)
            else -> String.format(Locale.US, "%,.0f주", v)
        } else when {
            v >= 1e9 -> String.format(Locale.US, "%.2fB", v / 1e9)
            v >= 1e6 -> String.format(Locale.US, "%.2fM", v / 1e6)
            v >= 1e3 -> String.format(Locale.US, "%.1fK", v / 1e3)
            else -> String.format(Locale.US, "%,.0f", v)
        }
    }

    fun marketCap(v: Double?, kr: Boolean): String {
        if (v == null || v.isNaN() || v <= 0) return "-"
        return if (kr) when {
            v >= 1e12 -> String.format(Locale.US, "%,.1f조원", v / 1e12)
            else -> String.format(Locale.US, "%,.0f억원", v / 1e8)
        } else when {
            v >= 1e12 -> String.format(Locale.US, "$%.2fT", v / 1e12)
            v >= 1e9 -> String.format(Locale.US, "$%.1fB", v / 1e9)
            else -> String.format(Locale.US, "$%.0fM", v / 1e6)
        }
    }
}
