package com.example.data.engine

import com.example.data.model.StakeProgressionMode

object StakeCalculator {

    fun calculateNextStake(
        currentStake: Double,
        baseStake: Double,
        afterLossMode: StakeProgressionMode,
        addAmount: Double,
        isWin: Boolean
    ): Double {
        if (isWin) {
            return baseStake.coerceAtLeast(1.0)
        }
        return when (afterLossMode) {
            StakeProgressionMode.DOUBLE_AMOUNT -> (currentStake * 2.0).coerceAtLeast(1.0)
            StakeProgressionMode.ADD_AMOUNT -> (currentStake + addAmount).coerceAtLeast(1.0)
            StakeProgressionMode.DISABLED -> baseStake.coerceAtLeast(1.0)
        }
    }
}
