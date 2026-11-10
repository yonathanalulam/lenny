package com.skilledservices.platform.identity.infrastructure.security

import com.skilledservices.platform.identity.domain.AccountStatus
import com.skilledservices.platform.identity.domain.AccountStore
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class AccountStateFilter(
    private val accountStore: AccountStore,
    private val authenticationEntryPoint: ProblemAuthenticationEntryPoint,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication is JwtAuthenticationToken && authentication.isAuthenticated) {
            val accountId = runCatching { UUID.fromString(authentication.token.subject) }.getOrNull()
            val credentialVersion = authentication.token.getClaim<Number>("credentialVersion")?.toInt()
            val account = accountId?.let(accountStore::findById)
            if (account == null || account.status != AccountStatus.ACTIVE || account.credentialVersion != credentialVersion) {
                SecurityContextHolder.clearContext()
                authenticationEntryPoint.commence(
                    request,
                    response,
                    InsufficientAuthenticationException("Account authentication state is no longer valid"),
                )
                return
            }
        }
        filterChain.doFilter(request, response)
    }
}
