package com.stb6.spdf.pdf

import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import org.junit.Test

class CancelWatchdogTest {
    @Test
    fun `监视器调度器尚未执行任务时取消仍发信号`() = runBlocking {
        val pending = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        }
        var entered = false
        var signalled = false
        val job = launch {
            withCancelWatchdog({ signalled = true }, dispatcher) {
                entered = true
                awaitCancellation()
            }
        }
        yield()
        assertTrue(entered)
        job.cancel()
        while (pending.isNotEmpty()) pending.removeFirst().run()
        job.join()
        assertTrue(signalled)
    }
}
