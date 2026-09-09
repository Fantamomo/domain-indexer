package com.fantamomo.hc.dns.util

import kotlinx.io.IOException
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.internal.delimiterOffset
import okhttp3.internal.http.toHttpDateString
import okhttp3.internal.platform.Platform.Companion.WARN
import okhttp3.internal.platform.Platform.Companion.get
import okhttp3.internal.trimSubstring
import java.net.CookieHandler
import java.util.*

// JavaNetCookieJar is based on the implementation from:
// https://github.com/lysine-dev/okhttp/blob/main/okhttp-java-net-cookiejar/src/main/kotlin/okhttp3/java/net/cookiejar/JavaNetCookieJar.kt
//
// The original implementation is licensed under the Apache License 2.0.
//
// The class is copied and adapted here to support the OkHttp version used by this project.
// JavaNetCookieJar is only available as part of OkHttp 5.0.0 and later. Adding the
// official dependency would therefore also introduce OkHttp 5.0.0 as a dependency.
//
// To avoid upgrading OkHttp, the implementation is included here and adapted to the
// currently used version.
class JavaNetCookieJar(
    var cookieHandler: CookieHandler?,
) : CookieJar {
    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        val cookieHandler = cookieHandler ?: return
        val cookieStrings = mutableListOf<String>()
        for (cookie in cookies) {
            // See the extension function at the bottom of this file.
            cookieStrings.add(cookie.toString(true))
        }
        val multimap = mapOf("Set-Cookie" to cookieStrings)
        try {
            cookieHandler.put(url.toUri(), multimap)
        } catch (e: IOException) {
            get().log("Saving cookies failed for " + url.resolve("/...")!!, WARN, e)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookieHandler = cookieHandler ?: return emptyList()
        val cookieHeaders =
            try {
                cookieHandler.get(url.toUri(), emptyMap<String, List<String>>())
            } catch (e: IOException) {
                get().log("Loading cookies failed for " + url.resolve("/...")!!, WARN, e)
                return emptyList()
            }

        var cookies: MutableList<Cookie>? = null
        for ((key, value) in cookieHeaders) {
            if (("Cookie".equals(key, ignoreCase = true) || "Cookie2".equals(key, ignoreCase = true)) &&
                value.isNotEmpty()
            ) {
                for (header in value) {
                    if (cookies == null) cookies = mutableListOf()
                    cookies.addAll(decodeHeaderAsJavaNetCookies(url, header))
                }
            }
        }

        return if (cookies != null) {
            Collections.unmodifiableList(cookies)
        } else {
            emptyList()
        }
    }

    private fun decodeHeaderAsJavaNetCookies(
        url: HttpUrl,
        header: String,
    ): List<Cookie> {
        val result = mutableListOf<Cookie>()
        var pos = 0
        val limit = header.length
        var pairEnd: Int
        while (pos < limit) {
            pairEnd = header.delimiterOffset(";,", pos, limit)
            val equalsSign = header.delimiterOffset('=', pos, pairEnd)
            val name = header.trimSubstring(pos, equalsSign)
            if (name.startsWith("$")) {
                pos = pairEnd + 1
                continue
            }

            var value =
                if (equalsSign < pairEnd) {
                    header.trimSubstring(equalsSign + 1, pairEnd)
                } else {
                    ""
                }

            if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                value = value.substring(1, value.length - 1)
            }

            value = value.trim()

            result.add(
                Cookie
                    .Builder()
                    .name(name)
                    .value(value)
                    .domain(url.host)
                    .build(),
            )
            pos = pairEnd + 1
        }
        return result
    }
}

// This method is copied from okhttp3.internal.Internal.kt, which is not available
// in the version of OkHttp used by this project.
//
// The original implementation only delegates to Cookie.toString(true), but this
// method is internal as well. Therefore, both methods are reproduced here.
//
// Both methods are licensed under the Apache License 2.0.
private fun Cookie.toString(forObsoleteRfc2965: Boolean): String {
    buildString {
        append(name)
        append('=')
        append(value)
        if (persistent) {
            if (expiresAt == Long.MIN_VALUE) append("; max-age=0")
            else append("; expires=").append(Date(expiresAt).toHttpDateString())
        }
        if (!hostOnly) {
            append("; domain=")
            if (forObsoleteRfc2965) append(".")
            append(domain)
        }
        append("; path=").append(path)
        if (secure) append("; secure")
        if (httpOnly) append("; httponly")
        if (sameSite != null) append("; samesite=").append(sameSite)
        return toString()
    }
}
