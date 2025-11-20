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
        100,
        100,
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
            logger.info("Payment ${createdEvent.paymentId} for order $orderId created. Time left: ${deadline - now()}ms")
            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
            logger.info("Order $orderId payment $paymentId was fully processed, time left: ${deadline - now()}ms")
        }
        return createdAt
    }

    companion object {
        private val logger = logger()
    }
}
