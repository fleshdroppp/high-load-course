package ru.quipy.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import ru.quipy.common.utils.OngoingWindow
import ru.quipy.common.utils.ParallelRequestsLimiter
import ru.quipy.common.utils.RateLimiter
import ru.quipy.common.utils.SlidingWindowRateLimiter
import java.time.Duration
import kotlin.properties.Delegates

@Configuration
@Import(
    HttpFilterConfiguration::class,
)
class HttpConfiguration {
    @Value("\${app.rate-limiter.duration}")
    private lateinit var rateLimiterDuration: Duration

    @set:Value("\${app.rate-limiter.rate}")
    private var rateLimiterRate by Delegates.notNull<Long>()

    @set:Value("\${app.parallel-requests-limiter.max-size}")
    private var parallelRequestsLimiterMaxSize by Delegates.notNull<Int>()

    @Bean
    fun rateLimiter(): RateLimiter {
        return SlidingWindowRateLimiter(rateLimiterRate, rateLimiterDuration)
    }

    @Bean
    fun parallelRequestsLimiter(): ParallelRequestsLimiter {
        return OngoingWindow(parallelRequestsLimiterMaxSize)
    }
}