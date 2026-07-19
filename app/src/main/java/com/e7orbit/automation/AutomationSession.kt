package com.e7orbit.automation

import com.e7orbit.logging.NoOpOrbitLogger
import com.e7orbit.logging.OrbitLogger
import com.e7orbit.model.ScreenFrame
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/**
 * Per-run services shared by workflow steps and legacy state-machine code.
 *
 * The session deliberately does not own a CoroutineScope. Runtime lifecycle remains
 * outside it, while pause gates, diagnostics, gesture receipts and checkpoints use
 * one consistent execution context.
 */
class AutomationSession(
    val gateway: ScreenGateway,
    val clock: AutomationClock,
    awaitRunPermission: suspend () -> Unit,
    private val onDiagnostic: suspend (ScreenFrame, String) -> Unit,
    internal val logger: OrbitLogger = NoOpOrbitLogger,
    private val checkpointStore: WorkflowCheckpointStore = InMemoryWorkflowCheckpointStore(),
) {
    val sessionId: Long = nextSessionId.incrementAndGet()
    val runId: String = UUID.randomUUID().toString()

    private val latestGestureReceipt = AtomicReference<GestureReceipt?>()

    val executor: OperationExecutor = OperationExecutor(
        gateway = gateway,
        clock = clock,
        awaitRunPermission = awaitRunPermission,
        onDiagnostic = ::saveDiagnostic,
        onGestureReceipt = latestGestureReceipt::set,
        logger = logger,
    )

    suspend fun awaitActive() = executor.awaitActive()

    suspend fun diagnose(reason: String) = executor.diagnose(reason)

    suspend fun saveDiagnostic(
        frame: ScreenFrame,
        reason: String,
    ) = onDiagnostic(frame, reason)

    fun latestGestureReceipt(): GestureReceipt? = latestGestureReceipt.get()

    internal fun markLatestGestureReconciled(
        stepId: String,
        previousToken: GestureToken?,
    ) {
        while (true) {
            val current = latestGestureReceipt.get()
                ?.takeIf(GestureReceipt::effectMayBeUncertain)
                ?: return
            if (current.token == previousToken) return
            val reconciled = current.copy(
                outcome = GestureOutcome.RECONCILED,
                recordedAtElapsedMs = clock.elapsedRealtime(),
                detail = "reconciled_by:$stepId",
            )
            if (latestGestureReceipt.compareAndSet(current, reconciled)) return
        }
    }

    internal suspend fun recordCheckpoint(checkpoint: WorkflowCheckpoint) {
        try {
            val recorded = checkpoint.copy(
                runId = runId,
                sessionId = sessionId,
                gestureToken = latestGestureReceipt.get()?.token,
            )
            checkpointStore.record(recorded)
            logger.debug(
                "workflow.checkpoint",
                "session" to sessionId,
                "runId" to runId,
                "workflow" to recorded.workflowId,
                "step" to recorded.stepId,
                "state" to recorded.state,
                "attempt" to recorded.attempt,
                "gesture" to recorded.gestureToken?.value,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.error(
                "workflow.checkpoint.save_failed",
                error,
                "workflow" to checkpoint.workflowId,
                "step" to checkpoint.stepId,
                "state" to checkpoint.state,
            )
        }
    }

    suspend fun checkpointHistory(): List<WorkflowCheckpoint> =
        checkpointStore.history(runId)

    private companion object {
        val nextSessionId = AtomicLong(0L)
    }
}

class AutomationSessionManager(
    private val coordinator: AutomationRunCoordinator = AutomationRunCoordinator(),
    private val checkpointStore: WorkflowCheckpointStore = InMemoryWorkflowCheckpointStore(),
) {
    fun tryOpen(
        kind: AutomationKind,
        gateway: ScreenGateway,
        clock: AutomationClock,
        awaitRunPermission: suspend () -> Unit,
        onDiagnostic: suspend (ScreenFrame, String) -> Unit,
        logger: OrbitLogger = NoOpOrbitLogger,
    ): ManagedAutomationSession? {
        val lease = coordinator.tryAcquire(kind) ?: return null
        return ManagedAutomationSession(
            lease = lease,
            session = AutomationSession(
                gateway = gateway,
                clock = clock,
                awaitRunPermission = awaitRunPermission,
                onDiagnostic = onDiagnostic,
                logger = logger,
                checkpointStore = checkpointStore,
            ),
            releaseLease = coordinator::release,
        )
    }

    fun activeKind(): AutomationKind? = coordinator.activeKind()
}

class ManagedAutomationSession internal constructor(
    val lease: RunLease,
    val session: AutomationSession,
    private val releaseLease: (RunLease) -> Boolean,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun release(): Boolean =
        closed.compareAndSet(false, true) && releaseLease(lease)

    override fun close() {
        release()
    }
}
