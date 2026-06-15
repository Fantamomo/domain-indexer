package com.fantamomo.hc.dns.util.slack

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

fun JsonObjectBuilder.putStyle(bold: Boolean = false, italic: Boolean = false, strike: Boolean = false, code: Boolean = false) {
    val hasStyle = bold || italic || strike || code
    if (hasStyle) {
        putJsonObject("style") {
            if (bold) put("bold", true)
            if (italic) put("italic", true)
            if (strike) put("strike", true)
            if (code) put("code", true)
        }
    }
}