package ru.quipy.payments.logic

import io.github.resilience4j.ratelimiter.RateLimiter
import org.springframework.stereotype.Service
import ru.quipy.common.utils.CallerBlockingRejectedExecutionHandler
import ru.quipy.common.utils.MdcExecutorDecorator.Companion.decorateWithMdc
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.common.utils.exhausting
import ru.quipy.common.utils.logger
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service
class OrderPayer(
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentService: PaymentService,
    outboundPaymentRateLimiter: RateLimiter,
) {
    private val paymentExecutor = ThreadPoolExecutor(
        50,
        50,
        0L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(8000),
        NamedThreadFactory("payment-submission-executor"),
        CallerBlockingRejectedExecutionHandler()
    ).decorateWithMdc()

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        paymentExecutor.execute {
            val createdEvent = paymentESService.create {
                it.create(
                    paymentId,
                    orderId,
                    amount
                )
            }
            logger.trace("Payment {} for order {} created.", createdEvent.paymentId, orderId)
            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
        }
        return createdAt
    }

    companion object {
        private val logger = logger()
    }
}
