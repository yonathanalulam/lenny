package com.skilledservices.platform.profiles.domain

open class ProfilesException(message: String, val code: String) : RuntimeException(message)

class ProfileInvalidException(message: String) : ProfilesException(message, "PROFILE_INVALID")
class ProfileNotFoundException : ProfilesException("The requested profile does not exist", "PROFILE_NOT_FOUND")
class InvalidAvailabilityRangeException(message: String) :
    ProfilesException(message, "INVALID_AVAILABILITY_RANGE")

class VerificationAlreadyPendingException :
    ProfilesException("A verification submission is already pending", "VERIFICATION_ALREADY_PENDING")

class VerificationNotFoundException :
    ProfilesException("The verification record was not found", "VERIFICATION_NOT_FOUND")

class InvalidVerificationTransitionException(message: String) :
    ProfilesException(message, "INVALID_VERIFICATION_TRANSITION")

class ForbiddenVerificationAccessException :
    ProfilesException("You are not allowed to access this verification evidence", "FORBIDDEN_VERIFICATION_ACCESS")

class UnsupportedUploadTypeException(contentType: String) :
    ProfilesException("Unsupported verification evidence content type: $contentType", "UNSUPPORTED_UPLOAD_TYPE")

class UploadTooLargeException(maxBytes: Long) :
    ProfilesException("Verification evidence exceeds the configured limit of $maxBytes bytes", "UPLOAD_TOO_LARGE")

class InvalidVerificationEvidenceException(message: String) :
    ProfilesException(message, "INVALID_VERIFICATION_EVIDENCE")

class ProfileRoleRequiredException :
    ProfilesException("The account does not have the required role", "PROFILE_ROLE_REQUIRED")
