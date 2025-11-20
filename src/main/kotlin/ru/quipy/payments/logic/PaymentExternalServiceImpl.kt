package ru.quipy.payments.logic

import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import ru.quipy.common.utils.MdcKeys
import ru.quipy.common.utils.ParallelRequestsLimiter
import ru.quipy.common.utils.logger
import ru.quipy.common.utils.withMdc
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.client.ExternalPaymentClient
import ru.quipy.payments.exception.TooManyParallelPaymentsException
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.*


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val parallelRequestsLimiter: ParallelRequestsLimiter,
    private val meterRegistry: MeterRegistry,
    private val externalPaymentClient: ExternalPaymentClient,
) : PaymentExternalSystemAdapter {

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

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

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

    override suspend fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val transactionId = UUID.randomUUID()

        withMdc(MdcKeys.PAYMENT_ID to paymentId, MdcKeys.TRANSACTION_ID to transactionId) {
            performPaymentAsyncInternal(paymentId, transactionId, amount, paymentStartedAt, deadline)
        }
    }

    private suspend fun performPaymentAsyncInternal(
        paymentId: UUID,
        transactionId: UUID,
        amount: Int,
        paymentStartedAt: Long,
        deadline: Long
    ) {
        logger.info("[$accountName] Submitting payment request for payment $paymentId, time left: ${deadline - now()}")

        val deadlineInstant = Instant.ofEpochMilli(deadline)

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

        try {
            val externalSysResponse =
                externalPaymentClient.executePayment(transactionId, paymentId, amount, deadlineInstant)

            paymentESService.update(paymentId) {
                it.logProcessing(externalSysResponse.result, now(), transactionId, reason = externalSysResponse.message)
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

    companion object {
        private val logger = logger()
    }
}

fun now() = System.currentTimeMillis()