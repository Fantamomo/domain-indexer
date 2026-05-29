package com.fantamomo.hc.dns.data

import com.fantamomo.hc.dns.util.GitHubHelperKtorPlugin
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

object SharedConstants {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    val jsonSQL = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    val client = HttpClient {
        install(GitHubHelperKtorPlugin)
        install(ContentNegotiation) {
            json(json)
        }
    }
}