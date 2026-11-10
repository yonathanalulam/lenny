package com.skilledservices.platform.shared.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class HealthResponse(val status: String)

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Platform")
class HealthController {
    @GetMapping
    @Operation(summary = "Check whether the API process is serving requests")
    fun health(): HealthResponse = HealthResponse("UP")
}
