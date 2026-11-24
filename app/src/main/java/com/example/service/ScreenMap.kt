package com.example.service

import android.graphics.Rect

data class ScreenMap(
    val gameId: String = "aviator",
    val siteUrl: String = "",
    val gameTitle: String = "",
    val balance: Double = 0.0,
    val currency: String = "TZS",
    val betButtonBounds: Rect? = null,
    val stakeBoxBounds: Rect? = null,
    val autoCashOutToggleBounds: Rect? = null,
    val autoCashOutValueBounds: Rect? = null,
    val gamePhase: String = "WAITING_FOR_GAME",
    val scannedAtMs: Long = System.currentTimeMillis()
) {
    fun isStale(maxAgeMs: Long = 10_000L): Boolean =
        (System.currentTimeMillis() - scannedAtMs) > maxAgeMs
}
