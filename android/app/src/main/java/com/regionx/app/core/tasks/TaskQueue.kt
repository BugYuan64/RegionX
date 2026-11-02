package com.regionx.app.core.tasks

import com.regionx.app.core.model.ModelConfig
import com.regionx.app.core.model.TaskRecord
import com.regionx.app.core.model.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 任务队列：限制同时运行的任务数，每个任务独立持有 Job，可取消、可重试。
 * 单个任务失败不影响其他任务。对应 Windows 端 TaskQueue。
 */
class TaskQueue(
    private val scope: CoroutineScope,
    private val runner: TaskRunner,
    maxConcurrency: Int,
    private val onChanged: (TaskRecord) -> Unit,
) {
    private val gate = AdjustableGate(maxConcurrency)
    private val running = ConcurrentHashMap<String, Job>()
    private val registrationLock = Any()

    val maxConcurrencyValue: Int get() = gate.capacity

    fun updateConcurrency(value: Int) = gate.setCapacity(value)

    /** 提交任务，立即返回，不等待模型。 */
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun enqueue(task: TaskRecord, model: ModelConfig) {
        synchronized(registrationLock) {
            val previous = running[task.id]
            previous?.cancel()
            // ATOMIC guarantees the cancellation finalizer runs even if cancelled before dispatch.
            val job = scope.launch(start = CoroutineStart.ATOMIC) {
                var runnerStarted = false
                try {
                    // Old final writes must finish before the replacement touches the same record.
                    previous?.join()
                    currentCoroutineContext().ensureActive()
                    gate.withPermit {
                        currentCoroutineContext().ensureActive()
                        runnerStarted = true
                        runner.run(task, model)
                    }
                } catch (ex: CancellationException) {
                    if (!runnerStarted) {
                        withContext(NonCancellable) {
                            previous?.join()
                            runner.cancelQueued(task)
                        }
                    }
                }
            }
            running[task.id] = job
            // Installing after registration also handles jobs that completed synchronously.
            job.invokeOnCompletion { running.remove(task.id, job) }
        }
    }

    fun retry(task: TaskRecord, model: ModelConfig) {
        enqueue(task.copy(status = TaskStatus.Queued), model)
    }

    fun cancel(taskId: String): Boolean {
        return synchronized(registrationLock) {
            val job = running[taskId] ?: return@synchronized false
            job.cancel()
            true
        }
    }

    /** 等待取消收尾和持久化完成，再由调用方删除或重新提交任务。 */
    suspend fun cancelAndJoin(taskId: String) {
        val job = synchronized(registrationLock) {
            running[taskId]?.also { it.cancel() }
        }
        job?.join()
    }

    fun isRunning(taskId: String): Boolean = running.containsKey(taskId)

    /** 程序退出 / 识别框关闭时取消全部任务。 */
    fun cancelAll() {
        synchronized(registrationLock) {
            // Keep ownership until final writes finish so retry/delete can still join it.
            running.values.forEach { it.cancel() }
        }
    }
}
