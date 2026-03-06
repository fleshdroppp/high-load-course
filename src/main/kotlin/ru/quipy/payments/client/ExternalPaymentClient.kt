package ru.quipy.payments.client

import com.fasterxml.jackson.core.type.TypeReference
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.quipy.common.utils.ParallelRequestsLimiter
import ru.quipy.common.utils.logger
import ru.quipy.common.utils.onlineShopObjectMapper
import ru.quipy.exception.ResourceExhaustedRetryableException
import ru.quipy.payments.logic.ExternalSysResponse
import ru.quipy.payments.logic.PaymentAccountProperties
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

class ExternalPaymentClient(
    private val properties: PaymentAccountProperties,
    private val paymentProviderHostPort: String,
    private val paymentToken: String,
    private val retryAmount: Int,
    private val clock: Clock,
    private val outboundRateLimiter: RateLimiter,
    private val outboundParallelRequestLimiter: ParallelRequestsLimiter,
    private val meterRegistry: MeterRegistry,
) {
    init {
        require(retryAmount >= 0) { "Retry amount must be >=0" }
    }

    private val clientExecutor = Executors.newFixedThreadPool(200)

    private val client = HttpClient.newBuilder()
        .executor(clientExecutor)
        .version(HttpClient.Version.HTTP_2).build()

    suspend fun executePayment(
        transactionId: UUID,
        paymentId: UUID,
        amount: Int,
        deadline: Instant,
        avgRequestProcessingTime: Duration,
    ): ExternalSysResponse {
        val request =
            HttpRequest.newBuilder(buildRequestUrl(transactionId.toString(), paymentId.toString(), amount.toString()))
                .POST(HttpRequest.BodyPublishers.ofString(EMPTY_BODY))
                .build()

        val hedgeDelay = (avgRequestProcessingTime.toMillis() * 0.1).toLong()

        return executeWithParallelLimitCheck(hedgeDelay, request, deadline).orDefaultError(transactionId, paymentId)
    }

    private suspend fun executeWithParallelLimitCheck(hedgeDelayMillis: Long, request: HttpRequest, deadline: Instant): ExternalSysResponse? {
        if (!outboundParallelRequestLimiter.tryToAddRequest(10)) {
            throw ResourceExhaustedRetryableException(1000)
        }

        try {
            return executeWithRetries(hedgeDelayMillis, request, deadline)
        } finally {
            outboundParallelRequestLimiter.releaseRequest()
        }
    }

    private suspend fun executeWithRetries(hedgeDelayMillis: Long, request: HttpRequest, deadline: Instant): ExternalSysResponse? {
        val remainingMillis = Duration.between(clock.instant(), deadline).toMillis()

        return withTimeoutOrNull(remainingMillis) {
            coroutineScope {
                val winner = CompletableDeferred<ExternalSysResponse?>()

                val hedgeJobs = (0..retryAmount).map { attempt ->
                    launch {
                        if (attempt > 0) {
                            delay(hedgeDelayMillis * attempt)
                        }

                        if (winner.isCompleted) return@launch

                        try {
                            RateLimiter.waitForPermission(outboundRateLimiter)
                        } catch (_: Exception) {
                            logger.warn("Rate limiter interrupted for hedge attempt {}", attempt)
                            return@launch
                        }

                        if (winner.isCompleted) return@launch

                        val sample = Timer.start(meterRegistry)
                        try {
                            val response = client
                                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                                .await()
                            winner.complete(response.body().toExternalSysResponse())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn("Hedge request attempt {} failed: {}", attempt, e.message)
                        } finally {
                            sample.stop(
                                meterRegistry.timer(
                                    "payment.external.request.duration",
                                    "account", properties.accountName,
                                )
                            )
                        }
                    }
                }

                launch {
                    hedgeJobs.joinAll()
                    winner.complete(null)
                }

                try {
                    winner.await()
                } finally {
                    coroutineContext.cancelChildren()
                }
            }
        }
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