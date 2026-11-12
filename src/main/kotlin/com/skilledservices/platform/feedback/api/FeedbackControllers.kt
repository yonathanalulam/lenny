package com.skilledservices.platform.feedback.api

import com.skilledservices.platform.feedback.application.*
import com.skilledservices.platform.feedback.domain.*
import com.skilledservices.platform.shared.api.PageResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

data class SubmitFeedbackRequest(@field:Min(1) @field:Max(5) val rating: Int,
    @field:Size(max = 2000) val comment: String? = null)
data class ModerateFeedbackRequest(val visible: Boolean, @field:Size(max = 1000) val reason: String? = null)
data class FeedbackResponse(val id: UUID, val bookingId: UUID, val direction: FeedbackDirection, val rating: Int,
    val comment: String?, val visible: Boolean, val createdAt: Instant)
data class PublicProviderReviewResponse(val id: UUID, val rating: Int, val comment: String?,
    val reviewerLabel: String = "Verified customer", val createdAt: Instant)
data class AdminFeedbackResponse(val id: UUID, val bookingId: UUID, val raterAccountId: UUID, val rateeAccountId: UUID,
    val direction: FeedbackDirection, val rating: Int, val comment: String?, val visible: Boolean,
    val moderationReason: String?, val moderatedBy: UUID?, val moderatedAt: Instant?, val createdAt: Instant)

@RestController
@RequestMapping("/api/v1/customers/me/bookings")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
class CustomerFeedbackController(private val service: FeedbackService) {
    @PostMapping("/{bookingId}/provider-feedback") @ResponseStatus(HttpStatus.CREATED)
    fun submit(@AuthenticationPrincipal jwt: Jwt, @PathVariable bookingId: UUID, @Valid @RequestBody request: SubmitFeedbackRequest) =
        service.customerToProvider(jwt.accountId(), bookingId, SubmitFeedbackCommand(request.rating, request.comment)).toResponse()
}

@RestController
@RequestMapping("/api/v1/providers/me/bookings")
@PreAuthorize("hasRole('SERVICE_PROVIDER')")
@SecurityRequirement(name = "bearerAuth")
class ProviderFeedbackController(private val service: FeedbackService) {
    @PostMapping("/{bookingId}/customer-feedback") @ResponseStatus(HttpStatus.CREATED)
    fun submit(@AuthenticationPrincipal jwt: Jwt, @PathVariable bookingId: UUID, @Valid @RequestBody request: SubmitFeedbackRequest) =
        service.providerToCustomer(jwt.accountId(), bookingId, SubmitFeedbackCommand(request.rating, request.comment)).toResponse()
}

@RestController
@RequestMapping("/api/v1/providers/{providerId}/reviews")
class PublicProviderReviewController(private val service: FeedbackService) {
    @GetMapping
    fun list(@PathVariable providerId: UUID, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int) = service.publicProviderReviews(providerId, page, size).map { it.toPublicResponse() }
}

@RestController
@RequestMapping("/api/v1/admin/feedback")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
class AdminFeedbackController(private val service: FeedbackService) {
    @GetMapping fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) =
        service.adminList(page, size).map { it.toAdminResponse() }
    @PostMapping("/{feedbackId}/moderation")
    fun moderate(@AuthenticationPrincipal jwt: Jwt, @PathVariable feedbackId: UUID,
        @Valid @RequestBody request: ModerateFeedbackRequest) =
        service.moderate(jwt.accountId(), feedbackId, ModerateFeedbackCommand(request.visible, request.reason)).toAdminResponse()
}

private fun BookingFeedback.toResponse() = FeedbackResponse(id, bookingId, direction, rating, comment, visible, createdAt)
private fun BookingFeedback.toPublicResponse() = PublicProviderReviewResponse(id, rating, comment, createdAt = createdAt)
private fun BookingFeedback.toAdminResponse() = AdminFeedbackResponse(id, bookingId, raterAccountId, rateeAccountId,
    direction, rating, comment, visible, moderationReason, moderatedBy, moderatedAt, createdAt)
private fun Jwt.accountId() = UUID.fromString(subject)
private fun <T, R> PageResponse<T>.map(transform: (T) -> R) = PageResponse(items.map(transform), page, size, totalItems, totalPages, hasNext)
