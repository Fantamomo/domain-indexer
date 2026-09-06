package com.fantamomo.hc.dns.db

import com.fantamomo.hc.dns.manager.DatabaseManager
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert

object SlackListColumnTable : Table("slack_list_columns") {
    val key = varchar("key", 64)
    val columnId = varchar("column_id", 64)

    override val primaryKey = PrimaryKey(key)

    suspend fun getColumnId(key: String): String? {
        return DatabaseManager.transaction {
            select(columnId)
                .where { SlackListColumnTable.key eq key }
                .singleOrNull()
                ?.get(columnId)
        }
    }

    suspend fun getAllColumnIds(): Map<String, String> {
        return DatabaseManager.transaction {
            selectAll()
                .map { it[key] to it[columnId] }
                .toList()
                .toMap()
        }
    }

    suspend fun saveColumnIds(columns: Map<String, String>) {
        if (columns.isEmpty()) return
        DatabaseManager.transaction {
            for ((k, colId) in columns) {
                upsert {
                    it[key] = k
                    it[columnId] = colId
                }
            }
        }
    }
}
