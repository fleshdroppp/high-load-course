package ru.quipy.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import ru.quipy.common.utils.LeakyBucketRateLimiter
import ru.quipy.common.utils.OngoingWindow
import ru.quipy.common.utils.ParallelRequestsLimiter
import java.time.Duration
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

    @Bean
    fun rateLimiter(): LeakyBucketRateLimiter {
        // bucketSize perfectly should be rate * processingTimeMillis = 11 * 13 = 143,
        // but in fact bucketSize value is a bit lower so no requests will fail with timeout
        return LeakyBucketRateLimiter(11, Duration.ofSeconds(1), 125)
    }

}