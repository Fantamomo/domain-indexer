package com.fantamomo.hc.dns.data

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.util.*
import kotlin.io.path.*
import kotlin.properties.ReadOnlyProperty

@Suppress("SameParameterValue")
object Config {
    private val logger = LoggerFactory.getLogger(Config::class.java)

    private val configPath: Path =
        Path(System.getProperty("di-config-path") ?: "config.properties")

    private val properties = Properties()

    private val entries = mutableListOf<ConfigEntry<*>>()

    val PORT by int(
        key = "server.port",
        default = 29001,
        description = "The port the HTTP server should listen on."
    )

    val HOST by string(
        key = "server.host",
        default = "0.0.0.0",
        description = "The host/interface the server should bind to."
    )

    val MESSAGE_HOST by string(
        key = "message.host",
        default = "localhost",
        description = "The host we should use in messages, so that the links work"
    )

    val REPO_DIR: Path by path(
        key = "repo.dir",
        default = Path("./dns"),
        description = "Directory where the git repository will be cloned and stored."
    )

    val POSTGRES_URL: String by string(
        key = "postgres.url",
        default = "r2dbc:postgresql://localhost:5432/postgres",
        description = "The JDBC URL of the Postgres database.",
        canBeSetByEnv = true
    )

    val POSTGRES_USER: String by string(
        key = "postgres.user",
        default = "postgres",
        description = "The username for the Postgres database.",
        canBeSetByEnv = true
    )

    val POSTGRES_PASSWORD: String by string(
        key = "postgres.password",
        default = "postgres",
        description = "The password for the Postgres database.",
        canBeSetByEnv = true
    )

    val GITHUB_TOKEN: String by string(
        key = "github.token",
        default = "",
        description = "The GitHub token used to authenticate with the GitHub API. Used for higher rate limits.",
        canBeSetByEnv = true
    )

    val SLACK_WEB_HOOK_URL: String by string(
        key = "slack.webhook.url",
        default = "",
        description = "The Slack webhook URL used to send notifications.",
        canBeSetByEnv = true
    )

    val SLACK_USER_OAUTH_TOKEN: String by string(
        key = "slack.user.oauth.token",
        default = "",
        description = "The Slack user OAuth token used to map users by there github account to there slack id",
        canBeSetByEnv = true
    )

    val SLACK_BOT_TOKEN: String by string(
        key = "slack.bot.token",
        default = "",
        description = "The Slack bot token used to request user profiles.",
        canBeSetByEnv = true
    )

    val IDS_TO_IGNORE: String by string(
        key = "ids.to.ignore",
        default = "",
        description = "A comma-separated list of github ids to ignore"
    )

    fun init() {
        if (properties.isNotEmpty()) return
        ensureConfigExists()
        loadProperties()
        validateAndLoad()
    }

    private fun string(
        key: String,
        default: String? = null,
        description: String,
        canBeSetByEnv: Boolean = false
    ) = register(key, default, description, canBeSetByEnv) { it }

    private fun int(
        key: String,
        default: Int? = null,
        description: String,
        canBeSetByEnv: Boolean = false
    ) = register(key, default, description, canBeSetByEnv) { value ->
        value.toIntOrNull()
            ?: throw IllegalArgumentException("Expected a valid integer.")
    }

