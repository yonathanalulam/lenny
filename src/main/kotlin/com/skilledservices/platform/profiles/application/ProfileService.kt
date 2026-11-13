package com.skilledservices.platform.profiles.application

import com.skilledservices.platform.identity.application.AccountContactSnapshot
import com.skilledservices.platform.identity.application.IdentityContactService
import com.skilledservices.platform.identity.application.IdentityAccountQuery
import com.skilledservices.platform.identity.domain.AccountRole
import com.skilledservices.platform.profiles.domain.CustomerProfile
import com.skilledservices.platform.profiles.domain.CustomerProfileStore
import com.skilledservices.platform.profiles.domain.ProfileInvalidException
import com.skilledservices.platform.profiles.domain.ProfileNotFoundException
import com.skilledservices.platform.profiles.domain.ProfileRoleRequiredException
import com.skilledservices.platform.profiles.domain.ProviderProfile
import com.skilledservices.platform.profiles.domain.ProviderProfileStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

data class CustomerProfileCommand(
    val fullName: String,
    val email: String,
    val phone: String?,
    val preferredLanguage: String,
    val serviceAreaId: String,
)

data class CustomerProfileView(
    val profile: CustomerProfile,
    val contact: AccountContactSnapshot,
    val setupComplete: Boolean,
)

data class ProviderProfileCommand(
    val professionalDescription: String,
    val skills: Set<String>,
    val experienceYears: Int,
    val serviceArea: String,
    val pricingSummary: String,
    val portfolioSummary: String?,
)

@Service
class ProfileService(
    private val customerStore: CustomerProfileStore,
    private val providerStore: ProviderProfileStore,
    private val identityAccountQuery: IdentityAccountQuery,
    private val identityContactService: IdentityContactService,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun getCustomer(accountId: UUID): CustomerProfileView {
        requireRole(accountId, AccountRole.CUSTOMER)
        val profile = customerStore.findByAccountId(accountId) ?: throw ProfileNotFoundException()
        val contact = identityAccountQuery.accountContact(accountId) ?: throw ProfileRoleRequiredException()
        return profile.toView(contact)
    }

    @Transactional
    fun putCustomer(accountId: UUID, command: CustomerProfileCommand): CustomerProfileView {
        requireRole(accountId, AccountRole.CUSTOMER)
        val fullName = cleanRequired(command.fullName, "fullName", 2, 160)
        val language = cleanRequired(command.preferredLanguage, "preferredLanguage", 2, 32)
        val serviceAreaId = cleanRequired(command.serviceAreaId, "serviceAreaId", 2, 100)
        val serviceAreaName = resolveServiceAreaName(serviceAreaId)
        val contact = identityContactService.updateCustomerContact(accountId, command.email, command.phone)
        val existing = customerStore.findByAccountId(accountId)
        val now = clock.instant()
        val setupComplete = contact.requiredContactVerified
        val saved = customerStore.save(
            CustomerProfile(
                accountId = accountId,
                fullName = fullName,
                serviceAreaId = serviceAreaId,
                serviceAreaName = serviceAreaName,
                preferredLanguage = language,
                profilePhotoObjectId = existing?.profilePhotoObjectId,
                setupComplete = setupComplete,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        return saved.toView(contact)
    }

    @Transactional(readOnly = true)
    fun getProvider(accountId: UUID): ProviderProfile {
        requireRole(accountId, AccountRole.SERVICE_PROVIDER)
        return providerStore.findByAccountId(accountId) ?: throw ProfileNotFoundException()
    }

    @Transactional
    fun putProvider(accountId: UUID, command: ProviderProfileCommand): ProviderProfile {
        requireRole(accountId, AccountRole.SERVICE_PROVIDER)
        val description = cleanRequired(command.professionalDescription, "professionalDescription", 20, 4000)
        val area = cleanRequired(command.serviceArea, "serviceArea", 2, 160)
        val pricing = cleanRequired(command.pricingSummary, "pricingSummary", 2, 1000)
        if (command.experienceYears !in 0..80) throw ProfileInvalidException("experienceYears must be between 0 and 80")
        val skills = command.skills.map { cleanRequired(it, "skill", 2, 100) }.toSet()
        if (skills.isEmpty() || skills.size > 30) throw ProfileInvalidException("skills must contain between 1 and 30 unique values")
        val portfolio = command.portfolioSummary?.trim()?.takeIf { it.isNotEmpty() }
        if (portfolio != null && portfolio.length > 4000) throw ProfileInvalidException("portfolioSummary must not exceed 4000 characters")

        val existing = providerStore.findByAccountId(accountId)
        val now = clock.instant()
        return providerStore.save(
            ProviderProfile(
                accountId = accountId,
                professionalDescription = description,
                skills = skills,
                experienceYears = command.experienceYears,
                serviceArea = area,
                pricingSummary = pricing,
                portfolioSummary = portfolio,
                profilePhotoObjectId = existing?.profilePhotoObjectId,
                setupComplete = true,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    private fun requireRole(accountId: UUID, role: AccountRole) {
        if (!identityAccountQuery.hasRole(accountId, role)) throw ProfileRoleRequiredException()
    }

    private fun cleanRequired(value: String, field: String, min: Int, max: Int): String {
        val cleaned = value.trim()
        if (cleaned.length !in min..max) throw ProfileInvalidException("$field must contain between $min and $max characters")
        return cleaned
    }

    private fun CustomerProfile.toView(contact: AccountContactSnapshot): CustomerProfileView =
        CustomerProfileView(this, contact, isCustomerSetupComplete(this, contact))

    private fun resolveServiceAreaName(serviceAreaId: String): String = when (serviceAreaId) {
        "bole-medhanialem" -> "Bole / Medhanialem"
        else -> serviceAreaId.split('-').joinToString(" ") { segment ->
            segment.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}

internal fun isCustomerSetupComplete(profile: CustomerProfile?, contact: AccountContactSnapshot): Boolean =
    profile != null &&
        profile.fullName.isNotBlank() &&
        profile.serviceAreaId.isNotBlank() &&
        profile.preferredLanguage.isNotBlank() &&
        contact.requiredContactVerified
