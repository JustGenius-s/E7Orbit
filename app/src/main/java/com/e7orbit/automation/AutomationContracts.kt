package com.e7orbit.automation

import com.e7orbit.model.GameLocation
import com.e7orbit.model.GestureResult
import com.e7orbit.model.HuntDungeon
import com.e7orbit.model.HuntPage
import com.e7orbit.model.MatchResult
import com.e7orbit.model.PurchaseTarget
import com.e7orbit.model.RunConfig
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import com.e7orbit.model.ShopPage
import com.e7orbit.model.VisualAction

interface ScreenGateway {
    fun isTargetAppForeground(): Boolean = true
    suspend fun awaitTargetApp(timeoutMs: Long): Boolean = true
    suspend fun capture(): ScreenFrame
    suspend fun tap(point: ScreenPoint): GestureResult
    suspend fun swipe(
        from: ScreenPoint,
        to: ScreenPoint,
        durationMs: Long,
    ): GestureResult
}

internal class SwitchingScreenGateway(
    private val currentGateway: () -> ScreenGateway?,
) : ScreenGateway {
    override fun isTargetAppForeground(): Boolean = current().isTargetAppForeground()

    override suspend fun awaitTargetApp(timeoutMs: Long): Boolean =
        current().awaitTargetApp(timeoutMs)

    override suspend fun capture(): ScreenFrame = current().capture()

    override suspend fun tap(point: ScreenPoint): GestureResult = current().tap(point)

    override suspend fun swipe(
        from: ScreenPoint,
        to: ScreenPoint,
        durationMs: Long,
    ): GestureResult = current().swipe(from, to, durationMs)

    private fun current(): ScreenGateway = currentGateway()
        ?: throw ScreenCaptureException("无障碍服务当前不可用")
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

interface VisualActionVision {
    suspend fun findAction(
        frame: ScreenFrame,
        action: VisualAction,
    ): MatchResult
}

interface ShopVision : VisualActionVision {
    fun health(): VisionHealth
    suspend fun detectPage(frame: ScreenFrame): ShopPage
    suspend fun findTargets(frame: ScreenFrame, config: RunConfig): List<PurchaseTarget>
    suspend fun verifyPurchase(
        frame: ScreenFrame,
        target: PurchaseTarget,
    ): MatchResult
}

interface GlobalUiVision : VisualActionVision {
    fun navigationHealth(): VisionHealth
    suspend fun detectLocation(frame: ScreenFrame): GameLocation
}

interface HuntVision : VisualActionVision {
    fun health(): VisionHealth
    suspend fun detectPage(frame: ScreenFrame): HuntPage
    suspend fun findDungeon(frame: ScreenFrame, dungeon: HuntDungeon): MatchResult
    suspend fun isManagedBattleEnabled(frame: ScreenFrame): Boolean
    suspend fun managedProgressSignature(frame: ScreenFrame): Long
}

interface AutomationClock {
    fun elapsedRealtime(): Long
    suspend fun delay(durationMs: Long)
}

class ScreenCaptureException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
