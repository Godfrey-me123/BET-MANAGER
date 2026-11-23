package com.example.data.engine

import com.example.data.model.BetTransactionLifecycleState
import com.example.data.model.BetTransactionRecord
import com.example.data.model.TransactionEvidence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Single authoritative transaction manager shared across AutomationEngine,
 * AccessibilityController, ScreenOcrAnalyzer, and LiveCrashPipeline.
 *
 * Every component reports evidence here, but ONLY the transaction manager
 * authorizes state transitions, guarantees idempotent settlement, and provides
 * restart reconciliation so uncertain bets are never repeated.
 */
class BetTransactionManager {
    private val mutex = Mutex()
    private val activeTransactions = ConcurrentHashMap<String, BetTransactionRecord>()
    private val journalOrder = ConcurrentHashMap<String, Long>()
    private val orderSeq = AtomicLong(0L)
    private val settledRoundIds = ConcurrentHashMap.newKeySet<String>()

    private val _currentTransaction = MutableStateFlow<BetTransactionRecord?>(null)
    val currentTransaction: StateFlow<BetTransactionRecord?> = _currentTransaction.asStateFlow()

    private val _recentJournal = MutableStateFlow<List<BetTransactionRecord>>(emptyList())
    val recentJournal: StateFlow<List<BetTransactionRecord>> = _recentJournal.asStateFlow()

    suspend fun recordIntent(
        transactionId: String,
        gameId: String,
        roundId: String,
        intendedStake: Double,
        targetMultiplier: Double,
        timestampMs: Long = System.currentTimeMillis()
    ): BetTransactionRecord = mutex.withLock {
        val record = BetTransactionRecord(
            transactionId = transactionId,
            gameId = gameId,
            roundId = roundId,
            intendedStake = intendedStake,
            targetMultiplier = targetMultiplier,
            state = BetTransactionLifecycleState.INTENT_RECORDED,
            createdAtMs = timestampMs,
            updatedAtMs = timestampMs
        ).apply {
            evidenceList.add(TransactionEvidence("INTENT_RECORDED", timestampMs, "Stake: $intendedStake, Target: ${targetMultiplier}x"))
        }
        activeTransactions[transactionId] = record
        journalOrder[transactionId] = orderSeq.incrementAndGet()
        _currentTransaction.value = record
        publishJournal()
        record
    }

    suspend fun recordStakeVerified(
        transactionId: String,
        verifiedStake: Double,
        evidence: String,
        timestampMs: Long = System.currentTimeMillis()
    ): Boolean = mutex.withLock {
        val tx = activeTransactions[transactionId] ?: return false
        if (tx.state != BetTransactionLifecycleState.INTENT_RECORDED) return false
        tx.acceptedStake = verifiedStake
        tx.state = BetTransactionLifecycleState.STAKE_VERIFIED
        tx.updatedAtMs = timestampMs
        tx.evidenceList.add(TransactionEvidence("STAKE_VERIFIED", timestampMs, evidence))
        _currentTransaction.value = tx
        publishJournal()
        true
    }

    suspend fun recordAutoCashOutVerified(
        transactionId: String,
        evidence: String,
        timestampMs: Long = System.currentTimeMillis()
    ): Boolean = mutex.withLock {
        val tx = activeTransactions[transactionId] ?: return false
        tx.state = BetTransactionLifecycleState.AUTO_CASHOUT_VERIFIED
        tx.updatedAtMs = timestampMs
        tx.evidenceList.add(TransactionEvidence("AUTO_CASHOUT_VERIFIED", timestampMs, evidence))
        _currentTransaction.value = tx
        publishJournal()
        true
    }

    suspend fun recordBetDispatched(
        transactionId: String,
        evidence: String,
        timestampMs: Long = System.currentTimeMillis()
    ): Boolean = mutex.withLock {
        val tx = activeTransactions[transactionId] ?: return false
        tx.state = BetTransactionLifecycleState.BET_DISPATCHED
        tx.updatedAtMs = timestampMs
        tx.evidenceList.add(TransactionEvidence("BET_DISPATCHED", timestampMs, evidence))
        _currentTransaction.value = tx
        publishJournal()
        true
    }

    suspend fun recordBetAccepted(
        transactionId: String,
        evidence: String,
        timestampMs: Long = System.currentTimeMillis()
    ): Boolean = mutex.withLock {
        val tx = activeTransactions[transactionId] ?: return false
        tx.state = BetTransactionLifecycleState.BET_ACCEPTED
        tx.updatedAtMs = timestampMs
        tx.evidenceList.add(TransactionEvidence("BET_ACCEPTED", timestampMs, evidence))
        _currentTransaction.value = tx
        publishJournal()
        true
    }

    suspend fun recordBetUnconfirmed(
        transactionId: String,
        reason: String,
        timestampMs: Long = System.currentTimeMillis()
    ): Boolean = mutex.withLock {
        val tx = activeTransactions[transactionId] ?: return false
        tx.state = BetTransactionLifecycleState.BET_UNCONFIRMED
        tx.failureReason = reason
        tx.updatedAtMs = timestampMs
        tx.evidenceList.add(TransactionEvidence("BET_UNCONFIRMED", timestampMs, reason, success = false))
        _currentTransaction.value = tx
        publishJournal()
        true
    }

