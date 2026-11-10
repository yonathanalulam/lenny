package com.skilledservices.platform.shared.storage

import java.time.Instant
import java.util.UUID

data class ProtectedObject(
    val id: UUID,
    val storageKey: String,
    val createdBy: UUID,
    val createdAt: Instant,
)

interface ProtectedObjectStore {
    fun save(protectedObject: ProtectedObject): ProtectedObject
    fun findById(id: UUID): ProtectedObject?
}

interface ProtectedObjectStorage {
    fun put(storageKey: String, content: ByteArray)
    fun get(storageKey: String): ByteArray
    fun delete(storageKey: String)
}

interface ProtectedUploadPolicy {
    val maxFileSizeBytes: Long
    val allowedContentTypes: Set<String>
}
