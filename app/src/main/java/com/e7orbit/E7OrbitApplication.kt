package com.e7orbit

import android.app.Application
import android.content.Context
import com.e7orbit.automation.CompositeGameUiRecognizer
import com.e7orbit.automation.FileWorkflowCheckpointStore
import com.e7orbit.automation.GameUiMonitor
import com.e7orbit.automation.GlobalUiVision
import com.e7orbit.automation.HomeNavigator
import com.e7orbit.automation.HuntTaskRunner
import com.e7orbit.automation.ShopTaskRunner
import com.e7orbit.automation.TaskCoordinator
import com.e7orbit.capture.ProjectionCaptureRepository
import com.e7orbit.data.DiagnosticStore
import com.e7orbit.data.E7DataRepository
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
    lateinit var e7DataRepository: E7DataRepository
        private set
    lateinit var diagnosticStore: DiagnosticStore
        private set
    lateinit var logger: FileOrbitLogger
        private set
    lateinit var projectionCapture: ProjectionCaptureRepository
        private set
    lateinit var templateRepository: TemplateRepository
        private set
    lateinit var taskCoordinator: TaskCoordinator
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
        e7DataRepository = E7DataRepository(appContext)
        diagnosticStore = DiagnosticStore(appContext)
        templateRepository = TemplateRepository(appContext, openCvReady)
        val checkpointStore = FileWorkflowCheckpointStore(
            appContext.filesDir.resolve("automation/workflow-checkpoints.jsonl"),
        )
        val shopVision = OpenCvShopVision(templateRepository, logger)
        val huntVision = OpenCvHuntVision(templateRepository, logger)
        val globalUiVision: GlobalUiVision = shopVision
        val homeNavigator = HomeNavigator(
            vision = globalUiVision,
            logger = logger,
        )
        val shopRunner = ShopTaskRunner(
            vision = shopVision,
            visionConfig = templateRepository.config,
            settingsRepository = settingsRepository,
            logger = logger,
            homeNavigator = homeNavigator,
        )
        val huntRunner = HuntTaskRunner(
            vision = huntVision,
            settingsRepository = settingsRepository,
            logger = logger,
            homeNavigator = homeNavigator,
        )
        val uiMonitor = GameUiMonitor(
            recognizer = CompositeGameUiRecognizer(
                shopVision = shopVision,
                huntVision = huntVision,
                globalVision = globalUiVision,
            ),
        )
        taskCoordinator = TaskCoordinator(
            shopRunner = shopRunner,
            huntRunner = huntRunner,
            uiMonitor = uiMonitor,
            diagnosticStore = diagnosticStore,
            checkpointStore = checkpointStore,
            logger = logger,
            captureReady = { projectionCapture.isReady.value },
        )
        initialized = true
    }

    @Synchronized
    fun close() {
        if (!initialized) return
        taskCoordinator.shutdown()
        templateRepository.close()
        logger.close()
        initialized = false
    }
}
