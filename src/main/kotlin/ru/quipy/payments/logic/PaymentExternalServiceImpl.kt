package ru.quipy.payments.logic

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
import java.util.concurrent.Executors


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val externalPaymentClient: ExternalPaymentClient,
) : PaymentExternalSystemAdapter {

    private val updateExecutor = Executors.newVirtualThreadPerTaskExecutor()

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

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        val now = now()
        updateExecutor.execute {
            paymentESService.update(paymentId) {
                it.logSubmission(success = true, transactionId, now, Duration.ofMillis(now - paymentStartedAt))
            }
            logger.error("DEBUG 59L: ${now() - now}")
        }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

        try {
            val externalSysResponse =
                externalPaymentClient.executePayment(transactionId, paymentId, amount, deadlineInstant)

            updateExecutor.execute {
                paymentESService.update(paymentId) {
                    it.logProcessing(externalSysResponse.result, now, transactionId, reason = externalSysResponse.message)
                }
                logger.error("DEBUG 72L: ${now() - now}")
            }
        } catch (e: Exception) {
            when (e) {
                is SocketTimeoutException -> {
                    logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
                    updateExecutor.execute {
                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now, transactionId, reason = "Request timeout.")
                        }
                        logger.error("DEBUG 82L: ${now() - now}")
                    }
                }

                else -> {
                    logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)

                    updateExecutor.execute {
                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now, transactionId, reason = e.message)
                        }
                        logger.error("DEBUG 93L: ${now() - now}")
                    }
                }
            }
        } finally {
            logger.info("Payment: $paymentId finished and released lock, time left: ${deadline - now}")
        }
    }

    companion object {
        private val logger = logger()
    }
}

fun now() = System.currentTimeMillis()