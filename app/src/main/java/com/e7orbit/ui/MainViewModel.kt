package com.e7orbit.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.e7orbit.AppGraph
import com.e7orbit.model.AutomationStatus
import com.e7orbit.model.E7_CN_PACKAGE
import com.e7orbit.model.REFERENCE_HEIGHT
import com.e7orbit.model.REFERENCE_WIDTH
import com.e7orbit.model.RunConfig
import com.e7orbit.model.RunSummary
import com.e7orbit.service.E7AccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EnvironmentStatus(
    val accessibilityEnabled: Boolean = false,
    val gameInstalled: Boolean = false,
    val resolutionReady: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val projectionReady: Boolean = false,
    val openCvReady: Boolean = false,
    val templatesReady: Boolean = false,
    val missingTemplates: List<String> = emptyList(),
) {
    val canPrepare: Boolean
        get() = accessibilityEnabled &&
            gameInstalled &&
            resolutionReady &&
            openCvReady &&
            templatesReady
}

data class MainUiState(
    val config: RunConfig = RunConfig(),
    val automation: AutomationStatus = AutomationStatus(),
    val environment: EnvironmentStatus = EnvironmentStatus(),
    val lastSummary: RunSummary = RunSummary(),
)

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val runtime = AppGraph.automationRuntime
    private val settings = AppGraph.settingsRepository
    private val draftConfig = MutableStateFlow(RunConfig())
    private val environment = MutableStateFlow(readEnvironment())

    val uiState: StateFlow<MainUiState> = combine(
        draftConfig,
        runtime.status,
        environment,
        settings.lastSummary,
    ) { config, automation, environmentStatus, lastSummary ->
        MainUiState(
            config = config,
            automation = automation,
            environment = environmentStatus,
            lastSummary = lastSummary,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MainUiState(),
    )

    init {
        viewModelScope.launch {
            settings.config.collect { saved -> draftConfig.value = saved }
        }
        runtime.refreshHealth()
    }

    fun refreshEnvironment() {
        runtime.refreshHealth()
        environment.value = readEnvironment()
    }

    fun setBuyCovenant(enabled: Boolean) {
        draftConfig.value = draftConfig.value.copy(buyCovenantBookmarks = enabled)
    }

    fun setBuyMystic(enabled: Boolean) {
        draftConfig.value = draftConfig.value.copy(buyMysticMedals = enabled)
    }

    fun setMaxRefreshes(value: Int) {
        draftConfig.value = draftConfig.value.copy(maxRefreshes = value.coerceIn(1, 10_000))
    }

    fun setMatchThreshold(value: Double) {
        draftConfig.value = draftConfig.value.copy(
            matchThreshold = value.coerceIn(0.75, 0.99),
        )
    }

    fun prepareRun() {
        refreshEnvironment()
        viewModelScope.launch {
            val config = draftConfig.value.normalized()
            runtime.start(config)
            if (runtime.status.value.isRunning) {
                launchGame()
            }
        }
    }

    fun pauseOrResume() {
        if (runtime.status.value.phase == com.e7orbit.model.AutomationPhase.PAUSED) {
            runtime.resume()
        } else {
            runtime.pause()
        }
    }

    fun stop() = runtime.stop()

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(intent)
    }

    private fun launchGame() {
        val context = getApplication<Application>()
        val intent = context.packageManager.getLaunchIntentForPackage(E7_CN_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private fun readEnvironment(): EnvironmentStatus {
        val context = getApplication<Application>()
        val bounds = context.getSystemService(WindowManager::class.java)
            .maximumWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
        val expectedService = ComponentName(context, E7AccessibilityService::class.java)
        val accessibilityEnabled = accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service ->
                val info = service.resolveInfo.serviceInfo
                ComponentName(info.packageName, info.name) == expectedService
            }
        val gameInstalled = try {
            context.packageManager.getApplicationInfo(E7_CN_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        val visionHealth = AppGraph.templateRepository.health()
        return EnvironmentStatus(
            accessibilityEnabled = accessibilityEnabled,
            gameInstalled = gameInstalled,
            resolutionReady =
                width == REFERENCE_WIDTH && height == REFERENCE_HEIGHT,
            width = width,
            height = height,
            projectionReady = AppGraph.projectionCapture.isReady.value,
            openCvReady = AppGraph.openCvReady,
            templatesReady = visionHealth.isReady,
            missingTemplates = visionHealth.missingTemplateIds,
        )
    }
}
