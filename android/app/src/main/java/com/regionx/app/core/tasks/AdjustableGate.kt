package com.regionx.app.core.tasks

import kotlinx.coroutines.delay

/**
 * 并发闸门：限制同时运行的任务数，容量可在设置页随时调整。
 *
 * 用「计数 + 容量比较」而不是 Semaphore：Semaphore 一旦缩容就无法安全收回已发放的
 * 许可，容易出现任务永久卡在「排队中」；这里每次放行都按当前 [capacity] 判断，
 * 扩容立即生效，缩容只影响后续放行，运行中的任务自然结束。
 */
class AdjustableGate(initialCapacity: Int) {

    @Volatile
    var capacity: Int = initialCapacity.coerceAtLeast(1)
        private set

    private val lock = Any()
    private var running = 0

    fun setCapacity(value: Int) {
        synchronized(lock) { capacity = value.coerceAtLeast(1) }
    }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        while (!tryAcquire()) {
            // 让出调度线程等待空位，避免忙等占满 CPU
            delay(POLL_INTERVAL_MS)
        }
        try {
            return block()
        } finally {
            synchronized(lock) { running-- }
        }
    }

    private fun tryAcquire(): Boolean = synchronized(lock) {
        if (running < capacity) {
            running++
            true
        } else {
            false
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5L
    }
}
