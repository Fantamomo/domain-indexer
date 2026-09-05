package com.fantamomo.hc.dns.util

import com.fantamomo.hc.dns.data.Config
import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.*

val DISABLE_GITHUB_HELPER_PLUGIN = AttributeKey<Boolean>("disable-github-helper-plugin")

val GitHubHelperKtorPlugin = createClientPlugin("GitHubBearerAuthPlugin") {
    val token = Config.GITHUB_TOKEN

    onRequest { request, _ ->
        if (request.attributes.getOrNull(DISABLE_GITHUB_HELPER_PLUGIN) == true) {
            return@onRequest
        }
        if (token.isBlank()) return@onRequest
        if (request.url.host == "api.github.com") {
            request.header(
                HttpHeaders.Authorization,
                "Bearer $token"
            )
            request.header(
                HttpHeaders.ContentType,
                "application/vnd.github+json"
            )
            request.header(
                "X-GitHub-Api-Version",
                "2026-03-10"
            )
        }
    }
}