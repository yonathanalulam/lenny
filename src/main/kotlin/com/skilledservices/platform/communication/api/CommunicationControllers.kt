package com.skilledservices.platform.communication.api

import com.skilledservices.platform.communication.application.*
import com.skilledservices.platform.communication.domain.*
import com.skilledservices.platform.shared.api.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

data class CreateConversationRequest(val requestId: UUID? = null, val bookingId: UUID? = null, val counterpartAccountId: UUID? = null)
data class SendMessageRequest(@field:NotBlank @field:Size(max = 4000) val body: String)
data class ConversationResponse(val id: UUID, val requestId: UUID?, val bookingId: UUID?, val customerId: UUID,
    val providerId: UUID, val state: ConversationState, val createdAt: Instant, val lastActivityAt: Instant)
data class MessageResponse(val id: UUID, val conversationId: UUID, val senderAccountId: UUID, val body: String,
    val sentAt: Instant, val readAt: Instant?)
data class NotificationResponse(val id: UUID, val type: String, val title: String, val message: String,
    val relatedEntityType: String?, val relatedEntityId: UUID?, val createdAt: Instant, val readAt: Instant?)
data class CreateBlockRequest(val blockedAccountId: UUID, val reasonCategory: BlockReason? = null)
data class BlockResponse(val blockedAccountId: UUID, val reasonCategory: BlockReason?, val createdAt: Instant)
data class CreateReportRequest(val reportedAccountId: UUID, val relatedEntityType: RelatedEntityType? = null,
    val relatedEntityId: UUID? = null, val category: ReportCategory,
    @field:NotBlank @field:Size(max = 2000) val description: String)
data class ReviewReportRequest(val status: ReportStatus, @field:Size(max = 1000) val moderationNote: String? = null)
data class ReportResponse(val id: UUID, val reporterAccountId: UUID, val reportedAccountId: UUID,
    val relatedEntityType: RelatedEntityType?, val relatedEntityId: UUID?, val category: ReportCategory,
    val description: String, val status: ReportStatus, val createdAt: Instant, val reviewedAt: Instant?, val moderationNote: String?)
data class CreateSupportCaseRequest(val bookingId: UUID? = null, val requestId: UUID? = null, val type: SupportCaseType,
    @field:NotBlank @field:Size(max = 4000) val description: String)
data class ReviewSupportCaseRequest(val status: SupportCaseStatus, @field:Size(max = 2000) val internalNote: String? = null,
    @field:Size(max = 1000) val resolutionOutcome: String? = null)
data class SupportCaseResponse(val id: UUID, val creatorAccountId: UUID, val bookingId: UUID?, val requestId: UUID?,
    val type: SupportCaseType, val description: String, val status: SupportCaseStatus, val createdAt: Instant,
    val updatedAt: Instant, val assignedAdminId: UUID?, val internalNote: String?, val resolutionOutcome: String?, val resolvedAt: Instant?)
data class PreferencesResponse(val preferredLanguage: PreferredLanguage, val marketplaceNotificationsEnabled: Boolean,
    val communicationNotificationsEnabled: Boolean, val supportNotificationsEnabled: Boolean, val updatedAt: Instant?)
data class UpdatePreferencesRequest(val preferredLanguage: PreferredLanguage,
    val marketplaceNotificationsEnabled: Boolean = true, val communicationNotificationsEnabled: Boolean = true,
    val supportNotificationsEnabled: Boolean = true)

@RestController
@RequestMapping("/api/v1/me/conversations")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
class ConversationController(private val service: ConversationService) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open or return a conversation backed by a marketplace relationship")
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: CreateConversationRequest) =
        service.create(jwt.accountId(), CreateConversationCommand(request.requestId, request.bookingId, request.counterpartAccountId)).toResponse()

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int) = service.list(jwt.accountId(), page, size).map { it.toResponse() }

    @GetMapping("/{conversationId}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable conversationId: UUID) = service.get(jwt.accountId(), conversationId).toResponse()

    @GetMapping("/{conversationId}/messages")
    fun messages(@AuthenticationPrincipal jwt: Jwt, @PathVariable conversationId: UUID,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int) =
        service.messages(jwt.accountId(), conversationId, page, size).map { it.toResponse() }

    @PostMapping("/{conversationId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    fun send(@AuthenticationPrincipal jwt: Jwt, @PathVariable conversationId: UUID,
        @Valid @RequestBody request: SendMessageRequest) = service.send(jwt.accountId(), conversationId, SendMessageCommand(request.body)).toResponse()

    @PostMapping("/{conversationId}/read")
    fun read(@AuthenticationPrincipal jwt: Jwt, @PathVariable conversationId: UUID) =
        mapOf("updatedMessages" to service.markRead(jwt.accountId(), conversationId))
}

@RestController
@RequestMapping("/api/v1/me/notifications")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
class NotificationController(private val service: NotificationService) {
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "false") unreadOnly: Boolean,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) =
        service.list(jwt.accountId(), unreadOnly, page, size).map { it.toResponse() }
    @PostMapping("/{notificationId}/read")
    fun read(@AuthenticationPrincipal jwt: Jwt, @PathVariable notificationId: UUID) = service.markRead(jwt.accountId(), notificationId).toResponse()
    @PostMapping("/read-all")
    fun readAll(@AuthenticationPrincipal jwt: Jwt) = mapOf("updatedNotifications" to service.markAllRead(jwt.accountId()))
}

