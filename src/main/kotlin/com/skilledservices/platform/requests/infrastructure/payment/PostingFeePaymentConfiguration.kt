package com.skilledservices.platform.requests.infrastructure.payment

import com.skilledservices.platform.requests.domain.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("platform.posting-fee")
data class PostingFeeProperties(
    override val amount: BigDecimal = BigDecimal("150.00"),
    override val currency: String = "ETB",
    val provider: String = "unavailable",
    val localTestWebhookSecret: String = "",
) : PostingFeePolicy

@Configuration
class PostingFeePaymentConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "platform.posting-fee", name = ["provider"], havingValue = "local-test")
    @Profile("!prod")
    fun localTestPaymentGateway(properties: PostingFeeProperties, objectMapper: ObjectMapper): PostingFeePaymentGateway =
        LocalTestPostingFeePaymentGateway(properties.localTestWebhookSecret, objectMapper)

    @Bean
    @ConditionalOnMissingBean(PostingFeePaymentGateway::class)
    @ConditionalOnProperty(prefix = "platform.posting-fee", name = ["provider"], havingValue = "unavailable", matchIfMissing = true)
    fun unavailablePaymentGateway(): PostingFeePaymentGateway = UnavailablePostingFeePaymentGateway()
}

class UnavailablePostingFeePaymentGateway : PostingFeePaymentGateway {
    override val providerIdentifier = "unavailable"
    override val available = false
    override fun initiate(paymentId: UUID, requestId: UUID, amount: BigDecimal, currency: String, idempotencyKey: UUID): PaymentInitiationResult =
        throw PaymentProviderUnavailableException()
    override fun verifyCallback(provider: String, body: ByteArray, headers: Map<String, String>): VerifiedPaymentEvent =
        throw PaymentCallbackInvalidException()
}

private data class LocalVerifiedCallback(
    val eventId: String,
    val paymentId: UUID,
    val providerReferenceId: String,
    val outcome: VerifiedPaymentOutcome,
    val amount: BigDecimal,
    val currency: String,
    val failureCode: String? = null,
)

class LocalTestPostingFeePaymentGateway(
    private val secret: String,
    private val objectMapper: ObjectMapper,
) : PostingFeePaymentGateway {
    init { require(secret.length >= 16) { "platform.posting-fee.local-test-webhook-secret must contain at least 16 characters" } }
    override val providerIdentifier = "local-test"
    override val available = true

    override fun initiate(paymentId: UUID, requestId: UUID, amount: BigDecimal, currency: String, idempotencyKey: UUID) =
        PaymentInitiationResult(
            providerReferenceId = "local-$paymentId",
            nextAction = PaymentNextAction(PaymentNextActionType.DISPLAY_INSTRUCTIONS, null, "Complete payment through the configured local test harness."),
        )

    override fun verifyCallback(provider: String, body: ByteArray, headers: Map<String, String>): VerifiedPaymentEvent {
        if (provider != providerIdentifier) throw PaymentCallbackInvalidException()
        val supplied = headers["x-local-payment-signature"] ?: throw PaymentCallbackInvalidException()
        val expected = hmac(body)
        val valid = runCatching {
            MessageDigest.isEqual(HexFormat.of().parseHex(supplied), HexFormat.of().parseHex(expected))
        }.getOrDefault(false)
        if (!valid) throw PaymentCallbackInvalidException()
        val callback = runCatching { objectMapper.readValue(body, LocalVerifiedCallback::class.java) }
            .getOrElse { throw PaymentCallbackInvalidException() }
        if (callback.eventId.isBlank() || callback.providerReferenceId.isBlank()) throw PaymentCallbackInvalidException()
        return VerifiedPaymentEvent(
            callback.paymentId, providerIdentifier, callback.providerReferenceId, callback.eventId,
            callback.outcome, callback.amount, callback.currency.uppercase(), callback.failureCode,
        )
    }

    private fun hmac(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal(body))
    }
}
