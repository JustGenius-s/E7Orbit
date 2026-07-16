package com.e7orbit

import android.app.Application
import android.content.Context
import com.e7orbit.automation.AutomationRuntime
import com.e7orbit.automation.AutomationRunCoordinator
import com.e7orbit.automation.HuntRuntime
import com.e7orbit.capture.ProjectionCaptureRepository
import com.e7orbit.data.DiagnosticStore
import com.e7orbit.data.SettingsRepository
import com.e7orbit.logging.FileOrbitLogger
import com.e7orbit.vision.OpenCvShopVision
import com.e7orbit.vision.OpenCvHuntVision
import com.e7orbit.vision.TemplateRepository
import org.opencv.android.OpenCVLoader

class E7OrbitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(this)
    }

    override fun onTerminate() {
        AppGraph.close()
        super.onTerminate()
    }
}

object AppGraph {
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var diagnosticStore: DiagnosticStore
        private set
    lateinit var logger: FileOrbitLogger
        private set
    lateinit var projectionCapture: ProjectionCaptureRepository
        private set
    lateinit var templateRepository: TemplateRepository
        private set
    lateinit var automationRuntime: AutomationRuntime
        private set
    lateinit var huntRuntime: HuntRuntime
        private set

    var openCvReady: Boolean = false
        private set

    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        logger = FileOrbitLogger(appContext)
        projectionCapture = ProjectionCaptureRepository()
        openCvReady = OpenCVLoader.initLocal()
        logger.info("app.initialize", "openCvReady" to openCvReady)
        settingsRepository = SettingsRepository(appContext)
        diagnosticStore = DiagnosticStore(appContext)
        templateRepository = TemplateRepository(appContext, openCvReady)
        val runCoordinator = AutomationRunCoordinator()
        val vision = OpenCvShopVision(templateRepository, logger)
        automationRuntime = AutomationRuntime(
            vision = vision,
            visionConfig = templateRepository.config,
            settingsRepository = settingsRepository,
            diagnosticStore = diagnosticStore,
            logger = logger,
            captureReady = { projectionCapture.isReady.value },
            runCoordinator = runCoordinator,
        )
        huntRuntime = HuntRuntime(
            vision = OpenCvHuntVision(templateRepository, logger),
            visionConfig = templateRepository.config,
            settingsRepository = settingsRepository,
            diagnosticStore = diagnosticStore,
            logger = logger,
            captureReady = { projectionCapture.isReady.value },
            runCoordinator = runCoordinator,
        )
        initialized = true
    }

    @Synchronized
    fun close() {
        if (!initialized) return
        automationRuntime.shutdown()
        huntRuntime.shutdown()
        templateRepository.close()
        logger.close()
        initialized = false
    }
}