    private fun boolean(
        key: String,
        default: Boolean? = null,
        description: String,
        canBeSetByEnv: Boolean = true
    ) = register(key, default, description, canBeSetByEnv) { value ->
        when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Expected 'true' or 'false'.")
        }
    }

    private fun path(
        key: String,
        default: Path? = null,
        description: String,
        canBeUsed: Boolean = false,
    ) = register(key, default, description, canBeUsed) { value ->
        Path(value).toAbsolutePath()
    }

    private fun <T> register(
        key: String,
        default: T?,
        description: String,
        canBeSetByEnv: Boolean,
        parser: (String) -> T
    ): ReadOnlyProperty<Any?, T> {

        val entry = ConfigEntry(
            key = key,
            default = default,
            description = description,
            parser = parser,
            canBeSetByEnv = canBeSetByEnv
        )

        entries += entry

        return ReadOnlyProperty { _, property ->
            entry.value
                ?: throw IllegalStateException(
                    "Config '${property.name}' was accessed before initialization."
                )
        }
    }

    private fun validateAndLoad() {

        val errors = mutableListOf<String>()

        for (entry in entries) {

            @Suppress("UNCHECKED_CAST")
            entry as ConfigEntry<Any>


            if (entry.canBeSetByEnv) {
                val envKey = entry.key.uppercase().replace('.', '_')
                val envValue = System.getenv(envKey)
                if (envValue != null) {
                    try {
                        entry.setValue(entry.parser(envValue.trim()))
                        logger.info(
                            "Config '{}' is set via environment variable '{}'.",
                            entry.key,
                            envKey
                        )
                        continue
                    } catch (ex: Exception) {
                        logger.warn(
                            "Failed to parse environment variable '{}'. Value: '{}'. Reason: {}",
                            envKey,
                            envValue,
                            ex.message
                        )
                    }
                }
            }

            val rawValue = properties.getProperty(entry.key)?.replace("\r", "\\")

            if (rawValue == null) {

                if (entry.default != null) {

                    logger.warn(
                        "Missing config '{}'. Using default '{}'. Description: {}",
                        entry.key,
                        entry.default,
                        entry.description
                    )

                    entry.setValue(entry.default)
                    continue
                }

                errors += buildString {
                    appendLine("Missing required configuration.")
                    appendLine("Key: ${entry.key}")
                    appendLine("Description: ${entry.description}")
                }

                continue
            }

            try {
                val parsed = entry.parser(rawValue.trim())
                entry.setValue(parsed)

            } catch (ex: Exception) {

                errors += buildString {
                    appendLine("Invalid configuration value.")
                    appendLine("Key: ${entry.key}")
                    appendLine("Value: $rawValue")
                    appendLine("Description: ${entry.description}")
                    appendLine("Reason: ${ex.message}")
                }
            }
        }

        if (errors.isNotEmpty()) {
            val message = buildString {
                appendLine("Failed to load configuration.")
                appendLine("Config file: ${configPath.absolutePathString()}")
                appendLine()
                errors.forEach {
                    appendLine(it)
                }
            }
            throw IllegalStateException(message)
        }

        logger.info("Successfully loaded {} config values.", entries.size)
    }

    private fun ensureConfigExists() {

        if (configPath.exists()) {
            return
        }

        logger.warn(
            "Config file does not exist. Creating default config at '{}'.",
            configPath.absolutePathString()
        )

        configPath.parent?.createDirectories()

        val defaultConfig = buildString {

            appendLine("# MapGit Configuration")
            appendLine()

            for (entry in entries) {

                appendLine("# ${entry.description}")

                if (entry.default != null) {
                    appendLine("${entry.key}=${entry.default}")
                } else {
                    appendLine("# ${entry.key}=")
                }

                appendLine()
            }
        }

        try {

            configPath.outputStream().bufferedWriter().use {
                it.write(defaultConfig)
            }

            logger.info(
                "Created default config at '{}'.",
                configPath.absolutePathString()
            )

        } catch (ex: IOException) {

            throw IllegalStateException(
                "Failed to create config file at '${configPath.absolutePathString()}'.",
                ex
            )
        }
    }

    private fun loadProperties() {
        try {
            configPath.inputStream().use(properties::load)

            logger.info(
                "Loaded config from '{}'.",
                configPath.absolutePathString()
            )
        } catch (ex: IOException) {
            throw IllegalStateException(
                "Failed to load config file '${configPath.absolutePathString()}'.",
                ex
            )
        }
    }

    private class ConfigEntry<T>(
        val key: String,
        val default: T?,
        val description: String,
        val parser: (String) -> T,
        val canBeSetByEnv: Boolean = false
    ) {
        private var internalValue: T? = null

        val value: T?
            get() = internalValue

        fun setValue(value: T) {
            internalValue = value
        }
    }
}