package com.skilledservices.platform.identity.infrastructure.security

import com.skilledservices.platform.identity.domain.PasswordHashPort
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

@Component
class PasswordHashAdapter(
    private val passwordEncoder: PasswordEncoder,
) : PasswordHashPort {
    override fun hash(rawPassword: String): String =
        requireNotNull(passwordEncoder.encode(rawPassword)) { "Password encoder returned no hash" }

    override fun matches(rawPassword: String, encodedPassword: String): Boolean =
        passwordEncoder.matches(rawPassword, encodedPassword)
}
