package com.fantamomo.hc.dns

import com.fantamomo.hc.dns.data.Config
import com.fantamomo.hc.dns.net.rootModule
import com.fantamomo.hc.dns.task.InitTask
import com.fantamomo.hc.dns.task.init.*
import com.fantamomo.hc.dns.util.humanReadable
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.measureTime

object App {
    private val logger = LoggerFactory.getLogger(App::class.java)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

    val initTasks = listOf(
        ConnectDatabaseTask,
        GetForksInitTask,
        InitDatabaseTablesTask,
        InitForksTask,
        InitRepoTask,
        UpdateUsersTask
    )

    private val running = AtomicBoolean(false)

    suspend fun main() {
        if (!running.compareAndSet(
                expectedValue = false,
                newValue = true
            )
        ) throw IllegalStateException("Application is already running")
        try {
            Config.init()
        } catch (e: Exception) {
            logger.error("Failed to initialize config", e)
            return
        }
        val job = scope.launch { // switching to the application scope
            start()
        }
        job.join()
    }

    private suspend fun start() = coroutineScope {
        launch { startInitTask() }
        launch { startServer() }
    }

    private suspend fun startServer(): Unit = coroutineScope {
        logger.info("Creating server on ${Config.HOST}:${Config.PORT}")
        server = embeddedServer(
            Netty,
            host = Config.HOST,
            port = Config.PORT,
            module = Application::rootModule
        )
        logger.info("Starting server")
        server.startSuspend(wait = true)
        logger.info("Server has been stopped")
    }

    private suspend fun startInitTask() {
        logger.info("Starting init tasks")
        val duration = measureTime {
            coroutineScope {
                for (task in initTasks) {
                    launch {
                        task.execute()
                        if (task.state != InitTask.State.COMPLETED) {
                            logger.error("Init task failed: ${task.name}, ${task.state}")
                            this@coroutineScope.cancel("Init task failed: ${task.name}")
                        }
                    }
                }
            }
        }
        logger.info("Init tasks completed in ${duration.humanReadable()}")
    }
}