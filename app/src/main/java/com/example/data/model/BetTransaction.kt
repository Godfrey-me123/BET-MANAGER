package com.example.data.model

/**
 * Authoritative lifecycle states for traceable bet transactions.
 * Every bet must transition cleanly through these states.
 */
enum class BetTransactionLifecycleState {
    INTENT_RECORDED,
    STAKE_VERIFIED,
    AUTO_CASHOUT_VERIFIED,
    BET_DISPATCHED,
    BET_ACCEPTED,
    BET_UNCONFIRMED,
    CASHOUT_DISPATCHED,
    CASHOUT_CONFIRMED,
    ROUND_LOST,
    OUTCOME_UNKNOWN,
    SETTLEMENT_RECORDED
}

/**
 * Granular evidence entry associated with a transaction state transition.
 */
data class TransactionEvidence(
    val stage: String,
    val timestampMs: Long,
    val details: String,
    val success: Boolean = true
)

/**
 * Authoritative record of a single bet transaction across its entire lifecycle.
 */
data class BetTransactionRecord(
    val transactionId: String,
    val gameId: String,
    val roundId: String,
    val intendedStake: Double,
    var acceptedStake: Double? = null,
    val targetMultiplier: Double,
    var state: BetTransactionLifecycleState = BetTransactionLifecycleState.INTENT_RECORDED,
    val evidenceList: MutableList<TransactionEvidence> = mutableListOf(),
    val createdAtMs: Long = System.currentTimeMillis(),
    var updatedAtMs: Long = System.currentTimeMillis(),
    var payout: Double = 0.0,
    var netProfit: Double = 0.0,
    var failureReason: String? = null
)
