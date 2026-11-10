package com.skilledservices.platform.identity.infrastructure.bootstrap

import com.skilledservices.platform.identity.domain.*
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

@ConfigurationProperties("platform.dev-admin")
data class DevAdminProperties(
    val enabled: Boolean = false,
    val email: String = "",
    val password: String = "",
)

@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "platform.dev-admin", name = ["enabled"], havingValue = "true")
class DevAdminBootstrap(
    private val properties: DevAdminProperties,
    private val accounts: AccountStore,
    private val passwordHash: PasswordHashPort,
    private val clock: Clock,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val email = properties.email.trim().lowercase()
        require(email.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) { "DEV_ADMIN_EMAIL must be a valid email address" }
        require(properties.password.length in 12..128) { "DEV_ADMIN_PASSWORD must contain 12 to 128 characters" }
        val existing = accounts.findByEmail(email)
        if (existing != null) {
            require(AccountRole.ADMIN in existing.roles) { "DEV_ADMIN_EMAIL already belongs to a non-admin account" }
            return
        }
        val now = clock.instant()
        accounts.save(
            Account(UUID.randomUUID(), email, null, passwordHash.hash(properties.password), AccountStatus.ACTIVE,
                setOf(AccountRole.ADMIN), emailVerified = true, phoneVerified = false, credentialVersion = 1,
                createdAt = now, updatedAt = now),
        )
    }
}
