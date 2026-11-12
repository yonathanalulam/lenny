package com.skilledservices.platform.shared.web

import com.skilledservices.platform.identity.domain.DuplicateContactException
import com.skilledservices.platform.identity.domain.IdentityException
import com.skilledservices.platform.identity.domain.InactiveAccountException
import com.skilledservices.platform.identity.domain.InvalidCredentialsException
import com.skilledservices.platform.identity.domain.InvalidRefreshTokenException
import com.skilledservices.platform.identity.domain.*
import jakarta.servlet.http.HttpServletRequest
import com.skilledservices.platform.profiles.domain.ForbiddenVerificationAccessException
import com.skilledservices.platform.profiles.domain.InvalidVerificationTransitionException
import com.skilledservices.platform.profiles.domain.ProfileNotFoundException
import com.skilledservices.platform.profiles.domain.ProfileRoleRequiredException
import com.skilledservices.platform.profiles.domain.ProfilesException
import com.skilledservices.platform.profiles.domain.UnsupportedUploadTypeException
import com.skilledservices.platform.profiles.domain.UploadTooLargeException
import com.skilledservices.platform.profiles.domain.VerificationAlreadyPendingException
import com.skilledservices.platform.profiles.domain.VerificationNotFoundException
import com.skilledservices.platform.marketplace.domain.CategoryDuplicateException
import com.skilledservices.platform.marketplace.domain.CategoryNotFoundException
import com.skilledservices.platform.marketplace.domain.MarketplaceException
import com.skilledservices.platform.marketplace.domain.ProviderNotPublicException
import com.skilledservices.platform.marketplace.domain.AiSuggestionUnavailableException
import com.skilledservices.platform.requests.domain.*
import com.skilledservices.platform.booking.domain.*
import com.skilledservices.platform.communication.domain.*
import com.skilledservices.platform.feedback.domain.*
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import java.net.URI

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionHandler {
    @ExceptionHandler(HandlerMethodValidationException::class)
    fun methodValidation(
        exception: HandlerMethodValidationException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = problem(
        status = HttpStatus.BAD_REQUEST,
        code = "request.validation_failed",
        detail = "One or more request fields are invalid",
        request = request,
    )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        status = HttpStatus.BAD_REQUEST,
        code = "request.invalid_body",
        detail = "The request body is missing or malformed",
        request = request,
    )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val errors = exception.bindingResult.fieldErrors
            .groupBy({ it.field }, { it.defaultMessage ?: "is invalid" })
        return problem(
            status = HttpStatus.BAD_REQUEST,
            code = "request.validation_failed",
            detail = "One or more request fields are invalid",
            request = request,
            extra = mapOf("errors" to errors),
        )
    }

    @ExceptionHandler(IdentityException::class)
    fun identity(exception: IdentityException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status = when (exception) {
            is DuplicateContactException -> HttpStatus.CONFLICT
            is GoogleAuthUnavailableException -> HttpStatus.SERVICE_UNAVAILABLE
            is GoogleAccountLinkRequiredException, is GoogleIdentityAlreadyLinkedException -> HttpStatus.CONFLICT
            is GoogleRegistrationRoleRequiredException, is GoogleIdentityEmailMismatchException,
            is AccountStatusChangeInvalidException -> HttpStatus.BAD_REQUEST
            is GoogleTokenInvalidException, is GoogleTokenExpiredException, is GoogleAudienceInvalidException,
            is GoogleEmailNotVerifiedException -> HttpStatus.UNAUTHORIZED
            is InvalidCredentialsException, is InvalidRefreshTokenException, is InactiveAccountException -> HttpStatus.UNAUTHORIZED
            else -> HttpStatus.NOT_FOUND
        }
        return problem(status, exception.code, exception.message ?: status.reasonPhrase, request)
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun constraintViolation(request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        problem(
            HttpStatus.CONFLICT,
            "request.conflict",
            "The request conflicts with existing data",
            request,
        )

    @ExceptionHandler(ProfilesException::class)
    fun profiles(exception: ProfilesException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status = when (exception) {
            is ProfileNotFoundException, is VerificationNotFoundException -> HttpStatus.NOT_FOUND
            is ForbiddenVerificationAccessException, is ProfileRoleRequiredException -> HttpStatus.FORBIDDEN
            is VerificationAlreadyPendingException, is InvalidVerificationTransitionException -> HttpStatus.CONFLICT
            is UnsupportedUploadTypeException -> HttpStatus.UNSUPPORTED_MEDIA_TYPE
            is UploadTooLargeException -> HttpStatus.PAYLOAD_TOO_LARGE
            else -> HttpStatus.BAD_REQUEST
        }
        return problem(status, exception.code, exception.message ?: status.reasonPhrase, request)
    }

    @ExceptionHandler(MarketplaceException::class)
    fun marketplace(exception: MarketplaceException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status = when (exception) {
            is CategoryNotFoundException, is ProviderNotPublicException -> HttpStatus.NOT_FOUND
            is CategoryDuplicateException -> HttpStatus.CONFLICT
            is AiSuggestionUnavailableException -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.BAD_REQUEST
        }
        return problem(status, exception.code, exception.message ?: status.reasonPhrase, request)
    }

    @ExceptionHandler(RequestsException::class)
    fun requests(exception: RequestsException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status = when (exception) {
            is RequestNotFoundException, is InvitationNotFoundException, is ProposalNotFoundException, is JobNotOpenException -> HttpStatus.NOT_FOUND
            is RequestNotOwnedException, is AttachmentAccessDeniedException, is CustomerNotEligibleException,
            is InvitationNotOwnedException, is ProposalNotOwnedException, is JobNotEligibleException -> HttpStatus.FORBIDDEN
            is ProviderNotEligibleException, is InvalidRequestStateException, is PostingFeeAlreadyPaidException,
            is InvitationNotPendingException, is ProposalAlreadyExistsException, is ProposalNotPendingException -> HttpStatus.CONFLICT
            is PaymentProviderUnavailableException -> HttpStatus.SERVICE_UNAVAILABLE
            is PaymentInitiationFailedException -> HttpStatus.BAD_GATEWAY
            is PaymentCallbackInvalidException -> HttpStatus.UNAUTHORIZED
            is UnsupportedRequestAttachmentTypeException -> HttpStatus.UNSUPPORTED_MEDIA_TYPE
            is RequestAttachmentTooLargeException -> HttpStatus.PAYLOAD_TOO_LARGE
            else -> HttpStatus.BAD_REQUEST
        }
        return problem(status, exception.code, exception.message ?: status.reasonPhrase, request)
    }

    @ExceptionHandler(BookingException::class)
    fun booking(exception: BookingException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status = when (exception) {
            is BookingNotFoundException -> HttpStatus.NOT_FOUND
            is BookingNotOwnedException, is BookingActorInactiveException -> HttpStatus.FORBIDDEN
            is BookingScheduleInvalidException, is BookingCancellationReasonInvalidException -> HttpStatus.BAD_REQUEST
            is ProviderNotAvailableException, is BookingScheduleConflictException,
            is RequestAlreadyHasBookingException, is ProposalNotAcceptableException,
            is BookingInvalidTransitionException -> HttpStatus.CONFLICT
            else -> HttpStatus.BAD_REQUEST
        }
        return problem(status, exception.code, exception.message ?: status.reasonPhrase, request)
    }

    @ExceptionHandler(CommunicationException::class)
    fun communication(exception: CommunicationException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status = when (exception) {
            is ConversationNotFoundException, is ReportNotFoundException, is SupportCaseNotFoundException,
            is NotificationNotFoundException, is BlockNotFoundException -> HttpStatus.NOT_FOUND
            is ConversationAccessDeniedException, is SupportCaseAccessDeniedException,
            is NotificationAccessDeniedException -> HttpStatus.FORBIDDEN
            is InteractionBlockedException, is DuplicateBlockException, is ConversationClosedException,
            is ActiveDisputeExistsException, is ReportInvalidTransitionException,
            is SupportCaseInvalidTransitionException -> HttpStatus.CONFLICT
            else -> HttpStatus.BAD_REQUEST
        }
        return problem(status, exception.code, exception.message ?: status.reasonPhrase, request)
    }

    @ExceptionHandler(FeedbackException::class)
    fun feedback(exception: FeedbackException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status = when (exception) {
            is FeedbackBookingNotFoundException, is FeedbackNotFoundException -> HttpStatus.NOT_FOUND
            is FeedbackNotAllowedException -> HttpStatus.FORBIDDEN
            is FeedbackAlreadyExistsException -> HttpStatus.CONFLICT
            else -> HttpStatus.BAD_REQUEST
        }
        return problem(status, exception.code, exception.message ?: status.reasonPhrase, request)
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun multipartTooLarge(request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        problem(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE", "Multipart request exceeds the configured limit", request)

    private fun problem(
        status: HttpStatus,
        code: String,
        detail: String,
        request: HttpServletRequest,
        extra: Map<String, Any> = emptyMap(),
    ): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = status.reasonPhrase
            instance = URI.create(request.requestURI)
            setProperty("code", code)
            setProperty("requestId", request.getAttribute(CORRELATION_ID_ATTRIBUTE)?.toString())
            extra.forEach(::setProperty)
        }
        return ResponseEntity.status(status).body(body)
    }
}
