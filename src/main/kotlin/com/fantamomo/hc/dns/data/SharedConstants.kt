package com.fantamomo.hc.dns.data

import com.fantamomo.hc.dns.util.GitHubHelperKtorPlugin
import com.fantamomo.hc.dns.util.WorkSynchronizer
import com.fantamomo.hc.dns.util.net.HackyHostnameVerifier
import com.fantamomo.hc.dns.util.net.SniSocketFactory
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.char
import kotlinx.serialization.json.Json
import okhttp3.internal.platform.Platform

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
        HttpClient(OkHttp) {
            install(GitHubHelperKtorPlugin)
            install(ContentNegotiation) {
                json(json)
            }
            install(ContentEncoding) {
                gzip()
                deflate()
                identity()

                mode = ContentEncodingConfig.Mode.DecompressResponse
            }
        }
    }

    val sniSocketFactory: SniSocketFactory by lazy {
        val defaultTrustManager = Platform.get().platformTrustManager()
        val backendSSLSocketFactory = Platform.get().newSslSocketFactory(defaultTrustManager)
        SniSocketFactory(backendSSLSocketFactory, defaultTrustManager)
    }

    val proxyClient by lazy {
        HttpClient(OkHttp) {
            followRedirects = false

            engine {
                config {
                    sslSocketFactory(sniSocketFactory, sniSocketFactory.trustManager)
                    hostnameVerifier(HackyHostnameVerifier(sniSocketFactory))
                }
            }

            install(ContentEncoding) {
                gzip()
                deflate()
                identity()

                mode = ContentEncodingConfig.Mode.DecompressResponse
            }
        }
    }

    // this work synchronizer is used to prevent the SiteChecker from checking sites while the Scheduler is currently checking for dns changes
    // the reason behind this is that the scheduler is more important than the site checker (also the site checker is slower)
    // in SiteChecker you will find this line: SharedConstants.workSynchronizer.waitUntilUnlocked()
    // at those places we will wait for the scheduler to finish its work (if it is currently working)
    //
    val workSynchronizer = WorkSynchronizer()

    // that is the internal github id of the hackclub/dns repo
    const val HACKCLUB_DNS_ID = 123017957L

    const val WEB_FLOW_GITHUB_ID = 19864447L

    const val SLACK_GITHUB_PROFILE_ID = "Xf09V176UVK5"

    val localDateTimeFormat = LocalDateTime.Format {
        hour()
        char(':')
        minute()
        char(':')
        second()
    }
}