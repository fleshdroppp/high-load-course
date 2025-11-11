package ru.quipy.payments.client

import com.fasterxml.jackson.core.type.TypeReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.quipy.common.utils.logger
import ru.quipy.common.utils.onlineShopObjectMapper
import ru.quipy.payments.exception.ClientException
import ru.quipy.payments.logic.ExternalSysResponse
import ru.quipy.payments.logic.PaymentAccountProperties
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

class ExternalPaymentClient(
    private val properties: PaymentAccountProperties,
    private val paymentProviderHostPort: String,
    private val paymentToken: String,
    private val retryAmount: Int,
    private val clock: Clock,
) {
    init {
        require(retryAmount >= 0) { "Retry amount must be >=0" }
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(properties.averageProcessingTime.toMillis(), TimeUnit.MILLISECONDS)
        .build()

    fun executePayment(
        transactionId: UUID,
        paymentId: UUID,
        amount: Int,
        deadline: Instant,
    ): ExternalSysResponse {
        val request = Request.Builder().run {
            url(buildRequestUrl(transactionId.toString(), paymentId.toString(), amount.toString()))
            post(EMPTY_BODY)
        }.build()

        return executeWithRetries(request, deadline).orDefaultError(transactionId, paymentId)
    }

    private fun executeWithRetries(request: Request, deadline: Instant): ExternalSysResponse? {
        val cnt = 0
        var response: Response? = null

        while (cnt <= retryAmount) {
            if (clock.instant().plus(properties.averageProcessingTime) > deadline) {
                throw ClientException("Not enough time for payment")
            }

            response?.close()
            response = executeRequest(request)
            if (response?.isSuccessful == true) break
        }

        requireNotNull(response)
        if (!response.isSuccessful) {
            throw ClientException("Could not get successful response with $retryAmount retries")
        }

        return response.body?.string().toExternalSysResponse().also { response.close() }
    }

    private fun executeRequest(request: Request): Response? {
        return try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            when (e) {
                is InterruptedIOException -> null
                else -> throw e
            }
        }
    }

    private fun buildRequestUrl(transactionId: String, paymentId: String, amount: String): String {
        val sb = StringBuilder()

        sb.append("http://")
            .append(paymentProviderHostPort)
            .append("/external/process")
            .append("?serviceName=").append(properties.serviceName)
            .append("&token=").append(paymentToken)
            .append("&accountName=").append(properties.accountName)
            .append("&transactionId=").append(transactionId)
            .append("&paymentId=").append(paymentId)
            .append("&amount=").append(amount)

        return sb.toString()
    }

    private fun String?.toExternalSysResponse(): ExternalSysResponse? {
        this ?: return null

        return try {
            objectMapper.readValue(this, EXTERNAL_SYS_RESPONSE_TR)
        } catch (_: Exception) {
            logger.error("Bad response: {}", this)
            null
        }
    }

    private fun ExternalSysResponse?.orDefaultError(
        transactionId: UUID,
        paymentId: UUID,
        message: String? = null,
    ): ExternalSysResponse {
        return this ?: ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, message)
    }

    companion object {
        private val logger = logger()
        private val objectMapper = onlineShopObjectMapper()
        private val EXTERNAL_SYS_RESPONSE_TR = object : TypeReference<ExternalSysResponse>() {}

        private val EMPTY_BODY = "".toRequestBody()
    }
}