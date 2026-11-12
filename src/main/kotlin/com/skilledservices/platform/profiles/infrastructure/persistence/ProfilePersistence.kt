package com.skilledservices.platform.profiles.infrastructure.persistence

import com.skilledservices.platform.profiles.domain.CustomerProfile
import com.skilledservices.platform.profiles.domain.CustomerProfileStore
import com.skilledservices.platform.profiles.domain.ProviderProfile
import com.skilledservices.platform.profiles.domain.ProviderProfileStore
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "customer_profiles")
class CustomerProfileEntity(
    @Id @Column(name = "account_id") var accountId: UUID,
    @Column(name = "service_area", nullable = false, length = 160) var serviceArea: String,
    @Column(name = "preferred_language", nullable = false, length = 32) var preferredLanguage: String,
    @Column(name = "profile_photo_object_id") var profilePhotoObjectId: UUID?,
    @Column(name = "setup_complete", nullable = false) var setupComplete: Boolean,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)

@Entity
@Table(name = "provider_profiles")
class ProviderProfileEntity(
    @Id @Column(name = "account_id") var accountId: UUID,
    @Column(name = "professional_description", nullable = false, length = 4000)
    var professionalDescription: String,
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "provider_profile_skills", joinColumns = [JoinColumn(name = "provider_id")])
    @Column(name = "skill", nullable = false, length = 100)
    var skills: MutableSet<String>,
    @Column(name = "experience_years", nullable = false) var experienceYears: Int,
    @Column(name = "service_area", nullable = false, length = 160) var serviceArea: String,
    @Column(name = "pricing_summary", nullable = false, length = 1000) var pricingSummary: String,
    @Column(name = "portfolio_summary", length = 4000) var portfolioSummary: String?,
    @Column(name = "profile_photo_object_id") var profilePhotoObjectId: UUID?,
    @Column(name = "setup_complete", nullable = false) var setupComplete: Boolean,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)

interface CustomerProfileJpaRepository : JpaRepository<CustomerProfileEntity, UUID>
interface ProviderProfileJpaRepository : JpaRepository<ProviderProfileEntity, UUID> {
    @EntityGraph(attributePaths = ["skills"])
    override fun findAll(): List<ProviderProfileEntity>
}

@Repository
class JpaCustomerProfileStore(private val repository: CustomerProfileJpaRepository) : CustomerProfileStore {
    override fun findByAccountId(accountId: UUID) = repository.findById(accountId).orElse(null)?.toDomain()
    override fun save(profile: CustomerProfile) = repository.save(profile.toEntity()).toDomain()
}

@Repository
class JpaProviderProfileStore(private val repository: ProviderProfileJpaRepository) : ProviderProfileStore {
    override fun findByAccountId(accountId: UUID) = repository.findById(accountId).orElse(null)?.toDomain()
    override fun findAll() = repository.findAll().map { it.toDomain() }
    override fun save(profile: ProviderProfile) = repository.save(profile.toEntity()).toDomain()
}

private fun CustomerProfileEntity.toDomain() = CustomerProfile(
    accountId, serviceArea, preferredLanguage, profilePhotoObjectId, setupComplete, createdAt, updatedAt,
)

private fun CustomerProfile.toEntity() = CustomerProfileEntity(
    accountId, serviceArea, preferredLanguage, profilePhotoObjectId, setupComplete, createdAt, updatedAt,
)

private fun ProviderProfileEntity.toDomain() = ProviderProfile(
    accountId, professionalDescription, skills.toSet(), experienceYears, serviceArea, pricingSummary,
    portfolioSummary, profilePhotoObjectId, setupComplete, createdAt, updatedAt,
)

private fun ProviderProfile.toEntity() = ProviderProfileEntity(
    accountId, professionalDescription, skills.toMutableSet(), experienceYears, serviceArea, pricingSummary,
    portfolioSummary, profilePhotoObjectId, setupComplete, createdAt, updatedAt,
)
