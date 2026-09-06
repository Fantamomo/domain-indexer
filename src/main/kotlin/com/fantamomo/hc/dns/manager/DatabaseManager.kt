package com.fantamomo.hc.dns.manager

import com.fantamomo.hc.dns.data.Config
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

object DatabaseManager {
    private var db: R2dbcDatabase? = null
    private val semaphore = Semaphore(16)

    suspend fun connect() {
        if (db != null) return
        Class.forName("org.postgresql.Driver")
        db = R2dbcDatabase.connect(
            url = Config.POSTGRES_URL,
            driver = "postgresql",
            user = Config.POSTGRES_USER,
            password = Config.POSTGRES_PASSWORD,
        )
    }

    suspend fun <T> transaction(block: suspend R2dbcTransaction.() -> T): T {
        val database = db ?: error("Database not connected")
        semaphore.withPermit {
            return suspendTransaction(db = database, statement = block)
        }
    }
}