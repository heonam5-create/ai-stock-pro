package com.aistock.pro

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.aistock.pro.ui.AppRoot
import com.aistock.pro.ui.MainViewModel
import com.aistock.pro.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { AppTheme { AppRoot(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** 알림을 눌러 들어온 경우 해당 종목 분석 화면으로 이동 */
    private fun handle(intent: Intent?) {
        val sym = intent?.getStringExtra("symbol") ?: return
        vm.openSymbol(sym, intent.getStringExtra("name"))
        intent.removeExtra("symbol")
    }
}
