package cn.yunyoujun.cook

import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.data.local.KitchenClockReading
import cn.yunyoujun.cook.data.local.RecipeTimerState
import cn.yunyoujun.cook.data.local.RecipeTimerState.Phase
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KitchenTimerStateTest {
    @Test
    fun olderSavedDurationsAndPartialPausesRemainUsable() {
        val configured = Json.decodeFromString<RecipeTimerState>("""{"durationSeconds":420,"pausedRemainingSeconds":420}""")
        val paused = Json.decodeFromString<RecipeTimerState>("""{"durationSeconds":420,"pausedRemainingSeconds":17}""")
        val finished = Json.decodeFromString<RecipeTimerState>("""{"durationSeconds":420,"pausedRemainingSeconds":0}""")

        val now = KitchenClockReading(0)
        assertEquals(Phase.READY, configured.phase(now))
        assertEquals(420, configured.remainingSeconds(now))
        assertEquals(Phase.PAUSED, paused.phase(now))
        assertEquals(17_100L, paused.start(KitchenClockReading(100), "青菜").deadlineEpochMs)
        assertEquals(Phase.FINISHED, finished.phase(now))
        assertEquals(420_000L, finished.start(now, "青菜").deadlineEpochMs)
    }

    @Test
    fun pausingAndResumingDoesNotRoundUpElapsedTime() {
        val originalGoal = DietaryGoal.WEIGHT_AWARE
        val newGoal = DietaryGoal.BALANCED_PAIRING
        val initial = RecipeTimerState(durationSeconds = 60).start(KitchenClockReading(2_000), "青菜", originalGoal)
        val paused = initial.pause(KitchenClockReading(2_125))
        assertEquals(60, paused.remainingSeconds(KitchenClockReading(5_000)))
        val resumed = paused.start(KitchenClockReading(10_000), "青菜", newGoal)
        assertEquals(originalGoal, resumed.dietaryGoal)
        assertEquals(69_875L, resumed.deadlineEpochMs)
        assertEquals(1, resumed.remainingSeconds(KitchenClockReading(69_874)))
        val end = KitchenClockReading(69_875)
        assertEquals(Phase.FINISHED, resumed.phase(end))
        val restarted = resumed.start(end, "青菜", newGoal)
        assertEquals(newGoal, restarted.dietaryGoal)
        assertNotEquals(resumed.alarmKey, restarted.alarmKey)
    }

    @Test
    fun countdownUsesElapsedTimeAndRebootRestoresAgainstTheRebasedDeadline() {
        val start = KitchenClockReading(4_000_000, 90_000, 3)
        val timer = RecipeTimerState(durationSeconds = 60).start(start, "青菜")
        val forward = KitchenClockReading(7_610_000, 100_000, 3)
        assertEquals(50, timer.remainingSeconds(forward))
        val backwards = KitchenClockReading(3_020_000, 110_000, 3)
        assertEquals(40, timer.remainingSeconds(backwards))

        val rebased = timer.restoreClock(backwards, wallClockChanged = true)
        assertEquals(timer.alarmKey, rebased.alarmKey)
        assertEquals(3_060_000L, rebased.deadlineEpochMs)
        val rebooted = KitchenClockReading(3_030_000, 1_000, 4)
        val restored = rebased.restoreClock(rebooted, wallClockChanged = false)
        assertEquals(timer.alarmKey, restored.alarmKey)
        assertEquals(31_000L, restored.elapsedDeadline(rebooted))
        assertEquals(30, restored.remainingSeconds(rebooted))
        assertEquals(Phase.FINISHED, restored.phase(rebooted.copy(elapsedMs = 31_000)))
    }
}
