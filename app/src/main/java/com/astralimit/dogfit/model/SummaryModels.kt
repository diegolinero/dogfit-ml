package com.astralimit.dogfit.model

import com.astralimit.dogfit.DailySummary

data class MonthlySummaryModel(
    val month: String = "",
    val year: Int = 2026,
    val totalSteps: Int = 0,
    val avgDailySteps: Int = 0,
    val totalDistance: Float = 0f,
    val totalActiveMinutes: Int = 0,
    val avgDailyMinutes: Int = 0,
    val activeDays: Int = 0,
    val longestActiveStreak: Int = 0,
    val consistencyScore: Float = 0f,
    val comparisonWithLastMonth: Float = 0f,
    val monthlyGoalAchieved: Boolean = false
)

data class WeeklySummaryModel(
    val weekNumber: Int = 1,
    val weekRange: String = "",
    val totalSteps: Int = 0,
    val avgDailySteps: Int = 0,
    val totalDistance: Float = 0f,
    val totalActiveMinutes: Int = 0,
    val avgDailyMinutes: Int = 0,
    val totalCalories: Float = 0f,
    val activeDays: Int = 0,
    val restDays: Int = 0,
    val trend: String = "",
    val bestDay: DailySummary? = null,
    val consistencyScore: Float = 0f
)
