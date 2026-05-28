package com.fantamomo.hc.dns.util

import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.DurationUnit


private val UNITS = listOf(
    DurationUnit.DAYS to 86_400_000L,
    DurationUnit.HOURS to 3_600_000L,
    DurationUnit.MINUTES to 60_000L,
    DurationUnit.SECONDS to 1_000L,
    DurationUnit.MILLISECONDS to 1L
)

val DurationUnit.symbol: String
    get() = when (this) {
        DurationUnit.DAYS -> "d"
        DurationUnit.HOURS -> "h"
        DurationUnit.MINUTES -> "m"
        DurationUnit.SECONDS -> "s"
        DurationUnit.MILLISECONDS -> "ms"
        DurationUnit.NANOSECONDS -> "ns"
        DurationUnit.MICROSECONDS -> "us"
    }

fun Duration.humanReadable(
    minSoft: DurationUnit? = null,
    minHard: DurationUnit? = null
): String {
    if (this == Duration.ZERO) return "0ms"

    val softIndex = minSoft?.let { u -> UNITS.indexOfFirst { it.first == u } } ?: UNITS.lastIndex
    val hardIndex = minHard?.let { u -> UNITS.indexOfFirst { it.first == u } } ?: UNITS.lastIndex

    val total = abs(this.inWholeMilliseconds)

    var remaining = total
    val result = mutableListOf<String>()

    for (i in UNITS.indices) {
        val (unit, size) = UNITS[i]

        if (i > softIndex) continue

        if (i >= hardIndex) {
            val value = remaining / size
            if (value > 0) result.add("${value}${unit.symbol}")
            remaining = 0
            break
        }

        val value = remaining / size
        if (value > 0) {
            result.add("${value}${unit.symbol}")
            remaining -= value * size
        }
    }

    if (result.isEmpty()) {
        val (u, _) = UNITS[softIndex]
        return "0${u.symbol}"
    }

    return result.joinToString(" ")
}