package ru.quipy.apigateway.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter
import ru.quipy.common.utils.RateLimiter
import ru.quipy.common.utils.logger

class RateLimiterFilter(
    private val rateLimiter: RateLimiter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val isAccepted = rateLimiter.tick()
        if (!isAccepted) {
            Companion.logger.warn("Dropped request {}", request.requestURI)
            response.sendError(HttpStatus.TOO_MANY_REQUESTS_429)
            return
        }

        filterChain.doFilter(request, response)
    }

    companion object {
        private val logger = logger()
    }
}