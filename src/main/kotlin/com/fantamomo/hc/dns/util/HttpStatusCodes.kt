package com.fantamomo.hc.dns.util

import io.ktor.http.*

private val statusCodesMap: Map<Int, HttpStatusCode> = HttpStatusCode.allStatusCodes.associateBy { it.value }

fun HttpStatusCode.internal() = statusCodesMap[this.value] ?: this