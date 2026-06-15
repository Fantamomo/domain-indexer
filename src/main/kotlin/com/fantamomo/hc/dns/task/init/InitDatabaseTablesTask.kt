package com.fantamomo.hc.dns.task.init

import com.fantamomo.hc.dns.db.MigrationTable
import com.fantamomo.hc.dns.manager.DatabaseManager
import com.fantamomo.hc.dns.task.InitTask
import com.fantamomo.hc.dns.util.humanReadable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import java.io.File
import java.net.URLDecoder
import java.util.jar.JarFile
import kotlin.time.Clock
import kotlin.time.measureTime

object InitDatabaseTablesTask : InitTask(
    "init-database-tables",
    ConnectDatabaseTask,
    shortDescription = "Initialize database tables",
    longDescription = "Creates the necessary database tables if they do not already exist"
) {
    private const val MIGRATION_FOLDER_PATH = "db/migration"
    private const val CREATE_TABLE_DB_MIGRATIONS_FILE_NAME = "V20260609194802__CREATE_TABLE_DB_MIGRATIONS.sql"

    override fun disableLogAndStateSetting() = false

    override suspend fun run() {
        logger.info("Initializing database tables")
        try {
            DatabaseManager.transaction {
                SchemaUtils.create(
                    MigrationTable
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize the migration table", e)
            markFailed()
            return
        }

        val migrationFileNames = try {
            loadAllMigrationFileNames()
        } catch (e: Exception) {
            logger.error("Failed to load the migrations files", e)
            markFailed()
            return
        }

        if (migrationFileNames.isEmpty()) {
            logger.warn("No migration files found")
            logger.warn("Are you sure you have the right run/application/jar")
            return
        }

        val alreadyAppliedMigrations = try {
            DatabaseManager.transaction {
                MigrationTable.select(MigrationTable.migration)
                    .map { it[MigrationTable.migration] }
                    .toList()
            }
        } catch (e: Exception) {
            logger.error("Failed to load the already applied migrations", e)
            markFailed()
            return
        }

        if (CREATE_TABLE_DB_MIGRATIONS_FILE_NAME !in alreadyAppliedMigrations) {
            DatabaseManager.transaction {
                MigrationTable.insert {
                    it[MigrationTable.migration] = CREATE_TABLE_DB_MIGRATIONS_FILE_NAME
                    it[MigrationTable.appliedAt] = Clock.System.now()
                }
            }
        }

        val migrationsToApply = migrationFileNames.filter { it != CREATE_TABLE_DB_MIGRATIONS_FILE_NAME && it !in alreadyAppliedMigrations }
        if (migrationsToApply.isEmpty()) {
            return
        }

        logger.info("Found ${migrationsToApply.size} migrations to apply")

        val migrationsMap = loadMigrations(migrationsToApply)

        val missingMigrations = migrationsToApply - migrationsMap.keys

        if (missingMigrations.isNotEmpty()) {
            logger.error("There are missing migrations: ${missingMigrations.joinToString(", ")}")
            logger.error("Please check the migrations folder")
            markFailed()
            return
        }

        val migrations = migrationsMap.entries.sortedBy { it.key }

        logger.info("Migrations to apply (${migrations.size}):")
        for ((migration, _) in migrations) {
            logger.info("- $migration")
        }

        for ((migration, sql) in migrations) {
            try {
                logger.info("Applying migration $migration")
                val duration = measureTime {
                    DatabaseManager.transaction {
                        exec(sql)
                        MigrationTable.insert {
                            it[MigrationTable.migration] = migration
                            it[MigrationTable.appliedAt] = Clock.System.now()
                        }
                    }
                }
                logger.info("Applied migration $migration in ${duration.humanReadable()}")
            } catch (e: Exception) {
                logger.error("Failed to apply migration $migration", e)
                logger.error("The database may be in an inconsistent state")
                logger.error("Please manually check the database and fix any issues")
                markFailed()
                return
            }
        }

        logger.info("Successfully applied ${migrations.size} migrations")
    }

    private fun loadMigrations(files: List<String>): Map<String, String> {
        val classLoader = javaClass.classLoader

        val result = mutableMapOf<String, String>()

        for (file in files) {
            try {
                val inputStream = classLoader.getResourceAsStream("$MIGRATION_FOLDER_PATH/$file")
                if (inputStream != null) {
                    inputStream.bufferedReader().use { reader ->
                        result[file] = reader.readText()
                    }
                } else {
                    logger.error("Failed to load migration file $file; resource not found")
                }
            } catch (e: Exception) {
                logger.error("Failed to load migration file $file", e)
            }
        }

        return result
    }

    private fun loadAllMigrationFileNames(): List<String> {
        val results = mutableListOf<String>()
        val resources = javaClass.classLoader.getResources(MIGRATION_FOLDER_PATH)

        while (resources.hasMoreElements()) {
            val url = resources.nextElement()

            if (url.protocol == "jar") {
                // we are in a jar file
                // so we need to open it and read the files
                val path = url.path.removePrefix("file:").substringBefore("!")
                JarFile(URLDecoder.decode(path, "UTF-8")).use { jar ->
                    jar.entries().asSequence()
                        .filter {
                            it.name.startsWith(MIGRATION_FOLDER_PATH) &&
                                    !it.isDirectory &&
                                    it.name.endsWith(".sql")
                        }
                        .map { it.name.removePrefix("$MIGRATION_FOLDER_PATH/") }
                        .forEach { results += it }
                }
            } else if (url.protocol == "file") {
                // we are running directly from classpath
                // eg. pressing the run button in IntelliJ
                val path = url.path.removePrefix("file:")
                val file = File(path)
                if (file.isDirectory) {
                    file.listFiles()?.forEach {
                        if (it.isFile && it.name.endsWith(".sql")) {
                            results += it.name.removePrefix("$MIGRATION_FOLDER_PATH/")
                        }
                    }
                }
            } else {
                logger.warn("Unsupported protocol: ${url.protocol} in $url")
            }
        }

        return results
    }
}