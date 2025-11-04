package ru.quipy.apigateway.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import ru.quipy.common.utils.MdcKeys
import java.util.UUID

class MdcFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        MDC.put(MdcKeys.REQUEST_ID.inner, generateRequestId())
        filterChain.doFilter(request, response)
    }

    private fun generateRequestId(): String {
        return UUID.randomUUID().toString()
    }
}