package com.fantamomo.hc.dns.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

// this database table is a special table in this project, changing it will require a manual database migration
object MigrationTable : Table("db_migrations") {
    val migration = varchar("version", 255) // the full name of the migration file
    val appliedAt = timestamp("applied_at") // the time the migration was applied
}