@RestController
@RequestMapping("/api/v1/me/blocks")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
class BlockingController(private val service: BlockingService) {
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: CreateBlockRequest) =
        service.create(jwt.accountId(), CreateBlockCommand(request.blockedAccountId, request.reasonCategory)).toResponse()
    @GetMapping fun list(@AuthenticationPrincipal jwt: Jwt) = service.list(jwt.accountId()).map { it.toResponse() }
    @DeleteMapping("/{blockedAccountId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@AuthenticationPrincipal jwt: Jwt, @PathVariable blockedAccountId: UUID) = service.remove(jwt.accountId(), blockedAccountId)
}

@RestController
@RequestMapping("/api/v1/me/reports")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
class ReportController(private val service: ReportService) {
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: CreateReportRequest) =
        service.create(jwt.accountId(), request.toCommand()).toResponse()
    @GetMapping fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int) = service.listMine(jwt.accountId(), page, size).map { it.toResponse() }
}

@RestController
@RequestMapping("/api/v1/me/support-cases")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
class SupportCaseController(private val service: SupportCaseService) {
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: CreateSupportCaseRequest) =
        service.create(jwt.accountId(), CreateSupportCaseCommand(request.bookingId, request.requestId, request.type, request.description)).toUserResponse()
    @GetMapping fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int) = service.listMine(jwt.accountId(), page, size).map { it.toUserResponse() }
    @GetMapping("/{caseId}") fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable caseId: UUID) = service.getMine(jwt.accountId(), caseId).toUserResponse()
}

@RestController
@RequestMapping("/api/v1/me/preferences")
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "bearerAuth")
class PreferencesController(private val service: PreferencesService) {
    @GetMapping fun get(@AuthenticationPrincipal jwt: Jwt) = service.get(jwt.accountId()).toResponse()
    @PutMapping fun update(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: UpdatePreferencesRequest) =
        service.update(jwt.accountId(), UpdatePreferencesCommand(request.preferredLanguage,
            request.marketplaceNotificationsEnabled, request.communicationNotificationsEnabled,
            request.supportNotificationsEnabled)).toResponse()
}

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
class CommunicationAdminController(private val reports: ReportService, private val support: SupportCaseService) {
    @GetMapping("/reports") fun reports(@RequestParam(required = false) status: ReportStatus?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) = reports.adminList(status, page, size).map { it.toResponse() }
    @PostMapping("/reports/{reportId}/review") fun reviewReport(@AuthenticationPrincipal jwt: Jwt,
        @PathVariable reportId: UUID, @Valid @RequestBody request: ReviewReportRequest) =
        reports.review(jwt.accountId(), reportId, ReviewReportCommand(request.status, request.moderationNote)).toResponse()
    @GetMapping("/support-cases") fun supportCases(@RequestParam(required = false) status: SupportCaseStatus?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) = support.adminList(status, page, size).map { it.toAdminResponse() }
    @GetMapping("/support-cases/{caseId}") fun supportCase(@PathVariable caseId: UUID) = support.adminGet(caseId).toAdminResponse()
    @PostMapping("/support-cases/{caseId}/review") fun reviewSupport(@AuthenticationPrincipal jwt: Jwt,
        @PathVariable caseId: UUID, @Valid @RequestBody request: ReviewSupportCaseRequest) =
        support.review(jwt.accountId(), caseId, ReviewSupportCaseCommand(request.status, request.internalNote, request.resolutionOutcome)).toAdminResponse()
}

private fun CreateReportRequest.toCommand() = CreateReportCommand(reportedAccountId, relatedEntityType, relatedEntityId, category, description)
private fun Conversation.toResponse() = ConversationResponse(id, requestId, bookingId, customerId, providerId, state, createdAt, lastActivityAt)
private fun Message.toResponse() = MessageResponse(id, conversationId, senderAccountId, body, sentAt, readAt)
private fun Notification.toResponse() = NotificationResponse(id, type, title, message, relatedEntityType, relatedEntityId, createdAt, readAt)
private fun UserBlock.toResponse() = BlockResponse(blockedAccountId, reasonCategory, createdAt)
private fun UserReport.toResponse() = ReportResponse(id, reporterAccountId, reportedAccountId, relatedEntityType, relatedEntityId, category, description, status, createdAt, reviewedAt, moderationNote)
private fun SupportCase.toUserResponse() = SupportCaseResponse(id, creatorAccountId, bookingId, requestId, type, description, status, createdAt, updatedAt, assignedAdminId, null, resolutionOutcome, resolvedAt)
private fun SupportCase.toAdminResponse() = SupportCaseResponse(id, creatorAccountId, bookingId, requestId, type, description, status, createdAt, updatedAt, assignedAdminId, internalNote, resolutionOutcome, resolvedAt)
private fun AccountPreferences.toResponse() = PreferencesResponse(preferredLanguage, marketplaceNotificationsEnabled, communicationNotificationsEnabled, supportNotificationsEnabled, updatedAt.takeUnless { it == Instant.EPOCH })
private fun Jwt.accountId() = UUID.fromString(subject)
private fun <T, R> PageResponse<T>.map(transform: (T) -> R) = PageResponse(items.map(transform), page, size, totalItems, totalPages, hasNext)
