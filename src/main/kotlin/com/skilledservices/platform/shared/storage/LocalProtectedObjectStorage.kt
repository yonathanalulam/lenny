package com.skilledservices.platform.shared.storage

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@ConfigurationProperties("platform.protected-storage")
data class ProtectedStorageProperties(
    val localRoot: String = "./data/protected",
    override val maxFileSizeBytes: Long = 10_485_760,
    override val allowedContentTypes: Set<String> = setOf("application/pdf", "image/jpeg", "image/png"),
) : ProtectedUploadPolicy

@Component
class LocalProtectedObjectStorage(properties: ProtectedStorageProperties) : ProtectedObjectStorage {
    private val root: Path = Path.of(properties.localRoot).toAbsolutePath().normalize()

    override fun put(storageKey: String, content: ByteArray) {
        val target = resolveSafely(storageKey)
        Files.createDirectories(target.parent)
        Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    override fun get(storageKey: String): ByteArray = Files.readAllBytes(resolveSafely(storageKey))
    override fun delete(storageKey: String) { Files.deleteIfExists(resolveSafely(storageKey)) }

    private fun resolveSafely(storageKey: String): Path {
        require(storageKey.matches(Regex("[a-zA-Z0-9/_-]{1,512}"))) { "Invalid protected storage key" }
        val resolved = root.resolve(storageKey).normalize()
        require(resolved.startsWith(root)) { "Protected storage key escapes configured root" }
        return resolved
    }
}
