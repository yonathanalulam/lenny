package com.skilledservices.platform.profiles.api

import com.skilledservices.platform.profiles.application.AvailabilityService
import com.skilledservices.platform.profiles.application.OnboardingService
import com.skilledservices.platform.profiles.application.ProfileService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/customers/me")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer onboarding")
class CustomerProfileController(
    private val profileService: ProfileService,
    private val onboardingService: OnboardingService,
) {
    @GetMapping("/profile")
    @Operation(summary = "Get the authenticated customer's profile")
    @ApiResponse(responseCode = "200", description = "Customer profile")
    @ApiResponse(responseCode = "404", description = "Profile has not been created")
    fun getProfile(@AuthenticationPrincipal jwt: Jwt) = profileService.getCustomer(jwt.accountId()).toResponse()

    @PutMapping("/profile")
    @Operation(
        summary = "Create or replace the authenticated customer's profile",
        description = "Email must match the authenticated account. A new or changed phone remains unverified.",
    )
    fun putProfile(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: CustomerProfileRequest) =
        profileService.putCustomer(jwt.accountId(), request.toCommand()).toResponse()

    @GetMapping("/onboarding")
    @Operation(summary = "Get explicit customer setup, verification, and eligibility state")
    fun onboarding(@AuthenticationPrincipal jwt: Jwt) = onboardingService.customer(jwt.accountId()).toResponse()
}

@RestController
@RequestMapping("/api/v1/providers/me")
@PreAuthorize("hasRole('SERVICE_PROVIDER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Provider onboarding")
class ProviderProfileController(
    private val profileService: ProfileService,
    private val availabilityService: AvailabilityService,
    private val onboardingService: OnboardingService,
) {
    @GetMapping("/profile")
    @Operation(summary = "Get the authenticated provider's private professional profile")
    fun getProfile(@AuthenticationPrincipal jwt: Jwt) = profileService.getProvider(jwt.accountId()).toResponse()

    @PutMapping("/profile")
    @Operation(summary = "Create or replace the authenticated provider's private professional profile")
    fun putProfile(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: ProviderProfileRequest) =
        profileService.putProvider(jwt.accountId(), request.toCommand()).toResponse()

    @GetMapping("/availability")
    @Operation(summary = "Get weekly availability; an unset schedule is returned as unavailable")
    fun getAvailability(@AuthenticationPrincipal jwt: Jwt) = availabilityService.get(jwt.accountId()).toResponse()

    @PutMapping("/availability")
    @Operation(summary = "Replace weekly provider availability")
    fun putAvailability(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: ProviderAvailabilityRequest) =
        availabilityService.put(jwt.accountId(), request.toCommand()).toResponse()

    @GetMapping("/onboarding")
    @Operation(summary = "Get explicit provider setup, verification, and eligibility state")
    fun onboarding(@AuthenticationPrincipal jwt: Jwt) = onboardingService.provider(jwt.accountId()).toResponse()
}

internal fun Jwt.accountId(): UUID = UUID.fromString(subject)
