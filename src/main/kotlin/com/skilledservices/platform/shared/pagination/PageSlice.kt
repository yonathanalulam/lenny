package com.skilledservices.platform.shared.pagination

data class PageSlice<T>(val items: List<T>, val totalItems: Long)
