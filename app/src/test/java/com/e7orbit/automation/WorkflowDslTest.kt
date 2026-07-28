package com.e7orbit.automation

import com.e7orbit.model.GestureResult
import com.e7orbit.model.ScreenFrame
import com.e7orbit.model.ScreenPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDslTest {
    @Test
    fun stageDefaultsAndExplicitRecoveryDriveSafeRetry() = runTest {
        val clock = WorkflowClock()
        val session = session(clock = clock)
        var attempts = 0
        val workflow = workflow<Unit>("retry_workflow") {
            defaults {
                maxAttempts = 2
                retryDelayMs = 25L
                diagnoseOnFailure = false
            }
            stage("navigation") {
                step("open", effectSafety = EffectSafety.IDEMPOTENT) {
                    execute {
                        attempts += 1
                        if (attempts == 1) error("transient")
                    }
                    recover { StepRecovery.RetrySafe }
                }
            }
        }

        val result = workflow.run(Unit, session, runKey = "cycle-7")
        val checkpoints = session.checkpointHistory()

        assertFalse(result.completedEarly)
        assertEquals("cycle-7", result.runKey)
        assertEquals(1, result.completedSteps)
        assertEquals(2, attempts)
        assertEquals(25L, clock.now)
        assertEquals(
            listOf(
                WorkflowCheckpointState.STARTED,
                WorkflowCheckpointState.RETRYING,
                WorkflowCheckpointState.STARTED,
                WorkflowCheckpointState.SUCCEEDED,
            ),
            checkpoints.map(WorkflowCheckpoint::state),
        )
        assertTrue(checkpoints.all { it.workflowId == "retry_workflow" })
        assertTrue(checkpoints.all { it.runKey == "cycle-7" })
        assertTrue(checkpoints.all { it.runId == session.runId })
        assertTrue(checkpoints.all { it.stepId == "navigation.open" })
        assertTrue(checkpoints.all { it.effectSafety == EffectSafety.IDEMPOTENT })
    }

    @Test
    fun completeWorkflowSkipsRemainingSteps() = runTest {
        val session = session()
        var secondStepRan = false
        val workflow = workflow<Unit>("early_complete") {
            step("detect") {
                execute { completeWorkflow() }
            }
            step("unreachable") {
                execute { secondStepRan = true }
            }
        }

        val result = workflow.run(Unit, session)

        assertTrue(result.completedEarly)
        assertFalse(secondStepRan)
        assertEquals(1, result.completedSteps)
    }

    @Test
    fun checkpointCapturesLatestGestureToken() = runTest {
        val session = session()
        val workflow = workflow<Unit>("gesture_workflow") {
            step("tap", effectSafety = EffectSafety.RECONCILIATION_REQUIRED) {
                execute {
                    session.executor.tap(
                        operationId = "gesture.tap",
                        point = ScreenPoint(1, 1),
                        policy = OperationPolicy.reconciliationRequired(),
                    )
                }
            }
        }

        workflow.run(Unit, session)
        val checkpoint = session.checkpointHistory().last()

        assertEquals(WorkflowCheckpointState.SUCCEEDED, checkpoint.state)
        assertNotNull(checkpoint.gestureToken)
        assertEquals(checkpoint.gestureToken, session.latestGestureReceipt()?.token)
        assertEquals(GestureOutcome.COMPLETED, session.latestGestureReceipt()?.outcome)
    }

    @Test
    fun recoveredStepMarksUncertainGestureAsReconciled() = runTest {
        val session = session(gateway = CancelledWorkflowGateway)
        val workflow = workflow<Unit>("reconcile_workflow") {
            step("critical", effectSafety = EffectSafety.RECONCILIATION_REQUIRED) {
                execute {
                    session.executor.tap(
                        operationId = "critical.tap",
                        point = ScreenPoint(1, 1),
                        policy = OperationPolicy.reconciliationRequired(),
                    )
                }
                recover { StepRecovery.Recovered }
            }
        }

        workflow.run(Unit, session)

        assertEquals(GestureOutcome.RECONCILED, session.latestGestureReceipt()?.outcome)
        assertFalse(session.latestGestureReceipt()?.effectMayBeUncertain ?: true)
        assertEquals(
            WorkflowCheckpointState.RECOVERED,
            session.checkpointHistory().last().state,
        )
    }

    @Test
    fun recoveredReadOnlyStepDoesNotReconcilePreviousStepGesture() = runTest {
        val session = session(gateway = CancelledWorkflowGateway)
        val workflow = workflow<Unit>("step_receipt_boundary") {
            step("uncertain_gesture", effectSafety = EffectSafety.RECONCILIATION_REQUIRED) {
                execute {
                    try {
                        session.executor.tap(
                            operationId = "previous.tap",
                            point = ScreenPoint(1, 1),
                            policy = OperationPolicy.reconciliationRequired(),
                        )
                    } catch (error: OperationExecutionException) {
                        assertEquals(
                            ExecutionFailureKind.UNCERTAIN_EFFECT,
                            error.failure.kind,
                        )
                    }
                }
            }
            step("read_only") {
                execute { error("read failed") }
                recover { StepRecovery.Recovered }
            }
        }

        workflow.run(Unit, session)

        assertEquals(GestureOutcome.CANCELLED, session.latestGestureReceipt()?.outcome)
        assertTrue(session.latestGestureReceipt()?.effectMayBeUncertain ?: false)
        assertEquals(
            listOf(
                WorkflowCheckpointState.STARTED,
                WorkflowCheckpointState.SUCCEEDED,
                WorkflowCheckpointState.STARTED,
                WorkflowCheckpointState.RECOVERED,
            ),
            session.checkpointHistory().map(WorkflowCheckpoint::state),
        )
    }

    private fun session(
        clock: WorkflowClock = WorkflowClock(),
        gateway: ScreenGateway = WorkflowGateway,
    ) = AutomationSession(
        gateway = gateway,
        uiStateSource = TestGameUiStateSource(),
        clock = clock,
        awaitRunPermission = {},
        onDiagnostic = { _, _ -> },
    )

    private object WorkflowGateway : ScreenGateway {
        override suspend fun capture(): ScreenFrame = ScreenFrame(
            bitmap = null,
            width = 1920,
            height = 1080,
            capturedAtElapsedMs = 0L,
            sequence = 0L,
        )

        override suspend fun tap(point: ScreenPoint): GestureResult =
            GestureResult.COMPLETED

        override suspend fun swipe(
            from: ScreenPoint,
            to: ScreenPoint,
            durationMs: Long,
        ): GestureResult = GestureResult.COMPLETED
    }

    private object CancelledWorkflowGateway : ScreenGateway {
        override suspend fun capture(): ScreenFrame = WorkflowGateway.capture()

        override suspend fun tap(point: ScreenPoint): GestureResult =
            GestureResult.CANCELLED

        override suspend fun swipe(
            from: ScreenPoint,
            to: ScreenPoint,
            durationMs: Long,
        ): GestureResult = GestureResult.CANCELLED
    }

    private class WorkflowClock : AutomationClock {
        var now = 0L

        override fun elapsedRealtime(): Long = now

        override suspend fun delay(durationMs: Long) {
            now += durationMs
        }
    }
}
