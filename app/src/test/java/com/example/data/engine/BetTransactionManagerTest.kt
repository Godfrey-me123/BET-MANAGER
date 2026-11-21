package com.example.data.engine

import com.example.data.model.BetTransactionLifecycleState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BetTransactionManagerTest {
    private fun manager() = BetTransactionManager()

    @Test
    fun `settlement is idempotent for one round`() = runBlocking {
        val manager = manager()
        manager.recordIntent("tx-1", "game-1", "round-1", 5.0, 2.0)
        manager.recordStakeVerified("tx-1", 5.0, "visible stake 5.00")
        manager.recordBetDispatched("tx-1", "verified bet target")
        manager.recordBetAccepted("tx-1", "fresh queued state")
        manager.recordCashOutConfirmed("tx-1", 10.0, "payout confirmed")

        val first = manager.authorizeAndRecordSettlement("tx-1")
        val second = manager.authorizeAndRecordSettlement("tx-1")

        assertTrue(first.first)
        assertFalse(second.first)
        assertEquals(BetTransactionLifecycleState.SETTLEMENT_RECORDED, first.second?.state)
        assertTrue(manager.isRoundSettled("round-1"))
    }

    @Test
    fun `interrupted dispatched bet becomes unknown and is not reauthorized`() = runBlocking {
        val manager = manager()
        manager.recordIntent("tx-2", "game-1", "round-2", 7.0, 1.8)
        manager.recordStakeVerified("tx-2", 7.0, "visible stake 7.00")
        manager.recordBetDispatched("tx-2", "verified bet target")

        val reconciled = manager.reconcileInterruptedTransactions()
        assertEquals(1, reconciled.size)
        assertEquals(BetTransactionLifecycleState.OUTCOME_UNKNOWN, manager.getTransaction("tx-2")?.state)
        assertFalse(manager.authorizeAndRecordSettlement("tx-2").first)
    }

    @Test
    fun `unconfirmed bet cannot be treated as an accepted bet`() = runBlocking {
        val manager = manager()
        manager.recordIntent("tx-3", "game-1", "round-3", 4.0, 2.0)
        manager.recordBetUnconfirmed("tx-3", "no fresh acceptance evidence")

        assertEquals(BetTransactionLifecycleState.BET_UNCONFIRMED, manager.getTransaction("tx-3")?.state)
        assertFalse(manager.authorizeAndRecordSettlement("tx-3").first)
    }
}
