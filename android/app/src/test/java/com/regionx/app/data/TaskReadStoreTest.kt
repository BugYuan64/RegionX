package com.regionx.app.data

import android.app.Application
import android.content.Context
import com.regionx.app.core.model.TaskRecord
import com.regionx.app.core.model.TaskStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class TaskReadStoreTest {
    private lateinit var context: Context
    private lateinit var reads: TaskReadStore

    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("task_reads", Context.MODE_PRIVATE).edit().clear().commit()
        reads = TaskReadStore(context)
    }

    @Test fun existingCompletionsAreUnreadAndOtherStatusesAreNot() {
        val task = TaskRecord(id = "existing", updatedAt = 0L)
        TaskStatus.entries.forEach { status ->
            assertEquals(status == TaskStatus.Completed, reads.isUnread(task.copy(status = status)))
        }
    }

    @Test fun openingACompletionPersistsItsReadStateWithoutReadingOtherTasks() {
        val task = TaskRecord(id = "opened", status = TaskStatus.Completed, updatedAt = 100L)
        reads.markRead(task)

        assertFalse(reads.isUnread(task))
        val restored = TaskReadStore(context)
        assertFalse(restored.isUnread(task))
        assertTrue(restored.isUnread(task.copy(id = "unopened")))
    }

    @Test fun openingARunningTaskDoesNotReadItsLaterCompletionAtTheSameTimestamp() {
        val task = TaskRecord(id = "running", status = TaskStatus.Running, updatedAt = 100L)
        reads.markRead(task)

        assertEquals(0L, reads.revision.value)
        assertTrue(reads.isUnread(task.copy(status = TaskStatus.Completed)))
    }

    @Test fun retryCompletionWithANewerVersionBecomesUnreadAgain() {
        val first = TaskRecord(id = "retry", status = TaskStatus.Completed, updatedAt = 100L)
        reads.markRead(first)
        assertFalse(reads.isUnread(first.copy(status = TaskStatus.Running, updatedAt = 200L)))
        assertTrue(reads.isUnread(first.copy(updatedAt = 300L)))
    }

    @Test fun staleDetailCannotOverwriteANewerReadVersion() {
        val latest = TaskRecord(id = "versions", status = TaskStatus.Completed, updatedAt = 300L)
        reads.markRead(latest)
        reads.markRead(latest.copy(updatedAt = 100L))

        assertFalse(reads.isUnread(latest))
        assertEquals(1L, reads.revision.value)
    }

    @Test fun revisionChangesOnlyWhenACompletedVersionIsNewlyRead() {
        val task = TaskRecord(id = "revision", status = TaskStatus.Completed, updatedAt = 100L)
        reads.markRead(task)
        assertEquals(1L, reads.revision.value)
        reads.markRead(task)
        assertEquals(1L, reads.revision.value)
        reads.markRead(task.copy(updatedAt = 200L))
        assertEquals(2L, reads.revision.value)
    }
}
