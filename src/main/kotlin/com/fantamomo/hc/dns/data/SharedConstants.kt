package com.fantamomo.hc.dns.data

import com.fantamomo.hc.dns.util.GitHubHelperKtorPlugin
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.char
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

    val client by lazy {
        // we are doing it lazy because GitHubHelperKtorPlugin throws an exception if the Config hasnt been loaded,
        // there is currently only one way to init SharedConstants before Config is loaded, which is by using: ./gradle generateMigrations
        HttpClient {
            install(GitHubHelperKtorPlugin)
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    // that is the internal github id of the hackclub/dns repo
    const val HACKCLUB_DNS_ID = 123017957L

    val localDateTimeFormat = LocalDateTime.Format {
        hour()
        char(':')
        minute()
        char(':')
        second()
    }
}