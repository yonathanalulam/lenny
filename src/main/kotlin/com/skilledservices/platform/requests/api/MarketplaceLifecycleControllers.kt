package com.skilledservices.platform.requests.api

import com.skilledservices.platform.requests.application.*
import com.skilledservices.platform.shared.api.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/customers/me/requests/{requestId}/posting-fee")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Posting Fee")
class PostingFeeController(private val service: PostingFeePaymentService) {
    @PostMapping("/payments")
    @Operation(summary = "Initiate the configured Posting Fee payment")
    fun initiate(@AuthenticationPrincipal jwt: Jwt, @PathVariable requestId: UUID) = service.initiate(jwt.accountId(), requestId).toResponse()

    @GetMapping
    @Operation(summary = "Get Posting Fee amount, transaction status, and publication state")
    fun status(@AuthenticationPrincipal jwt: Jwt, @PathVariable requestId: UUID) = service.status(jwt.accountId(), requestId).toResponse()
}

@RestController
@RequestMapping("/api/v1/payment-webhooks")
@Tag(name = "Payment provider callbacks")
class PaymentWebhookController(private val service: PostingFeePaymentService) {
    @PostMapping("/{provider}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Receive and verify a payment-provider event")
    fun callback(@PathVariable provider: String, @RequestBody body: ByteArray, request: HttpServletRequest): PaymentWebhookResponse {
        val headers = request.headerNames.toList().associate { it.lowercase() to request.getHeader(it) }
        val result = service.processCallback(provider, body, headers)
        return PaymentWebhookResponse(result.payment.id, result.payment.status, result.request.status == com.skilledservices.platform.requests.domain.ServiceRequestStatus.OPEN)
    }
}

@RestController
@RequestMapping("/api/v1/providers/me/jobs")
@PreAuthorize("hasRole('SERVICE_PROVIDER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Provider Job Posting feed")
class ProviderJobController(private val service: ProviderJobService) {
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int): PageResponse<ProviderJobResponse> {
        val result = service.list(jwt.accountId(), page, size)
        return PageResponse(result.items.map { it.toResponse() }, result.page, result.size, result.totalItems, result.totalPages, result.hasNext)
    }
    @GetMapping("/{requestId}") fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable requestId: UUID) = service.get(jwt.accountId(), requestId).toResponse()
}

@RestController
@RequestMapping("/api/v1/providers/me/invitations/{invitationId}")
@PreAuthorize("hasRole('SERVICE_PROVIDER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Invitation responses")
class InvitationResponseController(private val service: InvitationResponseService) {
    @PostMapping("/accept") @Operation(summary = "Accept interest in a pending invitation; does not create a proposal or booking")
    fun accept(@AuthenticationPrincipal jwt: Jwt, @PathVariable invitationId: UUID) = service.accept(jwt.accountId(), invitationId).toStateResponse()
    @PostMapping("/decline") fun decline(@AuthenticationPrincipal jwt: Jwt, @PathVariable invitationId: UUID) = service.decline(jwt.accountId(), invitationId).toStateResponse()
}

@RestController
@RequestMapping("/api/v1/providers/me")
@PreAuthorize("hasRole('SERVICE_PROVIDER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Provider proposals")
class ProviderProposalController(private val service: ProposalService) {
    @PostMapping("/requests/{requestId}/proposals")
    @Operation(summary = "Submit one proposal for an accepted invitation or eligible OPEN Job Posting")
    fun submit(@AuthenticationPrincipal jwt: Jwt, @PathVariable requestId: UUID, @Valid @RequestBody request: SubmitProposalRequest) =
        service.submit(jwt.accountId(), requestId, request.toCommand()).toResponse()

    @PostMapping("/proposals/{proposalId}/withdraw")
    fun withdraw(@AuthenticationPrincipal jwt: Jwt, @PathVariable proposalId: UUID) = service.withdraw(jwt.accountId(), proposalId).toResponse()
}

@RestController
@RequestMapping("/api/v1/customers/me")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer proposal review")
class CustomerProposalController(private val service: ProposalService) {
    @GetMapping("/requests/{requestId}/proposals")
    fun list(@AuthenticationPrincipal jwt: Jwt, @PathVariable requestId: UUID) = service.listForCustomer(jwt.accountId(), requestId).map { it.toResponse() }
    @GetMapping("/proposals/{proposalId}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable proposalId: UUID) = service.getForCustomer(jwt.accountId(), proposalId).toResponse()
    @PostMapping("/proposals/{proposalId}/reject")
    @Operation(summary = "Reject a pending proposal; final acceptance is intentionally unavailable until Booking")
    fun reject(@AuthenticationPrincipal jwt: Jwt, @PathVariable proposalId: UUID) = service.reject(jwt.accountId(), proposalId).toResponse()
}

private fun Jwt.accountId(): UUID = UUID.fromString(subject)
