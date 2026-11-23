package com.liftlog.app

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDataTest {
    private val date = LocalDate.parse("2026-11-23") // Monday
    private val monday = date.toString()
    private val bench = ExercisePlan("卧推", 60.0, 2, 10, 90)

    @Test
    fun planResolvesSessionBeforeOverrideBeforeWeeklySchedule() {
        val row = listOf(ExercisePlan("划船", 25.0, 1, 6, 30))
        val data = AppData(weekly = mapOf(1 to row))
        assertEquals(row, data.plan(date))
        val withOverride = data.copy(overrides = mapOf(monday to emptyList()))
        assertTrue(withOverride.plan(date).isEmpty())
        val withSession = withOverride.copy(days = mapOf(monday to DayLog(monday, listOf(bench), emptyList(), false)))
        assertEquals(listOf(bench), withSession.plan(date))
        assertEquals(row, withSession.plan(date.plusWeeks(1)))
    }

    @Test
    fun checkInCountsRecordedGroupsNotPlannedRepsAndChangesOnUndo() {
        val data = AppData(weekly = mapOf(1 to listOf(bench)))
        assertEquals(DayStatus.PLANNED, data.status(date))
        assertEquals(DayStatus.REST, data.status(date.plusDays(1)))
        val first = SetLog(monday, 0, 0, 60.0, 8)
        val second = SetLog(monday, 0, 1, 60.0, 7)

        fun status(vararg sets: SetLog) = data.copy(
            days = mapOf(monday to DayLog(monday, listOf(bench), sets.toList(), ended = true))
        ).status(date)

        assertEquals(DayStatus.PARTIAL, status(first))
        assertEquals(DayStatus.COMPLETE, status(first, second))
        assertEquals(DayStatus.PARTIAL, status(second))
        assertEquals(DayStatus.PLANNED, status())
        assertEquals(DayStatus.PARTIAL, status(first, second.copy(setIndex = 2)))
    }

    @Test
    fun bestWeightUsesActualSetsAndTrainingDaysAreDistinct() {
        val nextWeek = date.plusWeeks(1).toString()
        val otherMonth = "2026-12-01"
        val data = AppData(days = mapOf(
            monday to DayLog(monday, listOf(bench, bench), listOf(
                SetLog(monday, 0, 0, 25.0, 9),
                SetLog(monday, 0, 1, 52.5, 6),
                SetLog(monday, 1, 0, 56.0, 4),
            ), true),
            nextWeek to DayLog(nextWeek, listOf(bench), listOf(SetLog(nextWeek, 0, 0, 54.0, 5)), true),
            otherMonth to DayLog(otherMonth, listOf(bench), listOf(SetLog(otherMonth, 0, 0, 58.0, 8)), true),
        ))
        assertEquals(listOf(ExerciseStats("卧推", 2, 56.0, 4, monday)), data.stats(YearMonth.of(2026, 11)))
        assertEquals(listOf(ExerciseStats("卧推", 3, 58.0, 8, otherMonth)), data.stats())
    }

    @Test
    fun bodyweightCountsAndInvalidRowsDoNot() {
        val data = AppData(days = mapOf(monday to DayLog(monday, listOf(bench), listOf(
            SetLog(monday, 0, 0, 0.0, 1),
            SetLog(monday, 0, 1, 100.0, 0),
            SetLog(monday, 0, 2, Double.NaN, 5),
            SetLog(monday, 1, 0, 500.0, 10),
        ), false)))
        assertEquals(DayStatus.PARTIAL, data.status(date))
        assertEquals(listOf(ExerciseStats("卧推", 1, 0.0, 1, monday)), data.stats())
    }
}
