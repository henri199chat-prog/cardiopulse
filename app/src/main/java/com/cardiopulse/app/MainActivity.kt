package com.cardiopulse.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cardiopulse.app.core.analytics.HealthInsightsEngine
import com.cardiopulse.app.data.preferences.AppLanguage
import com.cardiopulse.app.data.preferences.UserSettings
import com.cardiopulse.app.ui.screens.bp.BloodPressureScreen
import com.cardiopulse.app.ui.screens.bp.BloodPressureViewModel
import com.cardiopulse.app.ui.screens.dashboard.DashboardScreen
import com.cardiopulse.app.ui.screens.history.HistoryScreen
import com.cardiopulse.app.ui.screens.ppg.PpgMeasureScreen
import com.cardiopulse.app.ui.screens.ppg.PpgMeasureViewModel
import com.cardiopulse.app.ui.screens.settings.SettingsScreen
import com.cardiopulse.app.ui.theme.CardioPulseTheme
import kotlinx.coroutines.launch

private enum class CardioDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    DASHBOARD("dashboard", R.string.nav_dashboard, Icons.Rounded.Dashboard),
    PULSE("pulse", R.string.nav_pulse, Icons.Rounded.Favorite),
    BP("bp", R.string.nav_bp, Icons.Rounded.HealthAndSafety),
    HISTORY("history", R.string.nav_history, Icons.Rounded.History),
    SETTINGS("settings", R.string.nav_settings, Icons.Rounded.Settings)
}

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as CardioPulseApplication).container

        setContent {
            CardioPulseTheme {
                CardioPulseRootApp(
                    container = container,
                    onApplyLocale = { lang ->
                        val locales = LocaleListCompat.forLanguageTags(lang.tag)
                        AppCompatDelegate.setApplicationLocales(locales)
                    }
                )
            }
        }
    }
}

@Composable
private fun CardioPulseRootApp(
    container: AppContainer,
    onApplyLocale: (AppLanguage) -> Unit
) {
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val settings by container.preferences.userSettings.collectAsState(initial = UserSettings())
    val allBp by container.repository.allBloodPressureRecords.collectAsState(initial = emptyList())
    val allHr by container.repository.allHeartRateRecords.collectAsState(initial = emptyList())
    val latestBp by container.repository.latestBloodPressure.collectAsState(initial = null)
    val latestHr by container.repository.latestHeartRate.collectAsState(initial = null)

    val analyticsSummary = remember(allBp, allHr, settings.guideline) {
        HealthInsightsEngine.analyze(
            bpRecords = allBp,
            hrRecords = allHr,
            guideline = settings.guideline
        )
    }

    val ppgViewModel: PpgMeasureViewModel = viewModel(
        factory = remember(container) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PpgMeasureViewModel(container.repository) as T
                }
            }
        }
    )

    val bpViewModel: BloodPressureViewModel = viewModel(
        factory = remember(container) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return BloodPressureViewModel(
                        repository = container.repository,
                        bleManager = container.bleManager,
                        preferences = container.preferences
                    ) as T
                }
            }
        }
    )

    val layoutDirection = if (settings.language == AppLanguage.ARABIC) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    fun navigateSingleTop(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    CardioDestination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = { navigateSingleTop(dest.route) },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(stringResource(dest.labelRes), maxLines = 1) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = CardioDestination.DASHBOARD.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(CardioDestination.DASHBOARD.route) {
                    DashboardScreen(
                        latestHr = latestHr,
                        latestBp = latestBp,
                        recentBp = allBp,
                        summary = analyticsSummary,
                        guideline = settings.guideline,
                        onNavigateToPulse = { navigateSingleTop(CardioDestination.PULSE.route) },
                        onNavigateToBp = { navigateSingleTop(CardioDestination.BP.route) }
                    )
                }

                composable(CardioDestination.PULSE.route) {
                    PpgMeasureScreen(viewModel = ppgViewModel)
                }

                composable(CardioDestination.BP.route) {
                    BloodPressureScreen(
                        viewModel = bpViewModel,
                        guideline = settings.guideline
                    )
                }

                composable(CardioDestination.HISTORY.route) {
                    HistoryScreen(
                        bpRecords = allBp,
                        hrRecords = allHr,
                        summary = analyticsSummary,
                        settings = settings,
                        onDeleteBp = { id -> scope.launch { container.repository.deleteBloodPressure(id) } },
                        onDeleteHr = { id -> scope.launch { container.repository.deleteHeartRate(id) } }
                    )
                }

                composable(CardioDestination.SETTINGS.route) {
                    SettingsScreen(
                        settings = settings,
                        onSelectLanguage = { lang ->
                            scope.launch { container.preferences.setLanguage(lang) }
                            onApplyLocale(lang)
                        },
                        onSelectGuideline = { g ->
                            scope.launch { container.preferences.setGuideline(g) }
                        },
                        onSavePatientName = { name ->
                            scope.launch { container.preferences.setPatientName(name) }
                        }
                    )
                }
            }
        }
    }
}
