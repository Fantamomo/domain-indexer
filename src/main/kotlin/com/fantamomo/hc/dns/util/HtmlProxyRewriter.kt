package com.fantamomo.hc.dns.util

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

object HtmlProxyRewriter {

    private val urlAttributes = setOf(
        "href",
        "src",
        "action",
        "formaction",
        "poster",
        "data",
        "manifest",
        "cite",
        "background"
    )

    fun rewrite(
        html: String,
        sourceUrl: String,
        proxyUrl: String
    ): String {
        val doc = Jsoup.parse(
            html,
            sourceUrl
        )

        doc.select("*").forEach { element ->
            rewriteAttributes(
                element,
                sourceUrl,
                proxyUrl
            )
            rewriteStyle(
                element,
                sourceUrl,
                proxyUrl
            )
            rewriteSrcSet(
                element,
                sourceUrl,
                proxyUrl
            )
        }
        return doc.outerHtml()
    }

    private fun rewriteAttributes(
        element: Element,
        sourceUrl: String,
        proxyUrl: String
    ) {
        element.attributes()
            .forEach { attr ->
                if (attr.key.lowercase() !in urlAttributes) {
                    return@forEach
                }

                attr.setValue(
                    rewriteUrl(
                        attr.value,
                        sourceUrl,
                        proxyUrl
                    )
                )
            }
    }

    private fun rewriteUrl(
        raw: String,
        sourceUrl: String,
        proxyUrl: String
    ): String {
        val value = raw.trim()
        if (value.isEmpty() ||
            value.startsWith("#") ||
            value.startsWith("mailto:") ||
            value.startsWith("tel:") ||
            value.startsWith("javascript:")
        ) return raw

        val sourceUri = try {
            URI(sourceUrl)
        } catch (_: Exception) {
            return raw
        }

        val targetUri = try {
            sourceUri.resolve(value)
        } catch (_: Exception) {
            return raw
        }

        val targetHost =
            targetUri.host ?: return raw

        val sourceHost =
            sourceUri.host ?: return raw

        val isAbsolute =
            URI(value).isAbsolute

        if (isAbsolute && !targetHost.equals(sourceHost, ignoreCase = true)) {
            return raw
        }

        return buildString {
            append(proxyUrl.trimEnd('/'))
            append(
                targetUri.rawPath
                    ?.takeIf {
                        it.isNotEmpty()
                    }
                    ?: "/"
            )
            targetUri.rawQuery?.let {
                append("?")
                append(it)
            }
            targetUri.rawFragment?.let {
                append("#")
                append(it)
            }
        }
    }

    private fun rewriteSrcSet(
        element: Element,
        sourceUrl: String,
        proxyUrl: String
    ) {
        if (!element.hasAttr("srcset")) {
            return
        }
        val rewritten = element.attr("srcset")
            .split(",")
            .joinToString(",") { item ->
                val parts = item.trim()
                    .split(
                        Regex("\\s+"),
                        limit = 2
                    )
                val url = parts[0]
                val descriptor = parts.getOrNull(1)
                val result = rewriteUrl(
                    url,
                    sourceUrl,
                    proxyUrl
                )

                if (descriptor != null) {
                    "$result $descriptor"
                } else {
                    result
                }
            }
        element.attr(
            "srcset",
            rewritten
        )
    }

    private fun rewriteStyle(
        element: Element,
        sourceUrl: String,
        proxyUrl: String
    ) {
        if (!element.hasAttr("style")) {
            return
        }
        element.attr(
            "style",
            rewriteCss(
                element.attr("style"),
                sourceUrl,
                proxyUrl
            )
        )
    }

    private fun rewriteCss(
        css: String,
        sourceUrl: String,
        proxyUrl: String
    ): String {
        return Regex(
            """url\((['"]?)(.*?)\1\)"""
        ).replace(css) { match ->
            val url = match.groupValues[2]
            "url(${
                rewriteUrl(
                    url,
                    sourceUrl,
                    proxyUrl
                )
            })"
        }
    }
}