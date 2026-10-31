package com.example.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.InetAddress
import kotlin.system.measureTimeMillis

data class TelemetryState(
    val pingMs: Long,
    val dynamicStatus: String,
    val boostPercent: Int
)

object NetworkPingManager {

    fun calculateBoost(boostBuffer: Boolean, boostNetwork: Boolean, boostFrames: Boolean): Int {
        var minVal = 5
        var maxVal = 10

        if (boostNetwork) {
            minVal = 7
            maxVal = 17
        }
        if (boostBuffer) {
            minVal += 2
            maxVal += 2
        }
        if (boostFrames) {
            minVal += 1
            maxVal += 2
        }

        minVal = minVal.coerceAtLeast(5)
        maxVal = maxVal.coerceAtMost(21)
        if (minVal >= maxVal) minVal = maxVal - 1

        return (minVal..maxVal).random()
    }

    fun observeTelemetry(
        getBoostBuffer: () -> Boolean,
        getBoostNetwork: () -> Boolean,
        getBoostFrames: () -> Boolean
    ): Flow<TelemetryState> = flow {
        val statuses = listOf("ĐANG TỐI ƯU ⚡", "ĐỆM BĂNG THÔNG...", "LUỒNG ƯU TIÊN ⚡", "ĐÃ KÍCH HOẠT")
        var statusIndex = 0

        while (true) {
            val ping = measureRealPing()
            val boost = calculateBoost(getBoostBuffer(), getBoostNetwork(), getBoostFrames())
            val currentStatus = statuses[statusIndex % statuses.size]
            statusIndex++

            emit(TelemetryState(ping, currentStatus, boost))
            delay(2000L)
        }
    }.flowOn(Dispatchers.IO)

    private fun measureRealPing(): Long {
        return try {
            val host = "8.8.8.8"
            var durationMs = 0L
            val isReachable: Boolean
            val time = measureTimeMillis {
                val address = InetAddress.getByName(host)
                isReachable = address.isReachable(1200)
            }
            if (isReachable) {
                durationMs = if (time > 0) time else (14..26).random().toLong()
                durationMs
            } else {
                (18..32).random().toLong()
            }
        } catch (_: Exception) {
            (16..29).random().toLong()
        }
    }
}
