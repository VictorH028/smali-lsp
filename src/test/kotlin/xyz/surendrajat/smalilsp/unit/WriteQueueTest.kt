package xyz.surendrajat.smalilsp.unit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.WriteQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WriteQueueTest {

    @Test
    fun `writes run in submission order and the barrier waits for all of them`() {
        WriteQueue().use { queue ->
            val seen = mutableListOf<Int>()
            val gate = CountDownLatch(1)
            queue.submit("first") { gate.await(); seen.add(1) }
            for (i in 2..50) queue.submit("n$i") { seen.add(i) }
            val barrier = queue.barrier()
            assertTrue(seen.isEmpty(), "nothing ran while the first write is blocked")
            gate.countDown()
            barrier.get(5, TimeUnit.SECONDS)
            assertEquals((1..50).toList(), seen)
        }
    }

    @Test
    fun `a failing write does not stop the next one`() {
        WriteQueue().use { queue ->
            var ran = false
            queue.submit("boom") { throw IllegalStateException("boom") }
            queue.submit("after") { ran = true }
            queue.barrier().get(5, TimeUnit.SECONDS)
            assertTrue(ran)
        }
    }

    @Test
    fun `submissions from several threads keep each thread's order`() {
        WriteQueue().use { queue ->
            val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
            val threads = (0 until 4).map { t ->
                Thread { for (i in 0 until 100) queue.submit("$t-$i") { seen.add("$t-$i") } }.apply { start() }
            }
            threads.forEach { it.join() }
            queue.barrier().get(5, TimeUnit.SECONDS)
            assertEquals(400, seen.size)
            for (t in 0 until 4) {
                assertEquals((0 until 100).map { "$t-$it" }, seen.filter { it.startsWith("$t-") })
            }
        }
    }

    @Test
    fun `after close new writes are dropped and the barrier still completes`() {
        val queue = WriteQueue()
        queue.submit("before") { }
        queue.close()
        var ran = false
        queue.submit("after close") { ran = true }
        queue.barrier().get(5, TimeUnit.SECONDS)
        assertTrue(!ran)
    }
}
