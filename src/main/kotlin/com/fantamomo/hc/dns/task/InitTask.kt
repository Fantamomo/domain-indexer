package com.fantamomo.hc.dns.task

import com.fantamomo.hc.dns.util.humanReadable
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.measureTimedValue

abstract class InitTask(
    val name: String,
    vararg dependOn: InitTask,
    val shortDescription: String = "",
    val longDescription: String = shortDescription
) {
    protected val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val dependencies = dependOn.toList()

    private val deferred = CompletableDeferred<Result<Unit>>()

    private val taskState = AtomicReference(State.NOT_STARTED)

    suspend fun await(): Result<Unit> = deferred.await()

    val state: State
        get() = taskState.load()

    /**
     * Should we log messages from this task?
     * If false, the task will not log messages; the implementation should log messages itself.
     */
    protected open fun disableLogAndStateSetting(): Boolean = true

    suspend fun execute() {
        if (!taskState.compareAndSet(expectedValue = State.NOT_STARTED, newValue = State.AWAITING_DEPENDENCIES)) {
            throw IllegalStateException("Task has already been started.")
        }

        val dependencyResult = awaitDependencies()

        if (dependencyResult.isFailure) {
            deferred.complete(dependencyResult)
            taskState.store(State.FAILED)
            return
        }

        if (disableLogAndStateSetting()) logger.info("Executing task: $name")
        taskState.store(State.RUNNING)
        val result = measureTimedValue {
            runCatching {
                run()
            }
        }

        deferred.complete(result.value)

        if (state != State.RUNNING && !disableLogAndStateSetting()) return

        if (result.value.isFailure) {
            if (taskState.compareAndSet(State.RUNNING, State.FAILED)) {
                if (disableLogAndStateSetting()) return
                logger.error(
                    "Task $name failed with exception after ${result.duration.humanReadable()}",
                    result.value.exceptionOrNull()
                )
            }
        } else {
            if (taskState.compareAndSet(State.RUNNING, State.COMPLETED)) {
                if (disableLogAndStateSetting()) return
                logger.info("Task $name completed successfully in ${result.duration.humanReadable()}")
            }
        }
    }

    private suspend fun awaitDependencies(): Result<Unit> {
        for (dependency in dependencies) {
            val result = dependency.await()

            if (result.isFailure) return result
        }
        return Result.success(Unit)
    }

    protected fun markFailed() {
        taskState.compareAndExchange(expectedValue = State.RUNNING, newValue = State.FAILED)
    }

    protected abstract suspend fun run()

    enum class State {
        NOT_STARTED,
        AWAITING_DEPENDENCIES,
        RUNNING,
        COMPLETED,
        FAILED
    }
}