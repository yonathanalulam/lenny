package com.skilledservices.platform.profiles.domain

import java.time.Instant
import java.util.UUID

data class CustomerProfile(
    val accountId: UUID,
    val fullName: String,
    val serviceAreaId: String,
    val serviceAreaName: String,
    val preferredLanguage: String,
    val profilePhotoObjectId: UUID?,
    val setupComplete: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ProviderProfile(
    val accountId: UUID,
    val professionalDescription: String,
    val skills: Set<String>,
    val experienceYears: Int,
    val serviceArea: String,
    val pricingSummary: String,
    val portfolioSummary: String?,
    val profilePhotoObjectId: UUID?,
    val setupComplete: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface CustomerProfileStore {
    fun findByAccountId(accountId: UUID): CustomerProfile?
    fun save(profile: CustomerProfile): CustomerProfile
}

interface ProviderProfileStore {
    fun findByAccountId(accountId: UUID): ProviderProfile?
    fun findAll(): List<ProviderProfile>
    fun save(profile: ProviderProfile): ProviderProfile
}
