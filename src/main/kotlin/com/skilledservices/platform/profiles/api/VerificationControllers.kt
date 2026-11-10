package com.skilledservices.platform.profiles.api

import com.skilledservices.platform.profiles.application.VerificationTrustService
import com.skilledservices.platform.profiles.domain.MarketplaceSubjectType
import com.skilledservices.platform.profiles.domain.MarketplaceVerificationStatus
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.nio.charset.StandardCharsets
import java.util.UUID

@RestController
@RequestMapping("/api/v1/customers/me/verification")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer verification")
class CustomerVerificationController(private val service: VerificationTrustService) {
    @GetMapping
    @Operation(summary = "Get the current customer's marketplace-verification state")
    fun get(@AuthenticationPrincipal jwt: Jwt) =
        service.getForCurrent(jwt.accountId(), MarketplaceSubjectType.CUSTOMER).toResponse(false)

    @PostMapping("/submissions", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(
        summary = "Submit customer verification evidence",
        description = "The JSON metadata.evidence list and files list are matched by list position.",
    )
    @ApiResponse(responseCode = "200", description = "Verification submission is pending review")
    fun submit(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestPart("metadata") metadata: VerificationSubmissionRequest,
        @RequestPart("files") files: List<MultipartFile>,
    ) = service.submit(jwt.accountId(), MarketplaceSubjectType.CUSTOMER, verificationUploads(metadata, files)).toResponse(false)
}

@RestController
@RequestMapping("/api/v1/providers/me/verification")
@PreAuthorize("hasRole('SERVICE_PROVIDER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Provider verification")
class ProviderVerificationController(private val service: VerificationTrustService) {
    @GetMapping
    @Operation(summary = "Get the current provider's marketplace-verification state")
    fun get(@AuthenticationPrincipal jwt: Jwt) =
        service.getForCurrent(jwt.accountId(), MarketplaceSubjectType.PROVIDER).toResponse(false)

    @PostMapping("/submissions", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(
        summary = "Submit provider verification evidence",
        description = "The JSON metadata.evidence list and files list are matched by list position.",
    )
    fun submit(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestPart("metadata") metadata: VerificationSubmissionRequest,
        @RequestPart("files") files: List<MultipartFile>,
    ) = service.submit(jwt.accountId(), MarketplaceSubjectType.PROVIDER, verificationUploads(metadata, files)).toResponse(false)
}

@RestController
@RequestMapping("/api/v1/admin/verifications")
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Verification administration")
class AdminVerificationController(private val service: VerificationTrustService) {
    @GetMapping
    @Operation(summary = "List submitted verifications for administrative review")
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(required = false) subjectType: MarketplaceSubjectType?,
        @RequestParam(required = false) status: MarketplaceVerificationStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = service.listForAdmin(jwt.accountId(), subjectType, status, page, size).toResponse()

    @GetMapping("/{verificationId}")
    @Operation(summary = "Get a verification submission, evidence metadata, and decision history")
    fun get(@AuthenticationPrincipal jwt: Jwt, @PathVariable verificationId: UUID) =
        service.getForAdmin(jwt.accountId(), verificationId).toResponse(true)

    @PostMapping("/{verificationId}/decision")
    @Operation(summary = "Approve or reject a submitted pending verification")
    fun decide(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable verificationId: UUID,
        @Valid @RequestBody request: VerificationDecisionRequest,
    ) = service.decide(jwt.accountId(), verificationId, request.decision, request.reason).toResponse(true)
}

@RestController
@RequestMapping("/api/v1/verifications/evidence")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Protected verification evidence")
class VerificationEvidenceController(private val service: VerificationTrustService) {
    @GetMapping("/{evidenceId}/content")
    @Operation(summary = "Download evidence owned by the current user or accessible to an administrator")
    @ApiResponse(responseCode = "403", description = "Evidence belongs to another user")
    fun download(@AuthenticationPrincipal jwt: Jwt, @PathVariable evidenceId: UUID): ResponseEntity<ByteArray> {
        val download = service.downloadEvidence(jwt.accountId(), evidenceId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(download.contentType))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(download.originalFilename, StandardCharsets.UTF_8).build().toString(),
            )
            .contentLength(download.content.size.toLong())
            .body(download.content)
    }
}
