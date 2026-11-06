package com.aistock.pro.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aistock.pro.ui.screens.AnalysisScreen
import com.aistock.pro.ui.screens.Best5Screen
import com.aistock.pro.ui.screens.HomeScreen
import com.aistock.pro.ui.screens.SettingsScreen
import com.aistock.pro.ui.screens.WatchlistScreen
import com.aistock.pro.ui.theme.AppColors

@Composable
fun AppRoot(vm: MainViewModel) {
    val snack = remember { SnackbarHostState() }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(vm.lastError) {
        vm.lastError?.let { snack.showSnackbar(it); vm.lastError = null }
    }

    Scaffold(
        containerColor = AppColors.Bg,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = AppColors.Card, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = vm.tab == t,
                        onClick = { vm.tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.HOME -> Icons.Filled.Home
                                    Tab.ANALYSIS -> Icons.Filled.Search
                                    Tab.BEST -> Icons.Filled.Star
                                    Tab.WATCH -> Icons.Filled.Favorite
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                }, contentDescription = t.label,
                            )
                        },
                        label = { Text(t.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppColors.Accent, selectedTextColor = AppColors.Accent,
                            indicatorColor = AppColors.Card2, unselectedIconColor = AppColors.Sub, unselectedTextColor = AppColors.Sub,
                        ),
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (vm.tab) {
                Tab.HOME -> HomeScreen(vm)
                Tab.ANALYSIS -> AnalysisScreen(vm)
                Tab.BEST -> Best5Screen(vm)
                Tab.WATCH -> WatchlistScreen(vm)
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

