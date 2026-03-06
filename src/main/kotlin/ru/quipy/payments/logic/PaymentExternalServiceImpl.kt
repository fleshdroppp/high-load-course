package ru.quipy.payments.logic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.quipy.common.utils.MdcKeys
import ru.quipy.common.utils.logger
import ru.quipy.common.utils.withMdc
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.client.ExternalPaymentClient
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.*


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val externalPaymentClient: ExternalPaymentClient,
) : PaymentExternalSystemAdapter {

    private val accountName = properties.accountName

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

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

        try {
            externalPaymentClient.executePayment(transactionId, paymentId, amount, deadlineInstant, properties.averageProcessingTime)
        } catch (e: Exception) {
            when (e) {
                is SocketTimeoutException -> {
                    logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
                }
                else -> {
                    logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)
                }
            }
        } finally {
            logger.info("Payment: $paymentId finished and released lock, time left: ${deadline - now()}")
        }
    }

    companion object {
        private val logger = logger()
    }
}

fun now() = System.currentTimeMillis()