package ru.quipy.config

import io.github.resilience4j.ratelimiter.RequestNotPermitted
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@Component
@ControllerAdvice
class AppExceptionHandler {
    @ExceptionHandler(exception = [RequestNotPermitted::class])
    fun handleRequestNotPermitted(): ResponseEntity<Unit> {
        return ResponseEntity(HttpStatus.TOO_MANY_REQUESTS)
    }
}