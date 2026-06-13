package com.fantamomo.hc.dns.util.slack

private const val ITEMS_PER_BLOCK = 10

fun SlackMessageBuilder.addDiffSection(
    title: String,
    diffs: List<RecordDiff>,
    commitsToSlackId: Map<String, Pair<String?, String?>>
) {
    if (diffs.isEmpty()) return
    if (blockCount >= SlackMessageBuilder.BLOCK_LIMIT - 2) return

    richText {
        section {
            bold("$title (${diffs.size})")
        }
    }

    val chunks = diffs.chunked(ITEMS_PER_BLOCK)
    chunks.forEachIndexed { chunkIndex, chunk ->
        if (blockCount >= SlackMessageBuilder.BLOCK_LIMIT - 2) {
            contextMarkdown("_Some entries omitted due to Slack block limit_")
            return
        }

        richText {
            section {
                if (chunkIndex > 0) italic("Part ${chunkIndex + 1}")
                chunk.forEachIndexed { index, diff ->
                    if (index > 0) newline()
                    renderDiff(diff, commitsToSlackId)
                }
                newline()
            }
        }
    }

    divider()
}