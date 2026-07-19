package com.e7orbit.automation

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException

@DslMarker
annotation class AutomationWorkflowDsl

sealed interface Observation<out T> {
    data class Confirmed<T>(
        val value: T,
        val confidence: Double? = null,
    ) : Observation<T>

    data class Absent(
        val reason: String? = null,
    ) : Observation<Nothing>

    data class Uncertain(
        val reason: String,
        val cause: Throwable? = null,
    ) : Observation<Nothing>
}

fun interface Observer<C, T> {
    suspend fun observe(
        context: C,
        session: AutomationSession,
    ): Observation<T>
}

class ObservationException(
    val observation: Observation<*>,
    message: String,
) : RuntimeException(message)

enum class WorkflowCheckpointState {
    STARTED,
    RETRYING,
    SUCCEEDED,
    RECOVERED,
    FAILED,
}

data class WorkflowCheckpoint(
    val workflowId: String,
    val stepId: String,
    val state: WorkflowCheckpointState,
    val attempt: Int,
    val recordedAtElapsedMs: Long,
    val effectSafety: EffectSafety = EffectSafety.READ_ONLY,
    val sessionId: Long = 0L,
    val gestureToken: GestureToken? = null,
    val message: String? = null,
)

interface WorkflowCheckpointStore {
    suspend fun record(checkpoint: WorkflowCheckpoint)
    suspend fun history(sessionId: Long): List<WorkflowCheckpoint>
}

class InMemoryWorkflowCheckpointStore : WorkflowCheckpointStore {
    private val checkpoints = CopyOnWriteArrayList<WorkflowCheckpoint>()

    override suspend fun record(checkpoint: WorkflowCheckpoint) {
        checkpoints += checkpoint
    }

    override suspend fun history(sessionId: Long): List<WorkflowCheckpoint> =
        checkpoints.filter { it.sessionId == sessionId }
}

data class WorkflowStepPolicy(
    val effectSafety: EffectSafety = EffectSafety.READ_ONLY,
    val maxAttempts: Int = 1,
    val retryDelayMs: Long = 160L,
    val diagnoseOnFailure: Boolean = true,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts 必须至少为 1" }
        require(retryDelayMs >= 0L) { "retryDelayMs 不能为负数" }
    }
}

fun <T> Observation<T>.confirmedOrNull(): T? =
    (this as? Observation.Confirmed<T>)?.value

sealed interface StepRecovery {
    /** Re-observation confirmed that the step's intended effect already happened. */
    data object Recovered : StepRecovery

    /** Re-observation proved that repeating the entire step is safe. */
    data object RetrySafe : StepRecovery

    data object Fail : StepRecovery
}

data class WorkflowRunResult(
    val workflowId: String,
    val completedSteps: Int,
    val completedEarly: Boolean,
)

class WorkflowStepScope<C> internal constructor(
    val context: C,
    val session: AutomationSession,
    val stepId: String,
) {
    suspend fun <T> observe(observer: Observer<C, T>): Observation<T> =
        observer.observe(context, session)

    fun <T> requireConfirmed(
        observation: Observation<T>,
        message: String,
    ): T = when (observation) {
        is Observation.Confirmed -> observation.value
        is Observation.Absent,
        is Observation.Uncertain,
        -> throw ObservationException(observation, message)
    }

    fun completeWorkflow(): Nothing = throw WorkflowCompleted
}

