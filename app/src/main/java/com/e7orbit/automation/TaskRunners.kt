package com.e7orbit.automation

import com.e7orbit.data.SettingsRepository
import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.AutomationPhase
import com.e7orbit.model.HuntConfig
import com.e7orbit.model.HuntPhase
import com.e7orbit.model.RunConfig
import com.e7orbit.model.RunSummary
import com.e7orbit.vision.VisionConfig

internal interface ShopTaskPersistence {
    suspend fun saveConfig(config: RunConfig)
    suspend fun saveSummary(summary: RunSummary)
}

internal fun interface HuntTaskPersistence {
    suspend fun saveConfig(config: HuntConfig)
}

private class RepositoryShopTaskPersistence(
    private val repository: SettingsRepository,
) : ShopTaskPersistence {
    override suspend fun saveConfig(config: RunConfig) = repository.saveConfig(config)
    override suspend fun saveSummary(summary: RunSummary) = repository.saveSummary(summary)
}

private class RepositoryHuntTaskPersistence(
    private val repository: SettingsRepository,
) : HuntTaskPersistence {
    override suspend fun saveConfig(config: HuntConfig) = repository.saveHuntConfig(config)
}

class ShopTaskRunner internal constructor(
    private val vision: ShopVision,
    private val visionConfig: VisionConfig,
    private val persistence: ShopTaskPersistence,
    private val clock: AutomationClock = SystemAutomationClock,
    private val logger: OrbitLogger = NoOpOrbitLogger,
    private val homeNavigator: HomeNavigator? = null,
) {
    constructor(
        vision: ShopVision,
        visionConfig: VisionConfig,
        settingsRepository: SettingsRepository,
        clock: AutomationClock = SystemAutomationClock,
        logger: OrbitLogger = NoOpOrbitLogger,
        homeNavigator: HomeNavigator? = null,
    ) : this(
        vision = vision,
        visionConfig = visionConfig,
        persistence = RepositoryShopTaskPersistence(settingsRepository),
        clock = clock,
        logger = logger,
        homeNavigator = homeNavigator,
    )

    fun health(): VisionHealth = mergeHealth(vision.health(), homeNavigator?.health())

    suspend fun prepare(config: RunConfig) = persistence.saveConfig(config)

    suspend fun run(
        config: RunConfig,
        session: AutomationSession,
        onStatus: (AutomationPhase, com.e7orbit.model.RunStats, String, Double?) -> Unit,
    ): MachineResult = BookmarkStateMachine(
        vision = vision,
        visionConfig = visionConfig,
        clock = clock,
        logger = logger,
        homeNavigator = homeNavigator,
    ).run(config, session, onStatus)

    suspend fun saveSummary(summary: RunSummary) = persistence.saveSummary(summary)

    fun uiContract(phase: AutomationPhase): TaskUiContract = TaskUiContract(
        task = TaskKind.SHOP,
        step = phase.name,
        allowedPages = when (phase) {
            AutomationPhase.IDLE,
            AutomationPhase.WAITING_FOR_SERVICE,
            AutomationPhase.WAITING_FOR_SHOP,
            AutomationPhase.PAUSED,
            AutomationPhase.COMPLETED,
            AutomationPhase.ERROR,
            -> HOME_NAVIGABLE_PAGES

            AutomationPhase.SCANNING_TOP,
            AutomationPhase.SCANNING_BOTTOM,
            AutomationPhase.PURCHASING,
            -> setOf(GameUiPage.SHOP)

            AutomationPhase.VERIFYING_PURCHASE -> setOf(
                GameUiPage.SHOP_PURCHASE_CONFIRMATION,
                GameUiPage.SHOP,
            )

            AutomationPhase.REFRESHING,
            AutomationPhase.WAITING_FOR_REFRESH,
            -> setOf(GameUiPage.SHOP, GameUiPage.SHOP_REFRESH_CONFIRMATION)
        },
    )
}

