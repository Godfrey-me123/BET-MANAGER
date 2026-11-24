package com.example

import com.example.data.engine.StakeCalculator
import com.example.data.model.StakeProgressionMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BetManagerRequirementsTest {

    @Test
    fun `T8 StakeCalculator progression rules for Double, Add, and Keep`() {
        // Double progression
        var stake = 10.0
        stake = StakeCalculator.calculateNextStake(stake, 10.0, StakeProgressionMode.DOUBLE_AMOUNT, 5.0, false) // 20
        assertEquals(20.0, stake, 0.001)
        stake = StakeCalculator.calculateNextStake(stake, 10.0, StakeProgressionMode.DOUBLE_AMOUNT, 5.0, false) // 40
        assertEquals(40.0, stake, 0.001)
        stake = StakeCalculator.calculateNextStake(stake, 10.0, StakeProgressionMode.DOUBLE_AMOUNT, 5.0, true) // Win -> reset base 10
        assertEquals(10.0, stake, 0.001)

        // Add amount progression (add 5)
        stake = 10.0
        stake = StakeCalculator.calculateNextStake(stake, 10.0, StakeProgressionMode.ADD_AMOUNT, 5.0, false) // 15
        assertEquals(15.0, stake, 0.001)
        stake = StakeCalculator.calculateNextStake(stake, 10.0, StakeProgressionMode.ADD_AMOUNT, 5.0, false) // 20
        assertEquals(20.0, stake, 0.001)
        stake = StakeCalculator.calculateNextStake(stake, 10.0, StakeProgressionMode.ADD_AMOUNT, 5.0, true) // Win -> reset base 10
        assertEquals(10.0, stake, 0.001)

        // Keep same progression
        stake = 10.0
        stake = StakeCalculator.calculateNextStake(stake, 10.0, StakeProgressionMode.DISABLED, 5.0, false) // 10
        assertEquals(10.0, stake, 0.001)
        stake = StakeCalculator.calculateNextStake(stake, 10.0, StakeProgressionMode.DISABLED, 5.0, false) // 10
        assertEquals(10.0, stake, 0.001)
    }
}
