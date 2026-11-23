package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "platform_profiles")
data class PlatformProfile(
    @PrimaryKey
    val id: String, // e.g. "betpawa_portrait", "sportybet_portrait", "custom_portrait"
    val platformName: String, // "BetPawa", "SportyBet", "Custom"
    val orientation: String, // "PORTRAIT", "LANDSCAPE"
    val isActive: Boolean = false,
    val description: String = "",
    val minStake: Double = 1.0,
    val maxStake: Double = 1_000_000.0
)
