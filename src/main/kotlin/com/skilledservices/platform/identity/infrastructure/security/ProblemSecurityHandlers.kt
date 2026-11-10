package com.skilledservices.platform.identity.infrastructure.security

import com.skilledservices.platform.shared.web.CORRELATION_ID_ATTRIBUTE
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class ProblemAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        writeProblem(request, response, HttpStatus.UNAUTHORIZED, "security.authentication_required", "Authentication is required", objectMapper)
    }
}

@Component
class ProblemAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        writeProblem(request, response, HttpStatus.FORBIDDEN, "security.access_denied", "Access is denied", objectMapper)
    }
}

private fun writeProblem(
    request: HttpServletRequest,
    response: HttpServletResponse,
    status: HttpStatus,
    code: String,
    detail: String,
    objectMapper: ObjectMapper,
) {
    val problem = ProblemDetail.forStatusAndDetail(status, detail)
    problem.title = status.reasonPhrase
    problem.setProperty("code", code)
    problem.setProperty("requestId", request.getAttribute(CORRELATION_ID_ATTRIBUTE)?.toString())
    response.status = status.value()
    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    objectMapper.writeValue(response.outputStream, problem)
}
