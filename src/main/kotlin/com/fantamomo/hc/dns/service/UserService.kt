package com.fantamomo.hc.dns.service

import com.fantamomo.hc.dns.db.UserTable
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.model.User
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.selectAll

object UserService {
    suspend fun getAllUsers(): List<User> = DatabaseManager.transaction {
        UserTable.selectAll()
            .map {
                User(
                    id = it[UserTable.id],
                    username = it[UserTable.username],
                    email = it[UserTable.email],
                    type = it[UserTable.type],
                    slackId = it[UserTable.slackId],
                )
            }.toList()
    }

    suspend fun updateUsers(users: List<User>) {
        val usersMap = users.associateBy { it.id }
        val dbUsers = getAllUsers()
        val dbUsersMap = dbUsers.associateBy { it.id }

        val toInsert = users.filter { user -> user.id !in dbUsersMap }
        val toUpdate = users.filter { user -> dbUsersMap[user.id]?.let { user != it } ?: false }
        val toDelete = dbUsers.filter { user -> user.id !in usersMap }

        DatabaseManager.transaction {
            UserTable.batchUpsert(toInsert + toUpdate, shouldReturnGeneratedValues = false) {
                val dbUser = dbUsersMap[it.id]
                this[UserTable.id] = it.id
                this[UserTable.username] = it.username
                this[UserTable.email] = it.email ?: dbUser?.email
                this[UserTable.type] = it.type
                this[UserTable.slackId] = it.slackId ?: dbUser?.slackId
            }
            UserTable.batchUpsert(toDelete, shouldReturnGeneratedValues = false) {
                this[UserTable.id] = it.id
                this[UserTable.username] = it.username
                this[UserTable.email] = it.email
                this[UserTable.type] = it.type
                this[UserTable.slackId] = it.slackId
            }
        }
    }
}