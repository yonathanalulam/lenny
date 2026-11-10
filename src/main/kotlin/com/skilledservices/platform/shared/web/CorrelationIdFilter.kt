package com.skilledservices.platform.shared.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

const val CORRELATION_ID_ATTRIBUTE = "correlationId"
const val CORRELATION_ID_HEADER = "X-Correlation-ID"

@Component
class CorrelationIdFilter : OncePerRequestFilter() {
    private val allowed = Regex("[A-Za-z0-9._-]{1,128}")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val supplied = request.getHeader(CORRELATION_ID_HEADER)
        val correlationId = supplied?.takeIf { allowed.matches(it) } ?: UUID.randomUUID().toString()
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId)
        response.setHeader(CORRELATION_ID_HEADER, correlationId)
        MDC.put(CORRELATION_ID_ATTRIBUTE, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(CORRELATION_ID_ATTRIBUTE)
        }
    }
}
