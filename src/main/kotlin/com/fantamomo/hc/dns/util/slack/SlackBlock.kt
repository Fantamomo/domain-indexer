package com.fantamomo.hc.dns.util.slack

import kotlinx.serialization.json.*

interface SlackBlock {
    fun toJson(): JsonObject
}

sealed interface RichElement {
    fun toJson(): JsonObject
}

data class HeaderBlock(val text: String) : SlackBlock {
    override fun toJson() = jsonObj(
        "type" to "header",
        "text" to jsonObj("type" to "plain_text", "text" to text.cap(150), "emoji" to true)
    )
}

object DividerBlock : SlackBlock {
    override fun toJson() = jsonObj("type" to "divider")
}

data class SectionBlock(
    val text: String? = null,
    val fields: List<String> = emptyList(),
) : SlackBlock {
    override fun toJson() = buildJsonObject {
        put("type", "section")
        text?.let { put("text", jsonObj("type" to "mrkdwn", "text" to it.cap(3000))) }
        if (fields.isNotEmpty()) {
            put("fields", JsonArray(fields.map { jsonObj("type" to "mrkdwn", "text" to it.cap(2000)) }))
        }
    }
}

data class ContextBlock(val elements: List<ContextElement>) : SlackBlock {
    sealed interface ContextElement {
        fun toJson(): JsonObject
    }

    data class MarkdownElement(val text: String) : ContextElement {
        override fun toJson() = jsonObj("type" to "mrkdwn", "text" to text.cap(300))
    }

    data class ImageElement(val url: String, val altText: String) : ContextElement {
        override fun toJson() = jsonObj("type" to "image", "image_url" to url, "alt_text" to altText)
    }

    override fun toJson() = jsonObj(
        "type" to "context",
        "elements" to JsonArray(elements.map { it.toJson() })
    )
}

data class RichTextBlock(val sections: List<RichTextSection>) : SlackBlock {
    override fun toJson() = jsonObj(
        "type" to "rich_text",
        "elements" to JsonArray(sections.map { it.toJson() })
    )
}

data class RichTextSection(val elements: List<RichElement>) {
    fun toJson() = jsonObj(
        "type" to "rich_text_section",
        "elements" to JsonArray(elements.map { it.toJson() })
    )
}

data class RichTextListSection(
    val elements: List<RichTextSection>,
    val style: Style = Style.BULLET,
    val indent: Int = 0,
) {
    enum class Style(val value: String) { BULLET("bullet"), ORDERED("ordered") }

    fun toJson() = jsonObj(
        "type" to "rich_text_list",
        "style" to style.value,
        "indent" to indent,
        "elements" to JsonArray(elements.map { it.toJson() })
    )
}

data class TextElement(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
) : RichElement {
    override fun toJson() = buildJsonObject {
        put("type", "text")
        put("text", text)
        val hasStyle = bold || italic || strike || code
        if (hasStyle) {
            put("style", buildJsonObject {
                if (bold) put("bold", true)
                if (italic) put("italic", true)
                if (strike) put("strike", true)
                if (code) put("code", true)
            })
        }
    }
}

data class LinkElement(
    val url: String,
    val text: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
) : RichElement {
    override fun toJson() = buildJsonObject {
        put("type", "link")
        put("url", url)
        text?.let { put("text", it) }
        val hasStyle = bold || italic
        if (hasStyle) {
            put("style", buildJsonObject {
                if (bold) put("bold", true)
                if (italic) put("italic", true)
            })
        }
    }
}

data class UserElement(val userId: String) : RichElement {
    override fun toJson() = jsonObj("type" to "user", "user_id" to userId)
}

data class UserGroupElement(val groupId: String) : RichElement {
    override fun toJson() = jsonObj("type" to "usergroup", "usergroup_id" to groupId)
}

data class EmojiElement(val name: String) : RichElement {
    override fun toJson() = jsonObj("type" to "emoji", "name" to name)
}

internal fun jsonObj(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.associate { (k, v) ->
        k to when (v) {
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            null -> JsonNull
            else -> JsonPrimitive(v.toString())
        }
    })

internal fun String.cap(max: Int): String =
    if (length <= max) this else take(max - 1) + "…"

internal fun String.sanitize(): String {
    if (this == "\n") return this
    return replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), " ")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .trim()
}