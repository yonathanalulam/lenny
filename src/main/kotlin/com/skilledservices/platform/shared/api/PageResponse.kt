package com.skilledservices.platform.shared.api

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Stable platform-owned pagination envelope for future list endpoints")
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
    val hasNext: Boolean,
)