class HuntTaskRunner internal constructor(
    private val vision: HuntVision,
    private val persistence: HuntTaskPersistence,
    private val clock: AutomationClock = SystemAutomationClock,
    private val logger: OrbitLogger = NoOpOrbitLogger,
    private val homeNavigator: HomeNavigator? = null,
) {
    constructor(
        vision: HuntVision,
        settingsRepository: SettingsRepository,
        clock: AutomationClock = SystemAutomationClock,
        logger: OrbitLogger = NoOpOrbitLogger,
        homeNavigator: HomeNavigator? = null,
    ) : this(
        vision = vision,
        persistence = RepositoryHuntTaskPersistence(settingsRepository),
        clock = clock,
        logger = logger,
        homeNavigator = homeNavigator,
    )

    fun health(): VisionHealth = mergeHealth(vision.health(), homeNavigator?.health())

    suspend fun prepare(config: HuntConfig) = persistence.saveConfig(config)

    suspend fun run(
        config: HuntConfig,
        session: AutomationSession,
        onStatus: (HuntPhase, com.e7orbit.model.HuntStats, String, Double?) -> Unit,
    ): HuntMachineResult = HuntStateMachine(
        vision = vision,
        clock = clock,
        logger = logger,
        homeNavigator = homeNavigator,
    ).run(config, session, onStatus)

    fun uiContract(phase: HuntPhase): TaskUiContract = TaskUiContract(
        task = TaskKind.HUNT,
        step = phase.name,
        allowedPages = when (phase) {
            HuntPhase.IDLE,
            HuntPhase.WAITING_FOR_LOBBY,
            HuntPhase.PAUSED,
            HuntPhase.COMPLETED,
            HuntPhase.ERROR,
            -> HOME_NAVIGABLE_PAGES

            HuntPhase.OPENING_BATTLE -> setOf(GameUiPage.LOBBY)
            HuntPhase.OPENING_HUNT -> setOf(GameUiPage.BATTLE_MENU)
            HuntPhase.SELECTING_BOSS,
            HuntPhase.SELECTING_DIFFICULTY,
            -> setOf(GameUiPage.HUNT_SELECTION)

            HuntPhase.DISABLING_QUICK_BATTLE ->
                setOf(GameUiPage.HUNT_TEAM_QUICK_BATTLE)

            HuntPhase.CONFIGURING_MANAGED_BATTLE,
            HuntPhase.STARTING_BATTLE,
            -> setOf(GameUiPage.HUNT_TEAM_READY)

            HuntPhase.WAITING_FOR_BATTLE_CONTROLS -> setOf(
                GameUiPage.HUNT_TEAM_READY,
                GameUiPage.HUNT_BATTLE_CONTROLS,
            )

            HuntPhase.DELEGATING_BATTLE -> setOf(GameUiPage.HUNT_BATTLE_CONTROLS)
            HuntPhase.CONFIRMING_DELEGATION ->
                setOf(GameUiPage.HUNT_DELEGATION_CONFIRMATION)

            HuntPhase.MANAGED_IN_LOBBY -> setOf(
                GameUiPage.HUNT_MANAGED_LOBBY,
                GameUiPage.HUNT_MANAGED_PANEL,
                GameUiPage.HUNT_MANAGED_COMPLETE,
            )
        },
    )
}

private fun mergeHealth(primary: VisionHealth, secondary: VisionHealth?): VisionHealth {
    secondary ?: return primary
    val missing = (primary.missingTemplateIds + secondary.missingTemplateIds).distinct()
    return VisionHealth(
        openCvReady = primary.openCvReady && secondary.openCvReady,
        loadedTemplates = primary.loadedTemplates + secondary.loadedTemplates,
        requiredTemplates = primary.requiredTemplates + secondary.requiredTemplates,
        missingTemplateIds = missing,
    )
}

private val HOME_NAVIGABLE_PAGES = NAVIGABLE_GAME_PAGES - setOf(
    GameUiPage.SHOP_PURCHASE_CONFIRMATION,
    GameUiPage.SHOP_REFRESH_CONFIRMATION,
    GameUiPage.RESOURCE_INSUFFICIENT,
    GameUiPage.HUNT_DELEGATION_CONFIRMATION,
)
