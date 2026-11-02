package com.regionx.app.core.tasks

import com.regionx.app.core.llm.ChatModelClient
import com.regionx.app.core.llm.ChatModelClientFactory
import com.regionx.app.core.llm.LlmChatRequest
import com.regionx.app.core.model.AppSettings
import com.regionx.app.core.model.ModelConfig
import com.regionx.app.core.model.TaskRecord
import com.regionx.app.core.model.TaskStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class TaskCancellationTest {
    /** Like SQLite, each write checks cancellation by switching coroutine context. */
    private class Store : TaskPersistence {
        val states = ConcurrentHashMap<String, TaskStatus>()
        val replies = ConcurrentHashMap<String, String>()
        var failCancelledWrite = false
        var beforeCancelledWrite: suspend () -> Unit = {}
        var beforeRunningWrite: suspend () -> Unit = {}

        override suspend fun updateTask(task: TaskRecord) {
            withContext(Dispatchers.Unconfined) {
                if (task.status == TaskStatus.Running) beforeRunningWrite()
                if (task.status == TaskStatus.Cancelled) beforeCancelledWrite()
                if (failCancelledWrite && task.status == TaskStatus.Cancelled) error("database unavailable")
                states[task.id] = task.status
            }
        }

        override suspend fun saveAssistantMessage(task: TaskRecord, content: String) {
            withContext(Dispatchers.Unconfined) { replies[task.id] = content }
        }
    }

    private fun factory(stream: suspend kotlinx.coroutines.flow.FlowCollector<String>.() -> Unit) =
        ChatModelClientFactory {
            object : ChatModelClient {
                override fun stream(request: LlmChatRequest): Flow<String> = flow(stream)
            }
        }

    private fun runner(
        store: Store,
        factory: ChatModelClientFactory = factory { awaitCancellation() },
        changed: (TaskRecord) -> Unit = {},
    ) = TaskRunner(factory, store, TaskImageSource { byteArrayOf(1, 2, 3) }, { AppSettings() }, {}, changed)

    @Test fun queuedCancellationMustPersist() = runTest {
        val store = Store()
        val queue = TaskQueue(backgroundScope, runner(store), 1, {})
        val first = TaskRecord(id = "first")
        val second = TaskRecord(id = "queued")
        store.states[second.id] = TaskStatus.Queued
        queue.enqueue(first, ModelConfig())
        queue.enqueue(second, ModelConfig())
        runCurrent()
        assertEquals(TaskStatus.Running, store.states[first.id])

        queue.cancel(second.id)
        runCurrent()

        assertEquals(TaskStatus.Cancelled, store.states[second.id])
        assertFalse(queue.isRunning(second.id))
    }

    @Test fun runningCancellationMustPersistPartialReplyBeforeEvent() = runBlocking {
        val store = Store()
        val received = CompletableDeferred<Unit>()
        val persistedEvent = CompletableDeferred<Pair<TaskStatus?, String?>>()
        val task = TaskRecord(id = "running")
        val taskRunner = runner(store, factory {
            emit("already generated")
            received.complete(Unit)
            awaitCancellation()
        }) { state ->
            if (state.status == TaskStatus.Cancelled) {
                persistedEvent.complete(store.states[state.id] to store.replies[state.id])
            }
        }
        val job = launch { taskRunner.run(task, ModelConfig()) }
        try {
            withTimeout(3_000) { received.await() }
            job.cancelAndJoin()

            assertEquals(TaskStatus.Cancelled, store.states[task.id])
            assertEquals("already generated", store.replies[task.id])
            assertEquals(TaskStatus.Cancelled to "already generated", withTimeout(3_000) { persistedEvent.await() })
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun immediateCancellationBeforeDispatchMustPersist() = runTest {
        val store = Store()
        val queue = TaskQueue(backgroundScope, runner(store), 1, {})
        val task = TaskRecord(id = "immediate")
        store.states[task.id] = TaskStatus.Queued
        queue.enqueue(task, ModelConfig())
        assertTrue(queue.cancel(task.id))

        runCurrent()

        assertEquals(TaskStatus.Cancelled, store.states[task.id])
        assertFalse(queue.isRunning(task.id))
    }

    @Test fun cancellationDuringInitialRunningWriteMustPersist() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val store = Store().apply {
            beforeRunningWrite = {
                writing.complete(Unit)
                awaitCancellation()
            }
        }
        val task = TaskRecord(id = "initial-write")
        val job = launch { runner(store).run(task, ModelConfig()) }
        try {
            withTimeout(3_000) { writing.await() }
        } finally {
            job.cancelAndJoin()
        }

        assertEquals(TaskStatus.Cancelled, store.states[task.id])
    }

    @Test fun cancelAndJoinWaitsForFinalPersistence() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val writing = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val store = Store().apply {
            beforeCancelledWrite = {
                writing.complete(Unit)
                releaseWrite.await()
            }
        }
        val queue = TaskQueue(this, runner(store, factory {
            started.complete(Unit)
            awaitCancellation()
        }), 1, {})
        val task = TaskRecord(id = "join")
        try {
            queue.enqueue(task, ModelConfig())
            withTimeout(3_000) { started.await() }
            val waiter = launch {
                queue.cancelAndJoin(task.id)
                returned.complete(Unit)
            }
            try {
                withTimeout(3_000) { writing.await() }
                assertFalse(returned.isCompleted)
                assertTrue(queue.isRunning(task.id))
            } finally {
                releaseWrite.complete(Unit)
            }
            withTimeout(3_000) { waiter.join() }
            assertTrue(returned.isCompleted)
            assertEquals(TaskStatus.Cancelled, store.states[task.id])
            assertFalse(queue.isRunning(task.id))
        } finally {
            releaseWrite.complete(Unit)
            queue.cancelAll()
        }
    }

    @Test fun synchronousCompletionMustNotLeaveARegisteredJob() = runBlocking {
        val store = Store()
        val task = TaskRecord(id = "synchronous")
        // An already-cancelled scope also exercises completion before enqueue returns.
        val scope = CoroutineScope(SupervisorJob().apply { cancel() } + Dispatchers.Unconfined)
        val queue = TaskQueue(scope, runner(store), 1, {})

        queue.enqueue(task, ModelConfig())
        yield()

        assertFalse(queue.isRunning(task.id))
    }

    @Test fun retryWaitsForPreviousCleanupAndKeepsReplacementRegistered() = runBlocking {
        val store = Store()
        val firstStarted = CompletableDeferred<Unit>()
        val cleaningUp = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        val task = TaskRecord(id = "retry")
        var calls = 0
        val queue = TaskQueue(this, runner(store, factory {
            if (++calls == 1) {
                firstStarted.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        cleaningUp.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            } else {
                replacementStarted.complete(Unit)
                awaitCancellation()
            }
        }), 2, {})
        try {
            queue.enqueue(task, ModelConfig())
            withTimeout(3_000) { firstStarted.await() }
            queue.retry(task, ModelConfig())
            try {
                withTimeout(3_000) { cleaningUp.await() }
                assertNull("Replacement must wait for old persistence", withTimeoutOrNull(150) { replacementStarted.await() })
            } finally {
                releaseCleanup.complete(Unit)
            }
            withTimeout(3_000) { replacementStarted.await() }
            yield()
            assertTrue("Old completion must not remove replacement", queue.isRunning(task.id))
            assertTrue(queue.cancel(task.id))
        } finally {
            releaseCleanup.complete(Unit)
            queue.cancelAll()
        }
    }

    @Test fun failedCancellationWriteMustPropagateWithoutPublishingSuccess() = runBlocking {
        val store = Store().apply { failCancelledWrite = true }
        val changes = mutableListOf<TaskStatus>()
        val taskRunner = runner(store, factory { throw CancellationException("stop") }) { changes += it.status }

        val failure = runCatching { taskRunner.run(TaskRecord(id = "failed-write"), ModelConfig()) }.exceptionOrNull()

        assertTrue("Persistence failure must remain visible", failure is IllegalStateException)
        assertFalse(changes.contains(TaskStatus.Cancelled))
        assertEquals(TaskStatus.Running, store.states["failed-write"])
    }
}
