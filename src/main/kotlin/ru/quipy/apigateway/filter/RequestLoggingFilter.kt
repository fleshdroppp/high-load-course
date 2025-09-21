package ru.quipy.apigateway.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import ru.quipy.common.utils.logger

class RequestLoggingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        Companion.logger.info("Got request {} to {}, {}", request.method, request.requestURI, request.parameterMap)
        filterChain.doFilter(request, response)
    }

    companion object {
        private val logger = logger()
    }
}