package com.e7orbit.automation

import com.e7orbit.model.AutomationStatus
import com.e7orbit.model.GestureResult
import com.e7orbit.model.HuntDungeon
import com.e7orbit.model.HuntPage
import com.e7orbit.model.MatchResult
import com.e7orbit.model.PurchaseTarget
import com.e7orbit.model.RunConfig
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.model.ShopAction
import com.e7orbit.model.ShopPage
import kotlinx.coroutines.flow.StateFlow

interface ScreenGateway {
    suspend fun capture(): ScreenFrame
    suspend fun tap(point: ScreenPoint): GestureResult
    suspend fun swipe(
        from: ScreenPoint,
        to: ScreenPoint,
        durationMs: Long,
    ): GestureResult
}

data class VisionHealth(
    val openCvReady: Boolean,
    val loadedTemplates: Int,
    val requiredTemplates: Int,
    val missingTemplateIds: List<String>,
) {
    val isReady: Boolean
        get() = openCvReady && missingTemplateIds.isEmpty()
}

interface ShopVision {
    fun health(): VisionHealth
    suspend fun detectPage(frame: ScreenFrame): ShopPage
    suspend fun findTargets(frame: ScreenFrame, config: RunConfig): List<PurchaseTarget>
    suspend fun verifyPurchase(
        frame: ScreenFrame,
        target: PurchaseTarget,
    ): MatchResult

    suspend fun findAction(
        frame: ScreenFrame,
        action: ShopAction,
    ): MatchResult
}

interface HuntVision {
    fun health(): VisionHealth
    suspend fun detectPage(frame: ScreenFrame): HuntPage
    suspend fun findDungeon(frame: ScreenFrame, dungeon: HuntDungeon): MatchResult
    suspend fun isManagedBattleEnabled(frame: ScreenFrame): Boolean
    suspend fun managedProgressSignature(frame: ScreenFrame): Long
}

interface AutomationController {
    val status: StateFlow<AutomationStatus>
    suspend fun start(config: RunConfig)
    fun pause()
    fun resume()
    fun stop()
}

interface AutomationClock {
    fun elapsedRealtime(): Long
    suspend fun delay(durationMs: Long)
}

class ScreenCaptureException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
