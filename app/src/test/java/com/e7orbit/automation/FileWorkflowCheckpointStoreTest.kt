package com.e7orbit.automation

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileWorkflowCheckpointStoreTest {
    @Test
    fun persistsCheckpointsAcrossStoreInstancesAndAppliesRetention() = runTest {
        withCheckpointFile { file ->
            val firstStore = FileWorkflowCheckpointStore(file, maxEntries = 2)
            firstStore.record(checkpoint(runId = "run-a", stepId = "one"))
            firstStore.record(checkpoint(runId = "run-a", stepId = "two"))
            firstStore.record(checkpoint(runId = "run-b", stepId = "three"))

            val restoredStore = FileWorkflowCheckpointStore(file, maxEntries = 2)

            assertEquals(
                listOf("two", "three"),
                restoredStore.recent(10).map(WorkflowCheckpoint::stepId),
            )
            assertEquals(
                listOf("two"),
                restoredStore.history("run-a").map(WorkflowCheckpoint::stepId),
            )
            assertEquals(
                listOf("three"),
                restoredStore.history("run-b").map(WorkflowCheckpoint::stepId),
            )
        }
    }

    @Test
    fun malformedJournalDoesNotPreventRecordingNewEvidence() = runTest {
        withCheckpointFile { file ->
            requireNotNull(file.parentFile).mkdirs()
            file.writeText("not-json")
            val store = FileWorkflowCheckpointStore(file)

            assertTrue(store.recent(10).isEmpty())
            store.record(checkpoint(runId = "recovered-run", stepId = "started"))

            val restored = FileWorkflowCheckpointStore(file)
            assertEquals(
                listOf("started"),
                restored.history("recovered-run").map(WorkflowCheckpoint::stepId),
            )
        }
    }

    private suspend fun withCheckpointFile(block: suspend (File) -> Unit) {
        val directory = createTempDirectory("e7orbit-checkpoints").toFile()
        try {
            block(File(directory, "journal.json"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun checkpoint(
        runId: String,
        stepId: String,
    ) = WorkflowCheckpoint(
        runId = runId,
        workflowId = "test",
        stepId = stepId,
        runKey = "cycle-1",
        state = WorkflowCheckpointState.SUCCEEDED,
        attempt = 1,
        recordedAtElapsedMs = 1L,
    )
}
