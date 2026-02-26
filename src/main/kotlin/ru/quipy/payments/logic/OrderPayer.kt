package ru.quipy.payments.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service
import ru.quipy.common.utils.CallerBlockingRejectedExecutionHandler
import ru.quipy.common.utils.MdcExecutorDecorator.Companion.decorateWithMdc
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.common.utils.logger
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service
class OrderPayer(
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentService: PaymentService,
) {
    private val paymentExecutor = ThreadPoolExecutor(
        50,
        50,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(1),
        NamedThreadFactory("payment-submission-executor"),
        CallerBlockingRejectedExecutionHandler()
    )

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        paymentExecutor.execute {
            val b = System.currentTimeMillis()
            val createdEvent = paymentESService.create {
                it.create(
                    paymentId,
                    orderId,
                    amount
                )
            }
            logger.error("DEBUG: created for ${now() - b}")
            logger.debug("Payment {} for order {} created. Time left: {}ms", createdEvent.paymentId, orderId, deadline - now())
            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
            logger.debug("Order {} payment {} was fully processed, time left: {}ms", orderId, paymentId, deadline - now())
        }
        return createdAt
    }

    companion object {
        private val logger = logger()
    }
}
