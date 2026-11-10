package com.skilledservices.platform.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("platform.cors")
data class CorsProperties(
    val allowedOrigins: List<String> = listOf(
        "http://localhost:3000",
        "http://localhost:5173",
        "http://localhost:8081",
    ),
)
