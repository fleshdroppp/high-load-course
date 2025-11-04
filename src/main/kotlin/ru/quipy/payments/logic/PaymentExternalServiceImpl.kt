package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import ru.quipy.common.utils.ParallelRequestsLimiter
import ru.quipy.common.utils.logger
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.exception.TooManyParallelPaymentsException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val parallelRequestsLimiter: ParallelRequestsLimiter,
    private val outboundPaymentRateLimiter: RateLimiter,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val meterRegistry: MeterRegistry,
) : PaymentExternalSystemAdapter {

    companion object {
        private val logger = logger()

        private val emptyBody = RequestBody.create(null, ByteArray(0))
        private val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val client = OkHttpClient.Builder()
        .callTimeout(requestAverageProcessingTime.multipliedBy(3))
        .build()

    private val semaphoreWaitSuccessTimer by lazy {
        Timer.builder("semaphore_wait_duration")
            .tag("outcome", "SUCCESS")
            .tag("service", serviceName)
            .register(meterRegistry)
    }

    private val semaphoreWaitFailTimer by lazy {
        Timer.builder("semaphore_wait_duration")
            .tag("outcome", "FAIL")
            .tag("service", serviceName)
            .register(meterRegistry)
    }

    private val semaphoreWaitCounterWait by lazy {
        Counter.builder("semaphore_wait_counter")
            .tag("outcome", "WAIT")
            .tag("service", serviceName)
            .register(meterRegistry)
    }

    private val semaphoreWaitCounterFinish by lazy {
        Counter.builder("semaphore_wait_counter")
            .tag("outcome", "FINISH")
            .tag("service", serviceName)
            .register(meterRegistry)
    }

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.info("[$accountName] Submitting payment request for payment $paymentId, time left: ${deadline - now()}")

        val transactionId = UUID.randomUUID()

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        val waitStartTime = System.nanoTime()
        semaphoreWaitCounterWait.increment()
        if (!parallelRequestsLimiter.tryToAddRequest(10)) {
            val waitDuration = Duration.ofNanos(System.nanoTime() - waitStartTime)
            semaphoreWaitFailTimer.record(waitDuration)
            semaphoreWaitCounterFinish.increment()
            logger.warn("Dropped order with payment_id = $paymentId! Timeout for acquiring semaphore reached!")
            throw TooManyParallelPaymentsException("Parallel requests limit was reached!")
        }

        val waitDuration = Duration.ofNanos(System.nanoTime() - waitStartTime)
        semaphoreWaitSuccessTimer.record(waitDuration)
        semaphoreWaitCounterFinish.increment()

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

        val retriesCount = 3
        try {
            val request = Request.Builder().run {
                url("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
                post(emptyBody)
            }.build()

            for (attempt in 0 until retriesCount) {
                if (now() + requestAverageProcessingTime.toMillis() > deadline) {
                    throw Exception("Not enough time for payment $paymentId!")
                }

                var successfulRequest = false
                client.newCall(request).execute().use { response ->
                    val body = try {
                        mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
                    } catch (e: Exception) {
                        logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                        ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                    }
                    successfulRequest = body.result

                    logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, time left: ${deadline - now()}, attempt: $attempt, message: ${body.message}")

                    // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                    // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                    paymentESService.update(paymentId) {
                        it.logProcessing(body.result, now(), transactionId, reason = body.message)
                    }
                }
                if (successfulRequest) {
                    break
                }
            }
        } catch (e: Exception) {
            when (e) {
                is SocketTimeoutException -> {
                    logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
                    }
                }

                else -> {
                    logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)

                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = e.message)
                    }
                }
            }
        } finally {
            parallelRequestsLimiter.releaseRequest()
            logger.info("Payment: $paymentId finished and released lock, time left: ${deadline - now()}")
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

public fun now() = System.currentTimeMillis()