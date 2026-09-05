package com.fantamomo.hc.dns.model

enum class SiteProblemSeverity(val slackOptionName: String, val slackOptionId: String, val slackColor: String) {
    CRITICAL("Critical!", "critical", "flamingo"),
    HIGH("High", "high", "horchata"),
    MEDIUM("Medium", "medium", "honeycomb"),
    LOW("Low", "low", "grass"),
    INFO("Info", "info", "indigo"),
}