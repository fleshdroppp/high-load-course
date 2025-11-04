package ru.quipy.config

import io.github.resilience4j.ratelimiter.RequestNotPermitted
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import ru.quipy.exception.ResourceExhaustedRetryableException
import ru.quipy.payments.exception.TooManyParallelPaymentsException

@Component
@ControllerAdvice
class AppExceptionHandler {
    @ExceptionHandler(exception = [RequestNotPermitted::class])
    fun handleRequestNotPermitted(): ResponseEntity<Unit> {
        return ResponseEntity(HttpStatus.TOO_MANY_REQUESTS)
    }

    @ExceptionHandler(TooManyParallelPaymentsException::class)
    fun handleTooManyParallelRequests(): ResponseEntity<Unit> {
        return ResponseEntity(HttpStatus.TOO_MANY_REQUESTS)
    }

    @ExceptionHandler(ResourceExhaustedRetryableException::class)
    fun handleResourceExhaustedRetryableException(ex: ResourceExhaustedRetryableException): ResponseEntity<Unit> {
        val responseHeaders = HttpHeaders().apply {
            add("Retry-After", ex.retryAfter.toEpochMilli().toString())
        }

        return ResponseEntity<Unit>(Unit, responseHeaders, HttpStatus.TOO_MANY_REQUESTS)
    }
}