package ru.quipy.exception

import java.time.Duration
import java.time.Instant

interface ResourceExhaustedException {
    val message: String
}

class ResourceExhaustedRetryableException(
    override val message: String,
    val retryAfter: Instant,
) : RuntimeException(), ResourceExhaustedException {
    constructor(duration: Duration): this(DEFAULT_MESSAGE, Instant.now().plus(duration))
    constructor(millis: Long): this(DEFAULT_MESSAGE, Instant.now().plusMillis(millis))

    companion object {
        private const val DEFAULT_MESSAGE = "Resource exhausted"
    }
}
