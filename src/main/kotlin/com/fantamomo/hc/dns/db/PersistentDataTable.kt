package com.fantamomo.hc.dns.db

import com.fantamomo.hc.dns.manager.DatabaseManager
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.upsert

object PersistentDataTable : Table("persistent_data") {
    val name = varchar("name", 255)
    val value = varchar("value", 255)

    suspend fun getValue(name: String): String? {
        return DatabaseManager.transaction {
            select(value)
                .where { PersistentDataTable.name eq name }
                .singleOrNull()
                ?.get(value)
        }
    }

    suspend fun setValue(name: String, value: String) {
        require(name.isNotBlank()) { "Name cannot be blank" }
        require(name.length <= 255) { "Name cannot exceed 255 characters" }
        require(value.length <= 255) { "Value cannot exceed 255 characters" }
        DatabaseManager.transaction {
            upsert(PersistentDataTable.name) {
                it[PersistentDataTable.name] = name
                it[PersistentDataTable.value] = value
            }
        }
    }

    suspend fun removeValue(name: String): Boolean {
        return DatabaseManager.transaction {
            deleteWhere { PersistentDataTable.name eq name }
        } != 0
    }

    override val primaryKey = PrimaryKey(name)
}