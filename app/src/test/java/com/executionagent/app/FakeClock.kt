package com.executionagent.app

import com.executionagent.app.domain.time.ClockProvider
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class FakeClock(private var instant: Instant, private val zone: ZoneId = ZoneId.of("Asia/Shanghai")) : ClockProvider {
    override fun now(): Instant = instant
    override fun zoneId(): ZoneId = zone
    fun advance(duration: Duration) { instant = instant.plus(duration) }
}
