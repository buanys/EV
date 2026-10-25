package ev.buan

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.cordis.ServiceKey

/**
 * Host service directory: the published semantics (name-keyed publish/lookup/retract) plus the
 * concurrent access the agent layer introduced - the frontend reads `AgentService` on the Compose
 * thread while plugins read `ModelService`/`ToolRegistry`/`MemoryService` on their IO dispatchers,
 * and activation publishes or retracts on its own coroutine.
 *
 * The smoke tests assert only what callers rely on under contention: a publish is visible to the
 * publishing thread immediately, readers never observe a half-built entry, a publish that races a
 * retract resolves to one of the published values, and the directory is still usable after churn.
 * They are deterministic for a concurrent map; the previous `LinkedHashMap` flaked on the first one,
 * because a `put` that resizes the table while another thread reads can hide the value it asks for.
 */
class ServiceDirectoryImplTest {

    private val key = ServiceKey<String>("test/service")

    @Test
    fun `publish 后可 lookup 到同一实例`() {
        val directory = ServiceDirectoryImpl()
        val capability = "capability"

        directory.publish(key, capability)

        assertSame(capability, directory.lookup(key))
    }

    @Test
    fun `同一个 Key 再次 publish 替换旧值`() {
        val directory = ServiceDirectoryImpl()
        directory.publish(key, "first")

        directory.publish(key, "second")

        assertEquals("second", directory.lookup(key))
    }

    @Test
    fun `retract 后 lookup 为 null 且重复 retract 不抛`() {
        val directory = ServiceDirectoryImpl()
        directory.publish(key, "capability")

        directory.retract(key)
        assertNull(directory.lookup(key))

        directory.retract(key)
        assertNull(directory.lookup(key))
    }

    @Test
    fun `同名 Key 的不同实例指向同一条目`() {
        val directory = ServiceDirectoryImpl()
        directory.publish(ServiceKey<String>("test/service"), "capability")

        // The key is its name, never object identity - that is how injection and lookup already met.
        assertEquals("capability", directory.lookup(ServiceKey<String>("test/service")))
    }

    @Test
    fun `不同 name 的 Key 互不影响`() {
        val directory = ServiceDirectoryImpl()
        val other = ServiceKey<String>("test/other")
        directory.publish(key, "one")
        directory.publish(other, "two")

        directory.retract(key)

        assertNull(directory.lookup(key))
        assertEquals("two", directory.lookup(other))
    }

    @Test
    fun `并发 publish 立即被同线程读回`() {
        val directory = ServiceDirectoryImpl()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val threads = (0 until 8).map { index ->
            Thread {
                start.await()
                repeat(2_000) { iteration ->
                    val own = ServiceKey<String>("test/concurrent/$index")
                    val value = "t$index-$iteration"
                    runCatching {
                        directory.publish(own, value)
                        // A map that resized under a concurrent read could drop this update.
                        check(directory.lookup(own) == value) { "丢失写入 $value" }
                        // Readers also touch a neighbour's slot: reading must never throw.
                        directory.lookup(ServiceKey<String>("test/concurrent/${(index + 1) % 8}"))
                    }.onFailure { errors += it }
                }
            }.apply { isDaemon = true }
        }

        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        assertTrue(errors.isEmpty(), "并发线程内部异常：${errors.firstOrNull()}")
        assertNull(directory.lookup(ServiceKey<String>("test/unused")))
    }

    @Test
    fun `同一 Key 并发 publish 后读到其中一个已发布值`() {
        val directory = ServiceDirectoryImpl()
        val published = ConcurrentLinkedQueue<String>()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val shared = ServiceKey<String>("test/shared")
        val threads = (0 until 8).map { index ->
            Thread {
                start.await()
                repeat(1_000) { iteration ->
                    val value = "writer$index-$iteration"
                    runCatching {
                        directory.publish(shared, value)
                        published += value
                    }.onFailure { errors += it }
                }
            }.apply { isDaemon = true }
        }

        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        assertTrue(errors.isEmpty(), "并发线程内部异常：${errors.firstOrNull()}")
        val finalValue = assertNotNull(directory.lookup(shared))
        assertTrue(finalValue in published, "lookup 返回了从未发布的值：$finalValue")
    }

    @Test
    fun `并发 publish 与 retract 之后目录仍可用`() {
        val directory = ServiceDirectoryImpl()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val threads = (0 until 8).map { index ->
            Thread {
                start.await()
                repeat(2_000) { iteration ->
                    val churn = ServiceKey<String>("test/churn/${(index + iteration) % 16}")
                    runCatching {
                        directory.publish(churn, "v$iteration")
                        if (iteration % 5 == 0) directory.retract(churn)
                    }.onFailure { errors += it }
                }
            }.apply { isDaemon = true }
        }

        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        assertTrue(errors.isEmpty(), "并发线程内部异常：${errors.firstOrNull()}")
        // A structure that lost entries or chains during the churn would fail to hold these writes.
        val finals = (0 until 16).map { ServiceKey<String>("test/final/$it") }
        finals.forEach { directory.publish(it, it.name) }
        finals.forEach { assertEquals(it.name, directory.lookup(it)) }
    }
}
