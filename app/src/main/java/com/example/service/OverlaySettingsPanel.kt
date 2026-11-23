package com.example.service

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.engine.OperatingScheduleEvaluator
import com.example.data.model.NotificationSettings
import com.example.data.model.StrategyConfig
import com.example.ui.theme.ActionStartEmerald
import com.example.ui.theme.ActionStopRed
import com.example.ui.theme.PrimaryCobalt
import java.util.Locale

/**
 * Floating Overlay Settings Window composable designed to hover cleanly over the monitored
 * crash game without interrupting live detection or pulling the user into full-screen MainActivity.
 * Includes an explicit Full Screen button when the user intentionally wants to expand.
 * Note: Intentionally avoids ActivityResult launchers so it runs safely inside a Service ComposeView.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlaySettingsPanelComposable(
    strategyConfig: StrategyConfig,
    notificationSettings: NotificationSettings,
    isStandbyOutsideSchedule: Boolean = false,
    isScreenHeld: Boolean = false,
    isCpuAwakeHeld: Boolean = false,
    awakeError: String? = null,
    isMonitoringRunning: Boolean = false,
    isAlarmRinging: Boolean = false,
    activeAlarmTitle: String? = null,
    initialBubbleOpacity: Float = 0.95f,
    initialBubbleSizeDp: Float = 58f,
    initialHapticsEnabled: Boolean = true,
    onStrategyConfigChanged: (StrategyConfig) -> Unit = {},
    onNotificationSettingsChanged: (NotificationSettings) -> Unit = {},
    onSaveOverlaySettings: (
        config: StrategyConfig,
        notif: NotificationSettings,
        opacity: Float,
        bubbleSizeDp: Float,
        hapticsEnabled: Boolean
    ) -> Unit = { _, _, _, _, _ -> },
    onResetSessionStats: () -> Unit = {},
    onStopAlarm: () -> Unit = {},
    onSnoozeAlarm: () -> Unit = {},
    onTestAlarm: (NotificationSettings) -> Unit = {},
    onBackToMonitorPanel: (() -> Unit)? = null,
    onExpandFullScreen: () -> Unit = {},
    onClose: () -> Unit = {},
    onToggleBetHand: (() -> Unit)? = null,
    onToggleCashOutHand: (() -> Unit)? = null,
    onToggleActionHand: (() -> Unit)? = null,
    onOpenHandSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var localConfig by remember(strategyConfig) { mutableStateOf(strategyConfig) }
    var localNotif by remember(notificationSettings) { mutableStateOf(notificationSettings) }

    var baseStakeText by remember(strategyConfig.baseStake) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.baseStake))
    }
    var targetMultText by remember(strategyConfig.targetMultiplier) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.targetMultiplier))
    }
    var gameAutoCashTargetText by remember(strategyConfig.gameAutoCashOutTarget) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.gameAutoCashOutTarget))
    }
    var minStakeText by remember(strategyConfig.minStake) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.minStake))
    }
    var maxStakeText by remember(strategyConfig.maxStake) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.maxStake))
    }
    var minFlightTimeText by remember(strategyConfig.minFlightTimeBeforeCashOutMs) {
        mutableStateOf(strategyConfig.minFlightTimeBeforeCashOutMs.toString())
    }
    var lowThreshText by remember(strategyConfig.lowThreshold) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.lowThreshold))
    }
    var boundaryText by remember(strategyConfig.boundaryValue) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.boundaryValue))
    }
    var bigThreshText by remember(strategyConfig.bigThreshold) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.bigThreshold))
    }
    var startTimeText by remember(strategyConfig.scheduleStartHour, strategyConfig.scheduleStartMinute) {
        mutableStateOf(
            String.format(Locale.US, "%02d:%02d", strategyConfig.scheduleStartHour, strategyConfig.scheduleStartMinute)
        )
    }
    var stopTimeText by remember(strategyConfig.scheduleEndHour, strategyConfig.scheduleEndMinute) {
        mutableStateOf(
            String.format(Locale.US, "%02d:%02d", strategyConfig.scheduleEndHour, strategyConfig.scheduleEndMinute)
        )
    }
    var quietStartHourText by remember(notificationSettings.quietStartHour) {
        mutableStateOf(notificationSettings.quietStartHour.toString())
    }
    var quietEndHourText by remember(notificationSettings.quietEndHour) {
        mutableStateOf(notificationSettings.quietEndHour.toString())
    }

    var opacityValue by remember { mutableFloatStateOf(initialBubbleOpacity.coerceIn(0.3f, 1.0f)) }
    var bubbleSizeDp by remember { mutableFloatStateOf(initialBubbleSizeDp.coerceIn(44f, 80f)) }
    var hapticsEnabled by remember { mutableStateOf(initialHapticsEnabled) }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun pushConfig(updated: StrategyConfig) {
        localConfig = updated
        onStrategyConfigChanged(updated)
    }

    fun pushNotif(updated: NotificationSettings) {
        localNotif = updated
        onNotificationSettingsChanged(updated)
    }

    Card(
        modifier = modifier
            .shadow(18.dp, RoundedCornerShape(24.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
            .testTag("overlay_settings_window"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Sticky Floating Window Header (drag grip + live dot + Full Screen expand + Close)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (onBackToMonitorPanel != null) {
                            IconButton(
                                onClick = onBackToMonitorPanel,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("overlay_settings_back_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to Monitor Panel",
                                    tint = PrimaryCobalt,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.DragIndicator,
                                contentDescription = "Drag Overlay Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(start = 4.dp, end = 4.dp)
                                    .size(18.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (isMonitoringRunning) ActionStartEmerald else Color(0xFF94A3B8),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "OVERLAY SETTINGS",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isMonitoringRunning) "Monitoring live · Game stays active" else "Compact floating mode",
                                color = if (isMonitoringRunning) ActionStartEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onExpandFullScreen,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .minimumInteractiveComponentSize()
                                .testTag("overlay_settings_fullscreen_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "Full Screen Mode",
                                modifier = Modifier.size(14.dp),
                                tint = PrimaryCobalt
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Full Screen",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryCobalt,
                                maxLines = 1
                            )
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("overlay_settings_close_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Overlay Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // Scrollable Settings Body
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isAlarmRinging) {
                    Surface(
                        color = Color(0xFFFEE2E2),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ActionStopRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overlay_settings_alarm_banner")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = activeAlarmTitle ?: "SIGNAL ALARM RINGING",
                                color = ActionStopRed,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onStopAlarm,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ActionStopRed,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("overlay_settings_stop_alarm_btn")
                                ) {
                                    Icon(Icons.Default.NotificationsOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("STOP ALARM", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                }
                                OutlinedButton(
                                    onClick = onSnoozeAlarm,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("overlay_settings_snooze_alarm_btn")
                                ) {
                                    Icon(Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("SNOOZE 5M", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // 1. Unified Hand Execution (Separated BET & CASH OUT Controls)
                OverlaySettingsSectionCard(
                    title = "BET & CASH OUT CONTROLS",
                    icon = Icons.Default.PlayArrow,
                    modifier = Modifier.testTag("overlay_settings_auto_bet_card")
                ) {
                    Text(
                        text = "BET SETTINGS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = PrimaryCobalt
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (localConfig.isAutoBetEnabled) "Auto-Bet: AUTOMATIC" else "Auto-Bet: MANUAL TAP",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (localConfig.isAutoBetEnabled) ActionStartEmerald else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "When OFF, tap the Unified Action Hand to place a verified bet manually.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = localConfig.isAutoBetEnabled,
                            onCheckedChange = { pushConfig(localConfig.copy(isAutoBetEnabled = it)) },
                            modifier = Modifier.testTag("overlay_settings_auto_bet_switch")
                        )
                    }

                    OutlinedTextField(
                        value = baseStakeText,
                        onValueChange = { raw ->
                            baseStakeText = raw
                            raw.toDoubleOrNull()?.let { v ->
                                if (v >= 1.0) pushConfig(localConfig.copy(baseStake = v))
                            }
                        },
                        label = { Text("Base Stake (min 1.0)", fontSize = 11.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overlay_settings_base_stake_input")
                    )

                    OverlayToggleRow(
                        label = "Verify Stake Input Read-Back",
                        checked = localConfig.verifyStakeReadBack,
                        testTag = "overlay_settings_verify_stake_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(verifyStakeReadBack = it)) }
                    )
                    OverlayToggleRow(
                        label = "Dismiss Keyboard Safely Before Bet",
                        checked = localConfig.dismissKeyboardBeforeBet,
                        testTag = "overlay_settings_dismiss_keyboard_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(dismissKeyboardBeforeBet = it)) }
                    )
                    OverlayToggleRow(
                        label = "Configure Game-Side Auto Cash Out",
                        checked = localConfig.gameAutoCashOutEnabled,
                        testTag = "overlay_settings_game_auto_cashout_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(gameAutoCashOutEnabled = it)) }
                    )
                    if (localConfig.gameAutoCashOutEnabled) {
                        OutlinedTextField(
                            value = gameAutoCashTargetText,
                            onValueChange = { raw ->
                                gameAutoCashTargetText = raw
                                raw.toDoubleOrNull()?.let { v ->
                                    if (v >= 1.01) pushConfig(localConfig.copy(gameAutoCashOutTarget = v))
                                }
                            },
                            label = { Text("Game Auto Cash Out Target (e.g. 2.00x)", fontSize = 11.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("overlay_settings_game_auto_cashout_target_input")
                        )
                        Text("Auto Cash Out Mode", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("OFF", "ENSURE", "SKIP_IF_ALREADY_SET").forEach { m ->
                                FilterChip(
                                    selected = localConfig.gameAutoCashOutMode == m,
                                    onClick = { pushConfig(localConfig.copy(gameAutoCashOutMode = m)) },
                                    label = { Text(m, fontSize = 9.sp) },
                                    modifier = Modifier.testTag("overlay_settings_autocash_mode_$m")
                                )
                            }
                        }
                    }
                    OverlayToggleRow(
                        label = "Also cash out bets I placed by hand",
                        checked = localConfig.cashOutUntrackedBets,
                        testTag = "overlay_settings_cashout_untracked_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(cashOutUntrackedBets = it)) }
                    )
                    OverlayToggleRow(
                        label = "Allow betting while the plane is flying (queues next round)",
                        checked = localConfig.queueBetDuringFlight,
                        testTag = "overlay_settings_queue_bet_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(queueBetDuringFlight = it)) }
                    )
                    OutlinedTextField(
                        value = minStakeText,
                        onValueChange = { raw ->
                            minStakeText = raw
                            raw.toDoubleOrNull()?.takeIf { it >= 0.0 }?.let { v -> pushConfig(localConfig.copy(minStake = v)) }
                        },
                        label = { Text("Min stake (0 = no limit)", fontSize = 11.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overlay_settings_min_stake_input")
                    )
                    OutlinedTextField(
                        value = maxStakeText,
                        onValueChange = { raw ->
                            maxStakeText = raw
                            raw.toDoubleOrNull()?.takeIf { it >= 0.0 }?.let { v -> pushConfig(localConfig.copy(maxStake = v)) }
                        },
                        label = { Text("Max stake (0 = no limit)", fontSize = 11.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overlay_settings_max_stake_input")
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    Text(
                        text = "CASH OUT SETTINGS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = PrimaryCobalt
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (localConfig.isAutoCashOutEnabled) "Auto Cash Out: AUTOMATIC" else "Auto Cash Out: MANUAL TAP",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (localConfig.isAutoCashOutEnabled) ActionStartEmerald else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Independent from Auto-Bet. Controls automatic Cash Out at target multiplier.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = localConfig.isAutoCashOutEnabled,
                            onCheckedChange = { pushConfig(localConfig.copy(isAutoCashOutEnabled = it)) },
                            modifier = Modifier.testTag("overlay_settings_auto_cash_out_switch")
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = targetMultText,
                            onValueChange = { raw ->
                                targetMultText = raw
                                raw.toDoubleOrNull()?.let { v ->
                                    if (v >= 1.01) pushConfig(localConfig.copy(targetMultiplier = v))
                                }
                            },
                            label = { Text("Target Multiplier (e.g. 2.00x)", fontSize = 11.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("overlay_settings_target_multiplier_input")
                        )
                        OutlinedTextField(
                            value = minFlightTimeText,
                            onValueChange = { raw ->
                                minFlightTimeText = raw
                                raw.toLongOrNull()?.let { v ->
                                    if (v in 0L..60_000L) pushConfig(localConfig.copy(minFlightTimeBeforeCashOutMs = v))
                                }
                            },
                            label = { Text("Min Flight (ms)", fontSize = 11.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("overlay_settings_min_flight_input")
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    OverlayToggleRow(
                        label = "Automation Master Lock",
                        checked = localConfig.automationMasterEnabled,
                        testTag = "overlay_settings_automation_master_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(automationMasterEnabled = it)) }
                    )
                    OverlayToggleRow(
                        label = "Auto Game Recognition",
                        checked = localConfig.autoGameRecognitionEnabled,
                        testTag = "overlay_settings_auto_game_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(autoGameRecognitionEnabled = it)) }
                    )
                    OverlayToggleRow(
                        label = "Collect & Record Crash History",
                        checked = localConfig.historyCollectionEnabled,
                        testTag = "overlay_settings_history_collection_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(historyCollectionEnabled = it)) }
                    )

                    val effectiveActionHandClick = onToggleActionHand ?: onToggleBetHand
                    if (effectiveActionHandClick != null || onOpenHandSettings != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (effectiveActionHandClick != null) {
                                OutlinedButton(
                                    onClick = effectiveActionHandClick,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("overlay_settings_action_hand_btn")
                                ) {
                                    Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("ACTION HAND", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (onOpenHandSettings != null) {
                                OutlinedButton(
                                    onClick = onOpenHandSettings,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("overlay_settings_hand_settings_btn")
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("HAND SETTINGS", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // 2. Screen Awake & Operating Schedule
                OverlaySettingsSectionCard(
                    title = "SCREEN AWAKE & OPERATING HOURS",
                    icon = Icons.Default.BatteryChargingFull,
                    trailingBadge = {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (isScreenHeld) ActionStartEmerald.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = if (isScreenHeld) "SCREEN HELD" else "NORMAL SLEEP",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isScreenHeld) ActionStartEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    },
                    modifier = Modifier.testTag("overlay_settings_awake_schedule_card")
                ) {
                    Text(
                        text = "Hardware: Screen=${if (isScreenHeld) "ACTIVE" else "INACTIVE"} · CPU=${if (isCpuAwakeHeld) "HELD" else "RELEASED"}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isScreenHeld || isCpuAwakeHeld) ActionStartEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!awakeError.isNullOrBlank()) {
                        Text(
                            text = awakeError,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ActionStopRed
                        )
                    }
                    OverlayToggleRow(
                        label = "Keep Screen Awake During Active Monitoring",
                        checked = localConfig.keepScreenAwake,
                        testTag = "overlay_settings_keep_awake_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(keepScreenAwake = it)) }
                    )
                    OverlayToggleRow(
                        label = "Enable Scheduled Operating Window",
                        checked = localConfig.scheduleEnabled,
                        testTag = "overlay_settings_schedule_switch",
                        onCheckedChange = { pushConfig(localConfig.copy(scheduleEnabled = it)) }
                    )
                    if (localConfig.scheduleEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = startTimeText,
                                onValueChange = { startTimeText = it },
                                label = { Text("Start (HH:MM)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("overlay_settings_schedule_start_input")
                            )
                            OutlinedTextField(
                                value = stopTimeText,
                                onValueChange = { stopTimeText = it },
                                label = { Text("Stop (HH:MM)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("overlay_settings_schedule_stop_input")
                            )
                        }
                        val windowLabel = OperatingScheduleEvaluator.formatWindow(
                            localConfig.scheduleStartHour,
                            localConfig.scheduleStartMinute,
                            localConfig.scheduleEndHour,
                            localConfig.scheduleEndMinute
                        )
                        Surface(
                            color = if (isStandbyOutsideSchedule) Color(0xFFFEF3C7) else ActionStartEmerald.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = if (isStandbyOutsideSchedule) Color(0xFF92400E) else ActionStartEmerald,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isStandbyOutsideSchedule) "Standby ($windowLabel)" else "Active ($windowLabel)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isStandbyOutsideSchedule) Color(0xFF92400E) else ActionStartEmerald
                                )
                            }
                        }
                    }
                }

                // 3. Crash Grid Size & Thresholds
                OverlaySettingsSectionCard(
                    title = "GRID SIZE (${localConfig.gridRows}×${localConfig.gridColumns}) & THRESHOLDS",
                    icon = Icons.Default.GridView,
                    modifier = Modifier.testTag("overlay_settings_grid_thresholds_card")
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(4, 5).forEach { r ->
                            FilterChip(
                                selected = localConfig.gridRows == r,
                                onClick = { pushConfig(localConfig.copy(gridRows = r)) },
                                label = { Text("$r Rows", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                modifier = Modifier.testTag("overlay_settings_grid_rows_$r")
                            )
                        }
                        listOf(7, 8).forEach { c ->
                            FilterChip(
                                selected = localConfig.gridColumns == c,
                                onClick = { pushConfig(localConfig.copy(gridColumns = c)) },
                                label = { Text("$c Cols", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                modifier = Modifier.testTag("overlay_settings_grid_cols_$c")
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = lowThreshText,
                            onValueChange = { lowThreshText = it },
                            label = { Text("Low (< x)", fontSize = 10.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("overlay_settings_low_threshold_input")
                        )
                        OutlinedTextField(
                            value = boundaryText,
                            onValueChange = { boundaryText = it },
                            label = { Text("Bound (==)", fontSize = 10.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("overlay_settings_boundary_input")
                        )
                        OutlinedTextField(
                            value = bigThreshText,
                            onValueChange = { bigThreshText = it },
                            label = { Text("Big (>= x)", fontSize = 10.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("overlay_settings_big_threshold_input")
                        )
                    }
                }

                // 4. Alerts, Quiet Hours & Bubble Appearance
                OverlaySettingsSectionCard(
                    title = "ALERTS & OVERLAY APPEARANCE",
                    icon = Icons.Default.Notifications,
                    modifier = Modifier.testTag("overlay_settings_alerts_appearance_card")
                ) {
                    OverlayToggleRow(
                        label = "Master Pattern & System Alerts",
                        checked = localNotif.masterEnabled,
                        testTag = "overlay_settings_notif_master_switch",
                        onCheckedChange = { pushNotif(localNotif.copy(masterEnabled = it)) }
                    )
                    OverlayToggleRow(
                        label = "Pattern Detected Alerts",
                        checked = localNotif.reachedEnabled,
                        testTag = "overlay_settings_notif_reached_switch",
                        onCheckedChange = { pushNotif(localNotif.copy(reachedEnabled = it)) }
                    )
                    OverlayToggleRow(
                        label = "Signal Alarm (Pattern Match)",
                        checked = localNotif.highPriorityAlarmEnabled,
                        testTag = "overlay_settings_signal_alarm_switch",
                        onCheckedChange = { pushNotif(localNotif.copy(highPriorityAlarmEnabled = it)) }
                    )
                    OverlayToggleRow(
                        label = "Quiet Hours",
                        checked = localNotif.quietHoursEnabled,
                        testTag = "overlay_settings_notif_quiet_switch",
                        onCheckedChange = { pushNotif(localNotif.copy(quietHoursEnabled = it)) }
                    )
                    if (localNotif.quietHoursEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = quietStartHourText,
                                onValueChange = { quietStartHourText = it },
                                label = { Text("Quiet Start (0-23)", fontSize = 10.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("overlay_settings_quiet_start_input")
                            )
                            OutlinedTextField(
                                value = quietEndHourText,
                                onValueChange = { quietEndHourText = it },
                                label = { Text("Quiet End (0-23)", fontSize = 10.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("overlay_settings_quiet_end_input")
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = PrimaryCobalt, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Bubble Opacity (${(opacityValue * 100).toInt()}%)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Slider(
                        value = opacityValue,
                        onValueChange = { opacityValue = it },
                        valueRange = 0.3f..1.0f,
                        modifier = Modifier.testTag("overlay_settings_opacity_slider")
                    )

                    Text(
                        text = "Bubble Size (${bubbleSizeDp.toInt()}dp)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = bubbleSizeDp,
                        onValueChange = { bubbleSizeDp = it },
                        valueRange = 44f..80f,
                        modifier = Modifier.testTag("overlay_settings_bubble_size_slider")
                    )

                    OverlayToggleRow(
                        label = "Haptic Feedback",
                        checked = hapticsEnabled,
                        testTag = "overlay_settings_haptics_switch",
                        onCheckedChange = { hapticsEnabled = it }
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AssistChip(
                            onClick = { onTestAlarm(localNotif) },
                            label = { Text("Test Alarm", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = {
                                Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(15.dp))
                            },
                            modifier = Modifier.testTag("overlay_settings_test_alarm_btn")
                        )
                        AssistChip(
                            onClick = onResetSessionStats,
                            label = { Text("Reset Stats", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            leadingIcon = {
                                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                            },
                            modifier = Modifier.testTag("overlay_settings_reset_session_btn")
                        )
                    }

                    Text(
                        text = "Need ringtone pickers, per-category alarm profiles, or Android permissions? Tap Full Screen in the top bar.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!validationError.isNullOrBlank()) {
                    Surface(
                        color = Color(0xFFFEE2E2),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overlay_settings_error_banner")
                    ) {
                        Text(
                            text = validationError ?: "",
                            color = ActionStopRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            HorizontalDivider()

            // Sticky Bottom Save Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onExpandFullScreen,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("overlay_settings_footer_fullscreen_btn")
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Full Screen", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        val parsedStake = baseStakeText.trim().toDoubleOrNull()
                        val parsedTarget = targetMultText.trim().toDoubleOrNull()
                        val parsedGameAutoTarget = gameAutoCashTargetText.trim().toDoubleOrNull()
                        val parsedMinFlight = minFlightTimeText.trim().toLongOrNull()
                        val parsedLow = lowThreshText.trim().toDoubleOrNull()
                        val parsedBoundary = boundaryText.trim().toDoubleOrNull()
                        val parsedBig = bigThreshText.trim().toDoubleOrNull()
                        val startPair = parseOverlayHourMinute(startTimeText)
                        val stopPair = parseOverlayHourMinute(stopTimeText)
                        val quietStart = quietStartHourText.trim().toIntOrNull()
                        val quietEnd = quietEndHourText.trim().toIntOrNull()

                        val stakeInvalid = parsedStake == null || !parsedStake.isFinite() || parsedStake < 1.0
                        val targetInvalid = parsedTarget == null || !parsedTarget.isFinite() || parsedTarget < 1.01
                        val gameAutoInvalid = localConfig.gameAutoCashOutEnabled &&
                            (parsedGameAutoTarget == null || !parsedGameAutoTarget.isFinite() || parsedGameAutoTarget < 1.01)
                        val flightInvalid = parsedMinFlight == null || parsedMinFlight !in 0L..60_000L
                        val thresholdsInvalid = parsedLow == null || !parsedLow.isFinite() || parsedLow < 1.01 ||
                            parsedBoundary == null || !parsedBoundary.isFinite() || parsedBoundary < 1.01 ||
                            parsedBig == null || !parsedBig.isFinite() || parsedBig <= parsedLow
                        val scheduleInvalid = localConfig.scheduleEnabled && (startPair == null || stopPair == null)
                        val quietInvalid = localNotif.quietHoursEnabled &&
                            (quietStart == null || quietStart !in 0..23 || quietEnd == null || quietEnd !in 0..23)

                        if (stakeInvalid || targetInvalid || gameAutoInvalid || flightInvalid || thresholdsInvalid || scheduleInvalid || quietInvalid) {
                            validationError = "Please check numeric inputs (Stake >= 1.0, Targets >= 1.01, Big > Low, HH:MM)."
                            runCatching {
                                Toast.makeText(context, "Please check settings inputs", Toast.LENGTH_SHORT).show()
                            }
                            return@Button
                        }

                        validationError = null
                        val finalizedConfig = localConfig.copy(
                            baseStake = parsedStake!!,
                            targetMultiplier = parsedTarget!!,
                            gameAutoCashOutTarget = parsedGameAutoTarget ?: localConfig.gameAutoCashOutTarget,
                            minFlightTimeBeforeCashOutMs = parsedMinFlight!!,
                            lowThreshold = parsedLow!!,
                            boundaryValue = parsedBoundary!!,
                            bigThreshold = parsedBig!!,
                            strategy1LowThreshold = parsedLow,
                            strategy2HighThreshold = parsedBig,
                            scheduleStartHour = startPair?.first ?: localConfig.scheduleStartHour,
                            scheduleStartMinute = startPair?.second ?: localConfig.scheduleStartMinute,
                            scheduleEndHour = stopPair?.first ?: localConfig.scheduleEndHour,
                            scheduleEndMinute = stopPair?.second ?: localConfig.scheduleEndMinute
                        )
                        val finalizedNotif = localNotif.copy(
                            quietStartHour = quietStart ?: localNotif.quietStartHour,
                            quietEndHour = quietEnd ?: localNotif.quietEndHour
                        )
                        onSaveOverlaySettings(
                            finalizedConfig,
                            finalizedNotif,
                            opacityValue.coerceIn(0.3f, 1.0f),
                            bubbleSizeDp.coerceIn(44f, 80f),
                            hapticsEnabled
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("overlay_settings_save_btn")
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SAVE & CLOSE", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun OverlaySettingsSectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailingBadge: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(icon, contentDescription = null, tint = PrimaryCobalt, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = PrimaryCobalt,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (trailingBadge != null) {
                    trailingBadge()
                }
            }
            content()
        }
    }
}

@Composable
private fun OverlayToggleRow(
    label: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

private fun parseOverlayHourMinute(raw: String): Pair<Int, Int>? {
    val parts = raw.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].trim().toIntOrNull() ?: return null
    val m = parts[1].trim().toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h to m
}

/**
 * Dedicated Action Hand Settings window with two clearly separated sections:
 *  - BET SETTINGS (manual/automatic mode, stake, verification, keyboard dismiss, game-side Auto Cash Out, window timeouts)
 *  - CASH OUT SETTINGS (manual/automatic mode, target multiplier, minimum flight time)
 * Wired directly to the single authoritative Room StrategyConfig so General Settings never conflict.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DedicatedHandSettingsComposable(
    strategyConfig: StrategyConfig,
    onSaveHandSettings: (StrategyConfig) -> Unit,
    onClose: () -> Unit,
    onToggleActionHand: (() -> Unit)? = null,
    onOpenGeneralSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var localConfig by remember(strategyConfig) { mutableStateOf(strategyConfig) }

    var baseStakeText by remember(strategyConfig.baseStake) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.baseStake))
    }
    var gameAutoTargetText by remember(strategyConfig.gameAutoCashOutTarget) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.gameAutoCashOutTarget))
    }
    var stakeTimeoutText by remember(strategyConfig.stakeEditTimeoutMs) {
        mutableStateOf(strategyConfig.stakeEditTimeoutMs.toString())
    }
    var autoCashTimeoutText by remember(strategyConfig.autoCashOutTimeoutMs) {
        mutableStateOf(strategyConfig.autoCashOutTimeoutMs.toString())
    }
    var totalWindowTimeoutText by remember(strategyConfig.betWindowTotalTimeoutMs) {
        mutableStateOf(strategyConfig.betWindowTotalTimeoutMs.toString())
    }
    var minRemWindowText by remember(strategyConfig.minRemainingBetWindowMs) {
        mutableStateOf(strategyConfig.minRemainingBetWindowMs.toString())
    }
    var targetMultText by remember(strategyConfig.targetMultiplier) {
        mutableStateOf(String.format(Locale.US, "%.2f", strategyConfig.targetMultiplier))
    }
    var minFlightTimeText by remember(strategyConfig.minFlightTimeBeforeCashOutMs) {
        mutableStateOf(strategyConfig.minFlightTimeBeforeCashOutMs.toString())
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = modifier
            .shadow(18.dp, RoundedCornerShape(22.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp))
            .testTag("dedicated_hand_settings_window"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = "Action Hand Settings",
                            tint = PrimaryCobalt,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "ACTION HAND SETTINGS",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Authoritative Bet & Cash Out Execution Config",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("hand_settings_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Hand Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // SECTION 1: BET SETTINGS
                OverlaySettingsSectionCard(
                    title = "BET SETTINGS",
                    icon = Icons.Default.PlayArrow,
                    trailingBadge = {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (localConfig.isAutoBetEnabled) {
                                ActionStartEmerald.copy(alpha = 0.14f)
                            } else {
                                Color(0xFFDBEAFE)
                            }
                        ) {
                            Text(
                                text = if (localConfig.isAutoBetEnabled) "AUTOMATIC" else "MANUAL TAP",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (localConfig.isAutoBetEnabled) ActionStartEmerald else PrimaryCobalt,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    },
                    modifier = Modifier.testTag("hand_settings_bet_section")
                ) {
                    OverlayToggleRow(
                        label = "Automatic Bet (ON = auto-execute window, OFF = manual single-tap Bet)",
                        checked = localConfig.isAutoBetEnabled,
                        testTag = "hand_settings_auto_bet_switch",
                        onCheckedChange = { localConfig = localConfig.copy(isAutoBetEnabled = it) }
                    )

                    OutlinedTextField(
                        value = baseStakeText,
                        onValueChange = { baseStakeText = it },
                        label = { Text("Base Stake (min 1.0)", fontSize = 11.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hand_settings_base_stake_input")
                    )

                    OverlayToggleRow(
                        label = "Verify Stake Input Read-Back Before Bet",
                        checked = localConfig.verifyStakeReadBack,
                        testTag = "hand_settings_verify_stake_switch",
                        onCheckedChange = { localConfig = localConfig.copy(verifyStakeReadBack = it) }
                    )

                    OverlayToggleRow(
                        label = "Dismiss Keyboard Safely Before Bet",
                        checked = localConfig.dismissKeyboardBeforeBet,
                        testTag = "hand_settings_dismiss_keyboard_switch",
                        onCheckedChange = { localConfig = localConfig.copy(dismissKeyboardBeforeBet = it) }
                    )

                    OverlayToggleRow(
                        label = "Enable Game-Side Auto Cash Out",
                        checked = localConfig.gameAutoCashOutEnabled,
                        testTag = "hand_settings_game_auto_cashout_switch",
                        onCheckedChange = { localConfig = localConfig.copy(gameAutoCashOutEnabled = it) }
                    )

                    if (localConfig.gameAutoCashOutEnabled) {
                        OutlinedTextField(
                            value = gameAutoTargetText,
                            onValueChange = { gameAutoTargetText = it },
                            label = { Text("Game Auto Cash Out Target (e.g. 2.00x)", fontSize = 11.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("hand_settings_game_auto_cashout_target_input")
                        )
                        Text("Auto Cash Out Mode", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("OFF", "ENSURE", "SKIP_IF_ALREADY_SET").forEach { m ->
                                FilterChip(
                                    selected = localConfig.gameAutoCashOutMode == m,
                                    onClick = { localConfig = localConfig.copy(gameAutoCashOutMode = m) },
                                    label = { Text(m, fontSize = 9.sp) },
                                    modifier = Modifier.testTag("hand_settings_autocash_mode_$m")
                                )
                            }
                        }
                    }

                    Text(
                        text = "Betting-Window Execution Timeouts (ms)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = stakeTimeoutText,
                            onValueChange = { stakeTimeoutText = it },
                            label = { Text("Stake Target", fontSize = 10.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hand_settings_stake_timeout_input")
                        )
                        OutlinedTextField(
                            value = autoCashTimeoutText,
                            onValueChange = { autoCashTimeoutText = it },
                            label = { Text("AutoCash", fontSize = 10.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hand_settings_autocash_timeout_input")
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = totalWindowTimeoutText,
                            onValueChange = { totalWindowTimeoutText = it },
                            label = { Text("Window Total", fontSize = 10.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hand_settings_window_total_timeout_input")
                        )
                        OutlinedTextField(
                            value = minRemWindowText,
                            onValueChange = { minRemWindowText = it },
                            label = { Text("Min Remain", fontSize = 10.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hand_settings_min_remaining_window_input")
                        )
                    }
                }

                // SECTION 2: CASH OUT SETTINGS
                OverlaySettingsSectionCard(
                    title = "CASH OUT SETTINGS",
                    icon = Icons.Default.Security,
                    trailingBadge = {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (localConfig.isAutoCashOutEnabled) {
                                ActionStartEmerald.copy(alpha = 0.14f)
                            } else {
                                Color(0xFFFEF3C7)
                            }
                        ) {
                            Text(
                                text = if (localConfig.isAutoCashOutEnabled) "AUTOMATIC" else "MANUAL TAP",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (localConfig.isAutoCashOutEnabled) ActionStartEmerald else Color(0xFFB45309),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    },
                    modifier = Modifier.testTag("hand_settings_cashout_section")
                ) {
                    OverlayToggleRow(
                        label = "Automatic Cash Out (ON = auto at Target, OFF = manual single-tap Cash Out)",
                        checked = localConfig.isAutoCashOutEnabled,
                        testTag = "hand_settings_auto_cashout_switch",
                        onCheckedChange = { localConfig = localConfig.copy(isAutoCashOutEnabled = it) }
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = targetMultText,
                            onValueChange = { targetMultText = it },
                            label = { Text("Target Multiplier (x)", fontSize = 11.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hand_settings_target_multiplier_input")
                        )
                        OutlinedTextField(
                            value = minFlightTimeText,
                            onValueChange = { minFlightTimeText = it },
                            label = { Text("Min Flight Time (ms)", fontSize = 11.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("hand_settings_min_flight_input")
                        )
                    }
                }

                if (onToggleActionHand != null || onOpenGeneralSettings != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (onToggleActionHand != null) {
                            OutlinedButton(
                                onClick = onToggleActionHand,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("hand_settings_toggle_hand_btn")
                            ) {
                                Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Show/Hide Hand", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (onOpenGeneralSettings != null) {
                            OutlinedButton(
                                onClick = onOpenGeneralSettings,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("hand_settings_general_btn")
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("General Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (!validationError.isNullOrBlank()) {
                    Surface(
                        color = Color(0xFFFEE2E2),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hand_settings_error_banner")
                    ) {
                        Text(
                            text = validationError ?: "",
                            color = ActionStopRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onClose,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("CANCEL", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        val stake = baseStakeText.trim().toDoubleOrNull()
                        val targetMult = targetMultText.trim().toDoubleOrNull()
                        val gameAutoTarget = gameAutoTargetText.trim().toDoubleOrNull()
                        val minFlight = minFlightTimeText.trim().toLongOrNull()
                        val stakeTimeout = stakeTimeoutText.trim().toLongOrNull()
                        val autoCashTimeout = autoCashTimeoutText.trim().toLongOrNull()
                        val totalTimeout = totalWindowTimeoutText.trim().toLongOrNull()
                        val minRem = minRemWindowText.trim().toLongOrNull()

                        val invalid = stake == null || !stake.isFinite() || stake < 1.0 ||
                            targetMult == null || !targetMult.isFinite() || targetMult < 1.01 ||
                            (localConfig.gameAutoCashOutEnabled && (gameAutoTarget == null || !gameAutoTarget.isFinite() || gameAutoTarget < 1.01)) ||
                            minFlight == null || minFlight !in 0L..60_000L ||
                            stakeTimeout == null || stakeTimeout !in 150L..5_000L ||
                            autoCashTimeout == null || autoCashTimeout !in 150L..5_000L ||
                            totalTimeout == null || totalTimeout !in 500L..15_000L ||
                            minRem == null || minRem !in 300L..10_000L

                        if (invalid) {
                            validationError = "Invalid input: Stake >= 1.0, Targets >= 1.01, Flight 0–60000ms, Timeouts 150–15000ms."
                            runCatching { Toast.makeText(context, validationError, Toast.LENGTH_SHORT).show() }
                            return@Button
                        }

                        validationError = null
                        onSaveHandSettings(
                            localConfig.copy(
                                baseStake = stake!!,
                                targetMultiplier = targetMult!!,
                                gameAutoCashOutTarget = gameAutoTarget ?: localConfig.gameAutoCashOutTarget,
                                minFlightTimeBeforeCashOutMs = minFlight!!,
                                stakeEditTimeoutMs = stakeTimeout!!,
                                autoCashOutTimeoutMs = autoCashTimeout!!,
                                betWindowTotalTimeoutMs = totalTimeout!!,
                                minRemainingBetWindowMs = minRem!!
                            )
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("hand_settings_save_btn")
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("SAVE HAND SETTINGS", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