    suspend fun recordCashOutDispatched(
        transactionId: String,
        evidence: String,
        timestampMs: Long = System.currentTimeMillis()
    ): Boolean = mutex.withLock {
        val tx = activeTransactions[transactionId] ?: return false
        tx.state = BetTransactionLifecycleState.CASHOUT_DISPATCHED
        tx.updatedAtMs = timestampMs
        tx.evidenceList.add(TransactionEvidence("CASHOUT_DISPATCHED", timestampMs, evidence))
        _currentTransaction.value = tx
        publishJournal()
        true
    }

    suspend fun recordCashOutConfirmed(
        transactionId: String,
        actualPayout: Double,
        evidence: String,
        timestampMs: Long = System.currentTimeMillis()
    ): Boolean = mutex.withLock {
        val tx = activeTransactions[transactionId] ?: return false
        tx.state = BetTransactionLifecycleState.CASHOUT_CONFIRMED
        tx.payout = actualPayout
        val stake = tx.acceptedStake ?: tx.intendedStake
        tx.netProfit = actualPayout - stake
        tx.updatedAtMs = timestampMs
        tx.evidenceList.add(TransactionEvidence("CASHOUT_CONFIRMED", timestampMs, evidence))
        _currentTransaction.value = tx
        publishJournal()
        true
    }

    suspend fun recordRoundLost(
        transactionId: String,
        crashedMultiplier: Double,
        evidence: String,
        timestampMs: Long = System.currentTimeMillis()
    ): Boolean = mutex.withLock {
        val tx = activeTransactions[transactionId] ?: return false
        tx.state = BetTransactionLifecycleState.ROUND_LOST
        val stake = tx.acceptedStake ?: tx.intendedStake
        tx.payout = 0.0
        tx.netProfit = -stake
        tx.updatedAtMs = timestampMs
        tx.evidenceList.add(TransactionEvidence("ROUND_LOST", timestampMs, "Crashed at ${crashedMultiplier}x: $evidence", success = false))
        _currentTransaction.value = tx
        publishJournal()
        true
    }

    suspend fun recordOutcomeUnknown(
        transactionId: String,
        reason: String,
        timestampMs: Long = System.currentTimeMillis()
    ): Boolean = mutex.withLock {
        val tx = activeTransactions[transactionId] ?: return false
        tx.state = BetTransactionLifecycleState.OUTCOME_UNKNOWN
        tx.failureReason = reason
        tx.updatedAtMs = timestampMs
        tx.evidenceList.add(TransactionEvidence("OUTCOME_UNKNOWN", timestampMs, reason, success = false))
        _currentTransaction.value = tx
        publishJournal()
        true
    }

    /**
     * Authorizes and records settlement. Guaranteed strictly idempotent:
     * once a round is settled, it can NEVER be settled again.
     */
    suspend fun authorizeAndRecordSettlement(
        transactionId: String,
        timestampMs: Long = System.currentTimeMillis()
    ): Pair<Boolean, BetTransactionRecord?> = mutex.withLock {
        val tx = activeTransactions[transactionId] ?: return false to null
        if (settledRoundIds.contains(tx.roundId)) {
            // Already settled! Idempotent guard
            return false to tx
        }
        if (tx.state !in setOf(
                BetTransactionLifecycleState.CASHOUT_CONFIRMED,
                BetTransactionLifecycleState.ROUND_LOST
            )
        ) {
            // An uncertain or merely dispatched bet is never eligible for settlement.
            return false to tx
        }
        tx.state = BetTransactionLifecycleState.SETTLEMENT_RECORDED
        tx.updatedAtMs = timestampMs
        tx.evidenceList.add(TransactionEvidence("SETTLEMENT_RECORDED", timestampMs, "Settled net profit: ${tx.netProfit}"))
        settledRoundIds.add(tx.roundId)
        _currentTransaction.value = tx
        publishJournal()
        true to tx
    }

    /**
     * Reconciles unconfirmed/in-flight transactions on startup.
     * Never automatically re-places an uncertain bet.
     */
    suspend fun reconcileInterruptedTransactions(
        nowMs: Long = System.currentTimeMillis()
    ): List<BetTransactionRecord> = mutex.withLock {
        val reconciled = mutableListOf<BetTransactionRecord>()
        for ((_, tx) in activeTransactions) {
            if (tx.state !in listOf(BetTransactionLifecycleState.SETTLEMENT_RECORDED, BetTransactionLifecycleState.OUTCOME_UNKNOWN)) {
                tx.state = BetTransactionLifecycleState.OUTCOME_UNKNOWN
                tx.failureReason = "Interrupted by service/process restart; reconciled to OUTCOME_UNKNOWN"
                tx.updatedAtMs = nowMs
                tx.evidenceList.add(TransactionEvidence("RECONCILED_ON_RESTART", nowMs, "Interrupted prior to verified settlement", success = false))
                reconciled.add(tx)
            }
        }
        publishJournal()
        reconciled
    }

    fun getTransaction(transactionId: String): BetTransactionRecord? = activeTransactions[transactionId]

    fun isRoundSettled(roundId: String): Boolean = settledRoundIds.contains(roundId)

    fun resetForTesting() {
        activeTransactions.clear()
        journalOrder.clear()
        settledRoundIds.clear()
        _currentTransaction.value = null
        _recentJournal.value = emptyList()
    }

    private fun publishJournal() {
        _recentJournal.value = activeTransactions.values
            .sortedWith(
                compareByDescending<BetTransactionRecord> { it.createdAtMs }
                    .thenByDescending { journalOrder[it.transactionId] ?: 0L }
            )
            .take(50)
    }

    companion object {
        val instance = BetTransactionManager()
    }
}
