package ru.quipy.common.utils

import kotlinx.coroutines.*
import ru.quipy.exception.ResourceExhaustedRetryableException
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue

class LeakyBucketRateLimiter(
    private val rate: Int,
    private val window: Duration,
    bucketSize: Int
) {
    private val queue = LinkedBlockingQueue<CompletableDeferred<Unit>>(bucketSize)

    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        scope.launch {
            while (isActive) {
                delay(window.toMillis())
                repeat(rate) {
                    queue.poll()?.complete(Unit)
                }
            }
        }
    }

    suspend fun acquireOrThrow() {
        val deferred = CompletableDeferred<Unit>()
        val offered = queue.offer(deferred)
        if (!offered) {
            throw ResourceExhaustedRetryableException(1)
        }
        deferred.await()
    }

    companion object {
        private val logger = logger()
    }

}