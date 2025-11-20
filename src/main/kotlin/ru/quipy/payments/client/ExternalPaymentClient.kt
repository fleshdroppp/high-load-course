package ru.quipy.payments.client

import com.fasterxml.jackson.core.type.TypeReference
import io.github.resilience4j.ratelimiter.RateLimiter
import kotlinx.coroutines.future.await
import ru.quipy.common.utils.logger
import ru.quipy.common.utils.onlineShopObjectMapper
import ru.quipy.payments.logic.ExternalSysResponse
import ru.quipy.payments.logic.PaymentAccountProperties
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.util.*

class ExternalPaymentClient(
    private val properties: PaymentAccountProperties,
    private val paymentProviderHostPort: String,
    private val paymentToken: String,
    private val retryAmount: Int,
    private val outboundRateLimiter: RateLimiter,
    private val clock: Clock,
) {
    init {
        require(retryAmount >= 0) { "Retry amount must be >=0" }
    }

    private val client = HttpClient.newBuilder().build()

    suspend fun executePayment(
        transactionId: UUID,
        paymentId: UUID,
        amount: Int,
        deadline: Instant,
    ): ExternalSysResponse {
        val request =
            HttpRequest.newBuilder(buildRequestUrl(transactionId.toString(), paymentId.toString(), amount.toString()))
                .POST(HttpRequest.BodyPublishers.ofString(EMPTY_BODY))
                .build()

        return executeWithRetries(request, deadline).orDefaultError(transactionId, paymentId)
    }

    private suspend fun executeWithRetries(request: HttpRequest, deadline: Instant): ExternalSysResponse? {
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        return response.body().toExternalSysResponse()
    }

    private fun buildRequestUrl(transactionId: String, paymentId: String, amount: String): URI {
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

        return URI(sb.toString())
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

        private const val EMPTY_BODY = ""
    }
}