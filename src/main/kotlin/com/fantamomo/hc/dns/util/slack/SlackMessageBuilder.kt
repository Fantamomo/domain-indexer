package com.fantamomo.hc.dns.util.slack

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class SlackMessageBuilder {
    private val blocks = mutableListOf<SlackBlock>()
    private var lastWasDivider = false

    val blockCount get() = blocks.size

    fun add(block: SlackBlock) {
        if (overflow()) return
        if (block is DividerBlock && lastWasDivider) return
        blocks += block
        lastWasDivider = block is DividerBlock
    }

    fun header(text: String) = add(HeaderBlock(text))

    fun divider() = add(DividerBlock)

    fun section(text: String? = null, fields: List<String> = emptyList()) =
        add(SectionBlock(text = text, fields = fields))

    fun context(vararg elements: ContextBlock.ContextElement) =
        add(ContextBlock(elements.toList()))

    fun contextMarkdown(text: String) =
        context(ContextBlock.MarkdownElement(text))

    fun richText(init: RichTextBlockBuilder.() -> Unit) {
        val builder = RichTextBlockBuilder().apply(init)
        builder.buildBlocks().forEach { add(it) }
    }

    private fun overflow() = blocks.size >= BLOCK_LIMIT - 2

    fun build(altText: String = "Notification"): JsonObject = buildJsonObject {
//        put("text", altText)
        put("blocks", JsonArray(blocks.map { it.toJson() }))
    }

    companion object {
        const val BLOCK_LIMIT = 50
    }
}

class RichTextBlockBuilder {
    private val sections = mutableListOf<Any>()

    fun section(init: RichSectionBuilder.() -> Unit) {
        sections += RichTextSection(RichSectionBuilder().apply(init).build())
    }

    fun list(style: RichTextListSection.Style = RichTextListSection.Style.BULLET, indent: Int = 0, init: RichListBuilder.() -> Unit) {
        sections += RichTextListSection(RichListBuilder().apply(init).build(), style, indent)
    }

    fun buildBlocks(): List<SlackBlock> {
        if (sections.isEmpty()) return emptyList()
        return listOf(
            RichTextBlock(sections.filterIsInstance<RichTextSection>())
                .let { block ->
                    val allElements = mutableListOf<Any>()
                    sections.forEach { allElements += it }
                    buildCombinedBlock(allElements)
                }
        )
    }

    private fun buildCombinedBlock(elements: List<Any>): SlackBlock {
        val jsonElements = elements.map {
            when (it) {
                is RichTextSection -> it.toJson()
                is RichTextListSection -> it.toJson()
                else -> throw IllegalStateException("Unknown element type: $it")
            }
        }
        return object : SlackBlock {
            override fun toJson() = jsonObj(
                "type" to "rich_text",
                "elements" to JsonArray(jsonElements)
            )
        }
    }
}

class RichListBuilder {
    private val rows = mutableListOf<RichTextSection>()

    fun item(init: RichSectionBuilder.() -> Unit) {
        rows += RichTextSection(RichSectionBuilder().apply(init).build())
    }

    fun build() = rows
}

class RichSectionBuilder {
    private val elements = mutableListOf<RichElement>()

    fun text(text: String, bold: Boolean = false, italic: Boolean = false, strike: Boolean = false, code: Boolean = false) =
        elements.add(TextElement(text.sanitize(), bold, italic, strike, code))

    fun bold(text: String) = text(text, bold = true)

    fun italic(text: String) = text(text, italic = true)

    fun code(text: String) = text(text, code = true)

    fun strikeCode(text: String) = text(text, code = true, strike = true)

    fun link(url: String, label: String? = null, bold: Boolean = false) =
        elements.add(LinkElement(url, label, bold))

    fun user(userId: String) = elements.add(UserElement(userId))

    fun userGroup(groupId: String) = elements.add(UserGroupElement(groupId))

    fun emoji(name: String) = elements.add(EmojiElement(name))

    fun newline() = text("\n")

    fun build() = elements
}

fun buildSlackMessage(altText: String = "Notification", init: SlackMessageBuilder.() -> Unit): JsonObject =
    SlackMessageBuilder().apply(init).build(altText)