package com.godmode.app.data.model

data class AppAccessSummary(
    val packageName: String,
    val appName: String,
    val totalAccesses: Int,
    val lastAccessTime: Long,
    val dataTypes: List<String>  // e.g., ["LOCATION", "IMEI", "CONTACTS"]
)

data class DataAccessDetail(
    val dataType: String,
    val count: Int,
    val lastAccessTime: Long,
    val wasBlocked: Boolean
)

data class AccessLogDetail(
    val id: Long,
    val timestamp: Long,
    val dataType: String,
    val originalValue: String?,
    val spoofedValue: String?,
    val wasBlocked: Boolean
)
