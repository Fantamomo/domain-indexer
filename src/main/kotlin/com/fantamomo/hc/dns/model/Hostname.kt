package com.fantamomo.hc.dns.model

data class Hostname(val value: String) {
    init {
        require(value.isNotEmpty()) { "Hostname cannot be empty" }
        require(value.contains(".")) { "Hostname must contain a dot" }
        require(!value.contains("..")) { "Hostname cannot contain consecutive dots" }
        require(!value.startsWith('.')) { "Hostname cannot start with a dot" }
        require(!value.endsWith('.')) { "Hostname cannot end with a dot" }
    }

    private val labels = value.split('.')

    val fqdn = "$value."

    val host = labels.takeLast(2).joinToString(".")

    val subdomain = labels
        .dropLast(2)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(".")

    fun hasSubdomain() = subdomain != null
}