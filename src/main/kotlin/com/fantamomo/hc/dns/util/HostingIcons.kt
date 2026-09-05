package com.fantamomo.hc.dns.util

import org.intellij.lang.annotations.Language

interface HostingIcons {

    /**
     * The slack emoji which will be displayed next to the host value of it matches the [match] function.
     *
     * Without the `:` at the start and end.
     */
    val icon: String

    /**
     * Check if the given host is a match for this hosting icon.
     *
     * @param host The host to check. Can be a domain or IP address. Domain names will end with a dot.
     */
    fun match(host: String): Boolean

    class RegexHostingIcon(val regexes: List<Regex>, override val icon: String) : HostingIcons {
        override fun match(host: String) = regexes.any { it.matches(host) }
    }

    companion object {

        val NETLIFY = regex(
            ".+\\.netlify\\.(app|com)\\.",
            icon = "netlify"
        )
        val VERCEL = regex(
            "cname.vercel-dns\\.com\\.",
            "[0-9a-f]{16}.vercel-dns-0\\d{2}.com\\.",
            ".+\\.vercel\\.app\\.",
            ".+\\.vercel-dns\\.com\\.",
            "216\\.198\\.79\\.1",
            "216\\.150\\.1\\.1",
            "76\\.76\\.21\\.21",
            // the hackclub.dev name server points to Vercel for automatic subdomain creation for deployed projects, see https://github.com/hackclub/dns/blob/main/hackclub.dev.md
            ".+\\.hackclub\\.dev\\.",
            "hackclub\\.dev\\.",
            icon = "vercel"
        )
        val GITHUB_PAGES = regex(
            ".+\\.github\\.io\\.",
            icon = "github"
        )
        val COOLIFY_A = regex(
            "a\\.selfhosted\\.hackclub\\.com\\.",
            icon = "a"
        )
        val COOLIFY_B = regex(
            "b\\.selfhosted\\.hackclub\\.com\\.",
            icon = "b"
        )
        val ORCHARD = regex(
            "a\\.ingress\\.tier2\\.infra\\.hackclub\\.com\\.",
            icon = "orchard"
        )
        val NEST = regex(
            ".+\\.hackclub\\.app\\.",
            "65\\.108\\.74\\.29", // nests ip address
            icon = "nest"
        )
        val CLOUDFLARE_PAGES = regex(
            ".+\\.pages\\.dev\\.",
            "pages\\.dev\\.",
            icon = "cloudflare"
        )


        val all: List<HostingIcons> = listOf(
            NETLIFY,
            VERCEL,
            GITHUB_PAGES,
            COOLIFY_A,
            COOLIFY_B,
            ORCHARD,
            NEST,
            CLOUDFLARE_PAGES
        )

        fun findEmoji(value: String): String? = all.firstOrNull { it.match(value) }?.icon


        private fun regex(@Language("RegExp") vararg regexes: String, icon: String) =
            RegexHostingIcon(regexes.map { Regex(it) }, icon = icon)
    }
}