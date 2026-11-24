package com.example.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ScanScheduler(
    private val scope: CoroutineScope,
    private val onHeartbeatScan: suspend () -> Unit
) {
    private var heartbeatJob: Job? = null

    fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(5000L) // S4: slow heartbeat every 5 seconds
                runCatching { onHeartbeatScan() }
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
