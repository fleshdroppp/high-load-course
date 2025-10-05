package ru.quipy.common.utils

import io.github.resilience4j.ratelimiter.RateLimiter
import ru.quipy.exception.ResourceExhaustedRetryableException
import java.time.Duration
import java.util.concurrent.Executor

class ExhaustingExecutorDecorator(
    private val rateLimiter: RateLimiter,
    private val delay: Duration,
    private val executor: Executor,
) : Executor {
    override fun execute(command: Runnable) {
        if (!rateLimiter.acquirePermission()) {
            logger.error("Resource exhausted, rejecting request")
            throw ResourceExhaustedRetryableException(delay)
        }

        executor.execute(command)
    }

    companion object {
        private val logger = logger()
    }
}

fun Executor.exhausting(rateLimiter: RateLimiter, delay: Duration): Executor {
    return ExhaustingExecutorDecorator(rateLimiter, delay, this)
}

fun Executor.exhausting(rateLimiter: RateLimiter): Executor {
    return exhausting(rateLimiter, Duration.ofSeconds(1))
}