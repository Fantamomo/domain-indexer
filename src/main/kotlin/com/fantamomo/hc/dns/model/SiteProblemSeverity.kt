package com.fantamomo.hc.dns.model

enum class SiteProblemSeverity(val slackOptionName: String, val slackOptionId: String, val slackColor: String) {
    CRITICAL("Critical!", "critical", "red"),
    HIGH("High", "high", "orange"),
    MEDIUM("Medium", "medium", "yellow"),
    LOW("Low", "low", "green"),
    INFO("Info", "info", "blue"),
}