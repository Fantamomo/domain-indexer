package com.fantamomo.hc.dns.db

import com.fantamomo.hc.dns.model.SlackUserIdFoundState
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

object UserTable : Table("users") {
    val id = long("id")

    val username = varchar("username", 255)

    val email = varchar("email", 255)
        .nullable()

    val type = varchar("type", 255)

    val slackId = varchar("slack_id", 20)
        .nullable()

    val slackIdState = enumerationByName<SlackUserIdFoundState>("slack_id_state", 15)
        .default(SlackUserIdFoundState.UNKNOWN)

    val slackIdStateLastRequested = timestamp("slack_id_state_last_requested")
        .nullable()

    override val primaryKey = PrimaryKey(id)
}