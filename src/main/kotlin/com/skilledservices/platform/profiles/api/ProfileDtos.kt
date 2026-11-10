package com.skilledservices.platform.profiles.api

import com.skilledservices.platform.profiles.application.AvailabilityRangeCommand
import com.skilledservices.platform.profiles.application.CustomerProfileCommand
import com.skilledservices.platform.profiles.application.OnboardingStatus
import com.skilledservices.platform.profiles.application.ProviderAvailabilityCommand
import com.skilledservices.platform.profiles.application.ProviderProfileCommand
import com.skilledservices.platform.profiles.domain.CustomerProfile
import com.skilledservices.platform.profiles.domain.ProviderAvailability
import com.skilledservices.platform.profiles.domain.ProviderProfile
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

data class CustomerProfileRequest(
    @field:NotBlank @field:Size(min = 2, max = 160)
    @field:Schema(example = "Bole and nearby Addis Ababa areas")
    val serviceArea: String,
    @field:NotBlank @field:Size(min = 2, max = 32)
    @field:Schema(example = "Amharic")
    val preferredLanguage: String,
)

data class CustomerProfileResponse(
    val accountId: UUID,
    val serviceArea: String,
    val preferredLanguage: String,
    val profilePhotoAvailable: Boolean,
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

internal fun CustomerProfileRequest.toCommand() = CustomerProfileCommand(serviceArea, preferredLanguage)
internal fun ProviderProfileRequest.toCommand() = ProviderProfileCommand(
    professionalDescription, skills, experienceYears, serviceArea, pricingSummary, portfolioSummary,
)
internal fun ProviderAvailabilityRequest.toCommand() = ProviderAvailabilityCommand(
    available, ranges.map { AvailabilityRangeCommand(it.dayOfWeek, it.startTime, it.endTime) },
)
internal fun CustomerProfile.toResponse() = CustomerProfileResponse(
    accountId, serviceArea, preferredLanguage, profilePhotoObjectId != null, setupComplete, createdAt, updatedAt,
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
