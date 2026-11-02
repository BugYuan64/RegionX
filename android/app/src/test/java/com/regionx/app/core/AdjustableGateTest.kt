package com.regionx.app.core

import com.regionx.app.core.tasks.AdjustableGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 并发闸门：必须一开始就发放满额许可，否则任务会永远卡在「排队中」
 * （此前的实现重复扩容，导致所有许可被永久占用）。
 *
 * 这里显式使用 [Dispatchers.Default]（多线程），与生产环境的 appScope 一致；
 * 单线程事件循环下挂起的协程无法让出，会误判为死锁。
 */
class AdjustableGateTest {

    private fun <T> concurrent(block: suspend CoroutineScope.() -> T) =
        runBlocking(Dispatchers.Default) { coroutineScope { block() } }

    @Test
    fun `permits are available immediately`() = concurrent {
        val gate = AdjustableGate(3)
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)

        (1..6).map {
            async {
                gate.withPermit {
                    val now = running.incrementAndGet()
                    peak.updateAndGet { old -> maxOf(old, now) }
                    delay(50)
                    running.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(3, peak.get())
        assertEquals(0, running.get())
    }

    @Test
    fun `growing capacity releases extra permits`() = concurrent {
        val gate = AdjustableGate(1)
        val peak = AtomicInteger(0)
        val running = AtomicInteger(0)

        val jobs = (1..3).map {
            async {
                gate.withPermit {
                    val now = running.incrementAndGet()
                    peak.updateAndGet { old -> maxOf(old, now) }
                    delay(200)
                    running.decrementAndGet()
                }
            }
        }
        delay(20)
        gate.setCapacity(3)

        jobs.awaitAll()

        assertEquals(3, peak.get())
        assertEquals(3, gate.capacity)
    }

    @Test
    fun `shrinking capacity lets new tasks trickle in without deadlock`() = concurrent {
        val gate = AdjustableGate(3)
        val done = AtomicInteger(0)

        gate.setCapacity(1)
        withTimeout(5_000) {
            (1..3).map {
                async {
                    gate.withPermit {
                        delay(20)
                        done.incrementAndGet()
                    }
                }
            }.awaitAll()
        }

        assertEquals(3, done.get())
        assertEquals(1, gate.capacity)
    }
}
