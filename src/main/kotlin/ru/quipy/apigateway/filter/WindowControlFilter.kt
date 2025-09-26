package ru.quipy.apigateway.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.eclipse.jetty.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter
import ru.quipy.common.utils.ParallelRequestsLimiter
import ru.quipy.common.utils.logger

class ParallelRequestsLimiterFilter(
    private val limiter: ParallelRequestsLimiter,
    private val addRequestTimeout: Long
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        if (limiter.tryToAddRequest(addRequestTimeout)) {
            try {
                filterChain.doFilter(request, response)
            } finally {
                limiter.releaseRequest()
            }
            return
        }
        Companion.logger.warn(
            "Dropped request {}, limiter queue = {}",
            request.requestURI,
            limiter.awaitingQueueSize()
        )
        response.sendError(HttpStatus.TOO_MANY_REQUESTS_429)
    }

    companion object {
        private val logger = logger()
    }
}