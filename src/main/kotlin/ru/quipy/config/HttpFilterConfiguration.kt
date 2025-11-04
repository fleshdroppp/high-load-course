package ru.quipy.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import ru.quipy.apigateway.filter.MdcFilter

import ru.quipy.apigateway.filter.ParallelRequestsLimiterFilter
import ru.quipy.apigateway.filter.RequestLoggingFilter
import ru.quipy.common.utils.ParallelRequestsLimiter


@Configuration
class HttpFilterConfiguration {
    @Bean
    @Order(0)
    fun mdcFilter() = MdcFilter()

    @Bean
    @Order(1)
    fun requestLoggingFilter(): RequestLoggingFilter = RequestLoggingFilter()


    @Bean
    @Order(2)
    fun parallelRequestsFilter(limiter: ParallelRequestsLimiter) : ParallelRequestsLimiterFilter {
        return ParallelRequestsLimiterFilter(limiter, 1)
    }

}