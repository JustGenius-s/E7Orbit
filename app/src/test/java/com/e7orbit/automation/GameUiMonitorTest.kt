package com.e7orbit.automation

import com.e7orbit.model.GestureResult
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameUiMonitorTest {
    @Test
    fun publishesOnlyAfterTwoContinuousRecognitions() = runTest {
        val monitor = monitor(
            recognizer = SequenceRecognizer { GameUiPage.SHOP },
        )
        val gateway = CountingCaptureGateway()

        monitor.attachGateway(gateway)
        runCurrent()

        assertFalse(monitor.state.value.isStable)
        assertEquals(GameUiPage.SHOP, monitor.state.value.candidatePage)
        assertEquals(1, gateway.captures)

        advanceTimeBy(POLL_INTERVAL_MS)
        runCurrent()

        assertTrue(monitor.state.value.isStable)
        assertEquals(GameUiPage.SHOP, monitor.state.value.page)
        assertEquals(2, gateway.captures)
        monitor.shutdown()
    }

    @Test
    fun waitsWithoutCapturingWhenGameIsNotForeground() = runTest {
        val monitor = monitor(
            recognizer = SequenceRecognizer { GameUiPage.SHOP },
        )
        val gateway = CountingCaptureGateway(foreground = false)

        monitor.attachGateway(gateway)
        runCurrent()

        assertEquals(UiMonitorStatus.WAITING_FOR_GAME, monitor.state.value.status)
        assertEquals(0, gateway.captures)
        monitor.shutdown()
    }

    @Test
    fun ignoresRecognitionCompletedByReplacedGateway() = runTest {
        val monitor = monitor(
            recognizer = SequenceRecognizer { frame ->
                if (frame.sequence == 1L) GameUiPage.SHOP else GameUiPage.LOBBY
            },
        )
        val delayedFrame = CompletableDeferred<ScreenFrame>()
        val staleGateway = DeferredCaptureGateway(delayedFrame)
        val currentGateway = CountingCaptureGateway(firstSequence = 2L)

        monitor.attachGateway(staleGateway)
        runCurrent()
        monitor.attachGateway(currentGateway)
        delayedFrame.complete(frame(sequence = 1L))
        runCurrent()

        assertEquals(GameUiPage.LOBBY, monitor.state.value.candidatePage)
        assertEquals(2L, monitor.state.value.frameSequence)
        assertFalse(monitor.state.value.isStable)

        advanceTimeBy(POLL_INTERVAL_MS)
        runCurrent()

        assertEquals(GameUiPage.LOBBY, monitor.state.value.page)
        assertTrue(monitor.state.value.isStable)
        monitor.shutdown()
    }

    private fun TestScope.monitor(recognizer: GameUiRecognizer): GameUiMonitor = GameUiMonitor(
        recognizer = recognizer,
        clock = SchedulerClock(this),
        dispatcher = StandardTestDispatcher(testScheduler),
        activePollIntervalMs = POLL_INTERVAL_MS,
        idlePollIntervalMs = POLL_INTERVAL_MS,
    )

    private class SequenceRecognizer(
        private val page: (ScreenFrame) -> GameUiPage,
    ) : GameUiRecognizer {
        override fun health(): VisionHealth = VisionHealth(
            openCvReady = true,
            loadedTemplates = 1,
            requiredTemplates = 1,
            missingTemplateIds = emptyList(),
        )

        override suspend fun recognize(frame: ScreenFrame): UiRecognition =
            UiRecognition(page(frame), 1.0)
    }

    private class CountingCaptureGateway(
        private val foreground: Boolean = true,
        private val firstSequence: Long = 1L,
    ) : ScreenGateway {
        var captures = 0

        override fun isTargetAppForeground(): Boolean = foreground

        override suspend fun capture(): ScreenFrame = frame(
            sequence = firstSequence + captures++,
        )

        override suspend fun tap(point: ScreenPoint): GestureResult = GestureResult.COMPLETED

        override suspend fun swipe(
            from: ScreenPoint,
            to: ScreenPoint,
            durationMs: Long,
        ): GestureResult = GestureResult.COMPLETED
    }

    private class DeferredCaptureGateway(
        private val result: CompletableDeferred<ScreenFrame>,
    ) : ScreenGateway {
        override suspend fun capture(): ScreenFrame = result.await()

        override suspend fun tap(point: ScreenPoint): GestureResult = GestureResult.COMPLETED

        override suspend fun swipe(
            from: ScreenPoint,
            to: ScreenPoint,
            durationMs: Long,
        ): GestureResult = GestureResult.COMPLETED
    }

    private class SchedulerClock(
        private val scope: TestScope,
    ) : AutomationClock {
        override fun elapsedRealtime(): Long = scope.testScheduler.currentTime

        override suspend fun delay(durationMs: Long) {
            kotlinx.coroutines.delay(durationMs)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 100L

        fun frame(sequence: Long) = ScreenFrame(
            bitmap = null,
            width = 1920,
            height = 1080,
            capturedAtElapsedMs = sequence,
            sequence = sequence,
        )
    }
}
