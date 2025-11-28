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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service
class OrderPayer(
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentService: PaymentService,
) {
    private val paymentExecutor = ThreadPoolExecutor(
        130,
        130,
        1L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(8000),
        NamedThreadFactory("payment-submission-executor"),
        CallerBlockingRejectedExecutionHandler()
    ).decorateWithMdc().asCoroutineDispatcher()

    suspend fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()
        CoroutineScope(paymentExecutor).launch {
            val createdEvent = paymentESService.create {
                it.create(
                    paymentId,
                    orderId,
                    amount
                )
            }
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
