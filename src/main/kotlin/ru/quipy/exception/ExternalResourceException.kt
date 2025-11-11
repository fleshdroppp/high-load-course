package ru.quipy.exception

import java.time.Duration
import java.time.Instant

interface ExternalResourceException {
    val message: String
}

interface ClientRetryableException : ExternalResourceException {
    val retryAfter: Instant
}

class ResourceExhaustedRetryableException(
    override val message: String,
    override val retryAfter: Instant,
) : RuntimeException(), ClientRetryableException {
    constructor(duration: Duration) : this(DEFAULT_MESSAGE, Instant.now().plus(duration))
    constructor(millis: Long) : this(DEFAULT_MESSAGE, Instant.now().plusMillis(millis))

    companion object {
        private const val DEFAULT_MESSAGE = "Resource exhausted"
    }
}

class ExternalResourceTimeoutException(
    override val message: String,
    override val retryAfter: Instant,
) : RuntimeException(), ClientRetryableException {
    constructor(duration: Duration) : this(DEFAULT_MESSAGE, Instant.now().plus(duration))
    constructor(millis: Long) : this(DEFAULT_MESSAGE, Instant.now().plusMillis(millis))

    companion object {
        private const val DEFAULT_MESSAGE = "External resource timeout"
    }
}
