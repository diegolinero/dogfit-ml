package com.astralimit.dogfit.model

data class GpsLocation(
    val id: Long = System.currentTimeMillis(),
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val date: String,
    val time: String,
    val accuracy: Float = 0f
)

data class RouteDay(
    val date: String,
    val locations: List<GpsLocation>,
    val totalDistanceKm: Float,
    val durationMinutes: Int
)
