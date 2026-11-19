package com.runway.assistant

import com.runway.assistant.replay.JoinPlanner
import com.runway.assistant.replay.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinPlannerTest {

    private val points = listOf(
        TrackPoint(0, 30.000000, 120.000000),
        TrackPoint(1_000, 30.000025, 120.000000), // 约 2.78 m
        TrackPoint(2_000, 30.000050, 120.000000),
        TrackPoint(3_000, 30.000075, 120.000000),
    )

    @Test
    fun haversine_one_hundredth_degree_latitude_is_about_1111m() {
        val d = JoinPlanner.haversine(30.0, 120.0, 30.01, 120.0)
        assertEquals(1111.0, d, 5.0)
    }

    @Test
    fun remaining_meters_from_start_counts_all_segments() {
        val d = JoinPlanner.remainingMetersOfPoints(points, 0)
        assertEquals(8.34, d, 0.2)
    }

    @Test
    fun remaining_meters_from_middle_skips_earlier_segments() {
        val d = JoinPlanner.remainingMetersOfPoints(points, 2_000)
        assertEquals(2.78, d, 0.2)
    }

    @Test
    fun required_meters_adds_margin() {
        assertEquals(3300.0, JoinPlanner.requiredMeters(3000.0), 0.001)
    }

    @Test
    fun short_track_fails_join_plan() {
        val result = JoinPlanner.plan(
            events = emptyList(),
            requiredM = 3000.0,
            requestedMs = 0,
        )
        // 空素材没有定位点，剩余里程为 0，必然失败
        assertTrue(result.isFailure)
    }
}