class Workflow<C> internal constructor(
    val id: String,
    private val steps: List<WorkflowStep<C>>,
) {
    suspend fun run(
        context: C,
        session: AutomationSession,
    ): WorkflowRunResult {
        var completedSteps = 0
        for (step in steps) {
            val gestureTokenAtStart = session.latestGestureReceipt()?.token
            var attempt = 1
            while (true) {
                session.recordCheckpoint(
                    step.checkpoint(
                        workflowId = id,
                        state = WorkflowCheckpointState.STARTED,
                        attempt = attempt,
                        session = session,
                    ),
                )
                val scope = WorkflowStepScope(context, session, step.id)
                try {
                    step.action(scope)
                    session.recordCheckpoint(
                        step.checkpoint(
                            workflowId = id,
                            state = WorkflowCheckpointState.SUCCEEDED,
                            attempt = attempt,
                            session = session,
                        ),
                    )
                    completedSteps += 1
                    break
                } catch (_: WorkflowCompleted) {
                    session.recordCheckpoint(
                        step.checkpoint(
                            workflowId = id,
                            state = WorkflowCheckpointState.SUCCEEDED,
                            attempt = attempt,
                            session = session,
                            message = "workflow_completed",
                        ),
                    )
                    return WorkflowRunResult(
                        workflowId = id,
                        completedSteps = completedSteps + 1,
                        completedEarly = true,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val recovery = runRecovery(step, scope, error)
                    when (recovery) {
                        StepRecovery.Recovered -> {
                            session.markLatestGestureReconciled(
                                stepId = step.id,
                                previousToken = gestureTokenAtStart,
                            )
                            session.recordCheckpoint(
                                step.checkpoint(
                                    workflowId = id,
                                    state = WorkflowCheckpointState.RECOVERED,
                                    attempt = attempt,
                                    session = session,
                                    message = error.message,
                                ),
                            )
                            completedSteps += 1
                            break
                        }

                        StepRecovery.RetrySafe -> {
                            if (attempt >= step.policy.maxAttempts) {
                                failStep(step, session, attempt, error)
                            }
                            session.recordCheckpoint(
                                step.checkpoint(
                                    workflowId = id,
                                    state = WorkflowCheckpointState.RETRYING,
                                    attempt = attempt,
                                    session = session,
                                    message = error.message,
                                ),
                            )
                            session.clock.delay(step.policy.retryDelayMs)
                            attempt += 1
                        }

                        StepRecovery.Fail -> failStep(step, session, attempt, error)
                    }
                }
            }
        }
        return WorkflowRunResult(
            workflowId = id,
            completedSteps = completedSteps,
            completedEarly = false,
        )
    }

    private suspend fun runRecovery(
        step: WorkflowStep<C>,
        scope: WorkflowStepScope<C>,
        error: Throwable,
    ): StepRecovery {
        val recovery = step.recovery ?: return StepRecovery.Fail
        return try {
            recovery(scope, error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (recoveryError: Exception) {
            error.addSuppressed(recoveryError)
            StepRecovery.Fail
        }
    }

    private suspend fun failStep(
        step: WorkflowStep<C>,
        session: AutomationSession,
        attempt: Int,
        error: Throwable,
    ): Nothing {
        session.recordCheckpoint(
            step.checkpoint(
                workflowId = id,
                state = WorkflowCheckpointState.FAILED,
                attempt = attempt,
                session = session,
                message = error.message,
            ),
        )
        if (step.policy.diagnoseOnFailure) {
            session.diagnose("workflow_${id}_${step.id}_failed")
        }
        throw error
    }
}

@AutomationWorkflowDsl
class WorkflowPolicyBuilder internal constructor(
    policy: WorkflowStepPolicy,
) {
    var effectSafety: EffectSafety = policy.effectSafety
    var maxAttempts: Int = policy.maxAttempts
    var retryDelayMs: Long = policy.retryDelayMs
    var diagnoseOnFailure: Boolean = policy.diagnoseOnFailure

    internal fun build(): WorkflowStepPolicy = WorkflowStepPolicy(
        effectSafety = effectSafety,
        maxAttempts = maxAttempts,
        retryDelayMs = retryDelayMs,
        diagnoseOnFailure = diagnoseOnFailure,
    )
}

@AutomationWorkflowDsl
class WorkflowStepBuilder<C> internal constructor(
    private var policy: WorkflowStepPolicy,
) {
    private var action: (suspend WorkflowStepScope<C>.() -> Unit)? = null
    private var recovery: (suspend WorkflowStepScope<C>.(Throwable) -> StepRecovery)? = null

    fun policy(block: WorkflowPolicyBuilder.() -> Unit) {
        policy = WorkflowPolicyBuilder(policy).apply(block).build()
    }

    fun execute(block: suspend WorkflowStepScope<C>.() -> Unit) {
        action = block
    }

    fun recover(block: suspend WorkflowStepScope<C>.(Throwable) -> StepRecovery) {
        recovery = block
    }

    internal fun build(id: String): WorkflowStep<C> = WorkflowStep(
        id = id,
        policy = policy,
        action = requireNotNull(action) { "Workflow step $id 缺少 execute" },
        recovery = recovery,
    )
}

@AutomationWorkflowDsl
class WorkflowBuilder<C> internal constructor(
    private val workflowId: String,
    private val prefix: String = "",
    initialPolicy: WorkflowStepPolicy = WorkflowStepPolicy(),
    private val steps: MutableList<WorkflowStep<C>> = mutableListOf(),
) {
    private var defaults: WorkflowStepPolicy = initialPolicy

    fun defaults(block: WorkflowPolicyBuilder.() -> Unit) {
        defaults = WorkflowPolicyBuilder(defaults).apply(block).build()
    }

    fun stage(
        id: String,
        block: WorkflowBuilder<C>.() -> Unit,
    ) {
        require(id.isNotBlank()) { "stage id 不能为空" }
        WorkflowBuilder(
            workflowId = workflowId,
            prefix = "$prefix$id.",
            initialPolicy = defaults,
            steps = steps,
        ).apply(block)
    }

    fun step(
        id: String,
        effectSafety: EffectSafety? = null,
        block: WorkflowStepBuilder<C>.() -> Unit,
    ) {
        require(id.isNotBlank()) { "step id 不能为空" }
        val initial = effectSafety?.let { defaults.copy(effectSafety = it) } ?: defaults
        steps += WorkflowStepBuilder<C>(initial)
            .apply(block)
            .build("$prefix$id")
    }

    internal fun build(): Workflow<C> {
        require(steps.isNotEmpty()) { "workflow $workflowId 至少需要一个 step" }
        val duplicateStep = steps.groupingBy(WorkflowStep<C>::id)
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key
        require(duplicateStep == null) { "workflow $workflowId 存在重复 step：$duplicateStep" }
        return Workflow(workflowId, steps.toList())
    }
}

fun <C> workflow(
    id: String,
    block: WorkflowBuilder<C>.() -> Unit,
): Workflow<C> {
    require(id.isNotBlank()) { "workflow id 不能为空" }
    return WorkflowBuilder<C>(workflowId = id).apply(block).build()
}

internal data class WorkflowStep<C>(
    val id: String,
    val policy: WorkflowStepPolicy,
    val action: suspend WorkflowStepScope<C>.() -> Unit,
    val recovery: (suspend WorkflowStepScope<C>.(Throwable) -> StepRecovery)?,
) {
    fun checkpoint(
        workflowId: String,
        state: WorkflowCheckpointState,
        attempt: Int,
        session: AutomationSession,
        message: String? = null,
    ): WorkflowCheckpoint = WorkflowCheckpoint(
        workflowId = workflowId,
        stepId = id,
        state = state,
        attempt = attempt,
        recordedAtElapsedMs = session.clock.elapsedRealtime(),
        effectSafety = policy.effectSafety,
        message = message,
    )
}

private object WorkflowCompleted : RuntimeException(null, null, false, false)
