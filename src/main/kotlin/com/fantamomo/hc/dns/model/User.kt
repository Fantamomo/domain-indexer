package com.fantamomo.hc.dns.model

class User(
    val id: Long,
    val username: String,
    val email: String?,
    val type: String,
    val slackId: String?,
)