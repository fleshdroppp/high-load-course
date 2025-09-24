package ru.quipy.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import ru.quipy.common.utils.OngoingWindow
import ru.quipy.common.utils.ParallelRequestsLimiter
import kotlin.properties.Delegates

@Configuration
@Import(
    HttpFilterConfiguration::class,
)
class HttpConfiguration {
    @set:Value("\${app.parallel-requests-limiter.max-size}")
    private var parallelRequestsLimiterMaxSize by Delegates.notNull<Int>()

    @Bean
    fun parallelRequestsLimiter(): ParallelRequestsLimiter {
        return OngoingWindow(parallelRequestsLimiterMaxSize)
    }
}