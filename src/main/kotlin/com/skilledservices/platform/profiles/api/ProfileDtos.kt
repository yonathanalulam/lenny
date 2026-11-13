package com.skilledservices.platform.profiles.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.skilledservices.platform.profiles.application.AvailabilityRangeCommand
import com.skilledservices.platform.profiles.application.CustomerProfileCommand
import com.skilledservices.platform.profiles.application.CustomerProfileView
import com.skilledservices.platform.profiles.application.CustomerOnboardingStatus
import com.skilledservices.platform.profiles.application.OnboardingStatus
import com.skilledservices.platform.profiles.application.ProviderAvailabilityCommand
import com.skilledservices.platform.profiles.application.ProviderProfileCommand
import com.skilledservices.platform.profiles.domain.ProviderAvailability
import com.skilledservices.platform.profiles.domain.ProviderProfile
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

data class CustomerProfileRequest(
    @field:NotBlank @field:Size(min = 2, max = 160)
    @field:Schema(example = "Sara Tadesse")
    val fullName: String,
    @field:NotBlank @field:Email @field:Size(max = 320)
    @field:Schema(example = "sara@example.com", description = "Must match the authenticated account email")
    val email: String,
    @field:Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "must use E.164 format, for example +251911234567")
    @field:Schema(example = "+251911234567", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
    val phone: String?,
    @field:NotBlank @field:Size(min = 2, max = 32)
    @field:Schema(example = "en")
    val preferredLanguage: String,
    @field:NotBlank @field:Size(min = 2, max = 100)
    @field:Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "must be a lowercase service-area identifier")
    @field:Schema(example = "bole-medhanialem")
    val serviceAreaId: String,
)

data class CustomerProfileResponse(
    val accountId: UUID,
    val fullName: String,
    val email: String,
    val emailVerified: Boolean,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
    val phone: String?,
    val phoneVerified: Boolean,
    val serviceAreaId: String,
    val serviceAreaName: String,
    val preferredLanguage: String,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
    val profilePhotoUrl: String?,
    val setupComplete: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ProviderProfileRequest(
    @field:NotBlank @field:Size(min = 20, max = 4000)
    val professionalDescription: String,
    @field:NotEmpty @field:Size(max = 30)
    val skills: Set<@NotBlank @Size(min = 2, max = 100) String>,
    @field:Min(0) @field:Max(80)
    val experienceYears: Int,
    @field:NotBlank @field:Size(min = 2, max = 160)
    val serviceArea: String,
    @field:NotBlank @field:Size(min = 2, max = 1000)
    val pricingSummary: String,
    @field:Size(max = 4000)
    val portfolioSummary: String? = null,
)

data class ProviderProfileResponse(
    val accountId: UUID,
    val professionalDescription: String,
    val skills: Set<String>,
    val experienceYears: Int,
    val serviceArea: String,
    val pricingSummary: String,
    val portfolioSummary: String?,
    val profilePhotoAvailable: Boolean,
    val setupComplete: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AvailabilityRangeRequest(
    val dayOfWeek: DayOfWeek,
    @field:Schema(type = "string", format = "time", example = "08:30:00")
    val startTime: LocalTime,
    @field:Schema(type = "string", format = "time", example = "17:00:00")
    val endTime: LocalTime,
)

data class ProviderAvailabilityRequest(
    val available: Boolean,
    @field:Size(max = 100)
    val ranges: List<@Valid AvailabilityRangeRequest> = emptyList(),
)

data class AvailabilityRangeResponse(
    val id: UUID,
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endTime: LocalTime,
)

data class ProviderAvailabilityResponse(
    val available: Boolean,
    val ranges: List<AvailabilityRangeResponse>,
    val updatedAt: Instant?,
)

data class OnboardingStatusResponse(
    val accountStatus: String,
    val role: String,
    val setupComplete: Boolean,
    val verificationStatus: String,
    val verificationSubmittedAt: Instant?,
    val marketplaceEligible: Boolean,
)

data class ContactVerificationStatusResponse(
    val emailVerified: Boolean,
    val phoneVerified: Boolean,
)

data class CustomerSetupRequirementsResponse(
    val fullNameProvided: Boolean,
    val serviceAreaSelected: Boolean,
    val preferredLanguageSelected: Boolean,
    val requiredContactVerified: Boolean,
)

data class CustomerOnboardingStatusResponse(
    val role: String,
    val accountStatus: String,
    val setupStatus: String,
    val setupComplete: Boolean,
    val marketplaceEligible: Boolean,
    val contactVerification: ContactVerificationStatusResponse,
    val requirements: CustomerSetupRequirementsResponse,
    val verificationStatus: String,
    @get:JsonInclude(JsonInclude.Include.ALWAYS)
    @field:Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
    val verificationSubmittedAt: Instant?,
)

internal fun CustomerProfileRequest.toCommand() =
    CustomerProfileCommand(fullName, email, phone, preferredLanguage, serviceAreaId)
internal fun ProviderProfileRequest.toCommand() = ProviderProfileCommand(
    professionalDescription, skills, experienceYears, serviceArea, pricingSummary, portfolioSummary,
)
internal fun ProviderAvailabilityRequest.toCommand() = ProviderAvailabilityCommand(
    available, ranges.map { AvailabilityRangeCommand(it.dayOfWeek, it.startTime, it.endTime) },
)
internal fun CustomerProfileView.toResponse() = CustomerProfileResponse(
    accountId = profile.accountId,
    fullName = profile.fullName,
    email = contact.email,
    emailVerified = contact.emailVerified,
    phone = contact.phone,
    phoneVerified = contact.phoneVerified,
    serviceAreaId = profile.serviceAreaId,
    serviceAreaName = profile.serviceAreaName,
    preferredLanguage = profile.preferredLanguage,
    profilePhotoUrl = null,
    setupComplete = setupComplete,
    createdAt = profile.createdAt,
    updatedAt = profile.updatedAt,
)
internal fun ProviderProfile.toResponse() = ProviderProfileResponse(
    accountId, professionalDescription, skills, experienceYears, serviceArea, pricingSummary, portfolioSummary,
    profilePhotoObjectId != null, setupComplete, createdAt, updatedAt,
)
internal fun ProviderAvailability?.toResponse() = ProviderAvailabilityResponse(
    available = this?.available ?: false,
    ranges = this?.ranges?.map { AvailabilityRangeResponse(it.id, it.dayOfWeek, it.startTime, it.endTime) } ?: emptyList(),
    updatedAt = this?.updatedAt,
)
internal fun OnboardingStatus.toResponse() = OnboardingStatusResponse(
    accountStatus.name, role.name, setupComplete, verificationStatus.name, verificationSubmittedAt, marketplaceEligible,
)
internal fun CustomerOnboardingStatus.toResponse() = CustomerOnboardingStatusResponse(
    role = role.name,
    accountStatus = accountStatus.name,
    setupStatus = setupStatus.name,
    setupComplete = setupComplete,
    marketplaceEligible = marketplaceEligible,
    contactVerification = ContactVerificationStatusResponse(
        emailVerified = contactVerification.emailVerified,
        phoneVerified = contactVerification.phoneVerified,
    ),
    requirements = CustomerSetupRequirementsResponse(
        fullNameProvided = requirements.fullNameProvided,
        serviceAreaSelected = requirements.serviceAreaSelected,
        preferredLanguageSelected = requirements.preferredLanguageSelected,
        requiredContactVerified = requirements.requiredContactVerified,
    ),
    verificationStatus = verificationStatus.name,
    verificationSubmittedAt = verificationSubmittedAt,
)
