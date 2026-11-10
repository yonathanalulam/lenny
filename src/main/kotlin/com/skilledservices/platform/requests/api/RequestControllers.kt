package com.skilledservices.platform.requests.api

import com.skilledservices.platform.requests.application.AttachmentUpload
import com.skilledservices.platform.requests.application.RequestService
import com.skilledservices.platform.shared.api.PageResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.nio.charset.StandardCharsets
import java.util.UUID

@RestController
@RequestMapping("/api/v1/customers/me/requests")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer service requests")
class CustomerRequestController(private val service: RequestService) {
    @PostMapping
    @Operation(summary = "Create a Direct Invitation request or private Job Posting draft")
    fun create(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: CreateServiceRequestRequest) =
        service.create(jwt.accountId(), request.toCommand()).toCustomerResponse()

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int): PageResponse<CustomerServiceRequestResponse> {
        val result = service.listForCustomer(jwt.accountId(), page, size)
        return PageResponse(result.items.map { it.toCustomerResponse() }, result.page, result.size, result.totalItems, result.totalPages, result.hasNext)
    }

    @GetMapping("/{requestId}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable requestId: UUID) = service.getForCustomer(jwt.accountId(), requestId).toCustomerResponse()

    @PatchMapping("/{requestId}")
    fun update(@AuthenticationPrincipal jwt: Jwt, @PathVariable requestId: UUID, @Valid @RequestBody request: UpdateServiceRequestRequest) =
        service.update(jwt.accountId(), requestId, request.toCommand()).toCustomerResponse()

    @PostMapping("/{requestId}/request-publication")
    @Operation(summary = "Move a Job Posting draft to AWAITING_POSTING_FEE without publishing it")
    fun requestPublication(@AuthenticationPrincipal jwt: Jwt, @PathVariable requestId: UUID) =
        service.requestPublication(jwt.accountId(), requestId).toCustomerResponse()

    @PostMapping("/{requestId}/cancel")
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @PathVariable requestId: UUID) = service.cancel(jwt.accountId(), requestId).toCustomerResponse()

    @PostMapping("/{requestId}/attachments", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(summary = "Attach a protected photo or document to a service request")
    fun attach(@AuthenticationPrincipal jwt: Jwt, @PathVariable requestId: UUID, @RequestPart("file") file: MultipartFile) =
        service.uploadAttachment(jwt.accountId(), requestId, AttachmentUpload(file.originalFilename ?: "attachment", file.contentType ?: "application/octet-stream", file.bytes)).toResponse()
}

@RestController
@RequestMapping("/api/v1/providers/me/invitations")
@PreAuthorize("hasRole('SERVICE_PROVIDER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Provider Direct Invitations")
class ProviderInvitationController(private val service: RequestService) {
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int): PageResponse<ProviderInvitationResponse> {
        val result = service.listForProvider(jwt.accountId(), page, size)
        return PageResponse(result.items.map { it.toProviderResponse() }, result.page, result.size, result.totalItems, result.totalPages, result.hasNext)
    }

    @GetMapping("/{invitationId}")
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable invitationId: UUID) = service.getForProvider(jwt.accountId(), invitationId).toProviderResponse()
}

@RestController
@RequestMapping("/api/v1/requests/attachments")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Protected request attachments")
class RequestAttachmentController(private val service: RequestService) {
    @GetMapping("/{attachmentId}/content")
    @Operation(summary = "Download a request attachment as its customer, invited provider, or administrator")
    @ApiResponse(responseCode = "403", description = "Attachment is not accessible to the current account")
    fun download(@AuthenticationPrincipal jwt: Jwt, @PathVariable attachmentId: UUID): ResponseEntity<ByteArray> {
        val download = service.downloadAttachment(jwt.accountId(), attachmentId)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(download.originalFilename, StandardCharsets.UTF_8).build().toString())
            .contentLength(download.content.size.toLong()).body(download.content)
    }
}

private fun Jwt.accountId(): UUID = UUID.fromString(subject)
