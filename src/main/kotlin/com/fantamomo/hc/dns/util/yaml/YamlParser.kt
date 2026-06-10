package com.fantamomo.hc.dns.util.yaml

// we use our own parser because other parsers are to slow and have features we don't need
// also some parsers are to strict and some of yaml we are reading is not correct
object YamlParser {

    fun parse(input: String): YamlElement =
        parse(input.lineSequence())

    fun parse(lines: List<String>): YamlElement =
        parse(lines.asSequence())

    fun parse(lines: Sequence<String>): YamlElement {
        val cleaned = preprocess(lines)

        val root = LinkedHashMap<String, YamlElement>()

        var i = 0

        while (i < cleaned.size) {
            val line = cleaned[i]
            val indent = indentation(line)
            val trimmed = line.trim()

            if (trimmed.isEmpty() || trimmed == "---") {
                i++
                continue
            }

            if (trimmed.startsWith("- ")) {
                val (list, next) = parseList(cleaned, i, indent)
                root["_root_${i}"] = list
                i = next
                continue
            }

            val (key, value, hasValueInline) = readKeyValue(trimmed)

            if (hasValueInline) {
                root[key] = StringYamlElement(value)
                i++
                continue
            }

            val (element, next) = readValue(cleaned, i, indent)
            root[key] = element
            i = next
        }

        return MapYamlElement(root)
    }

    private fun readValue(
        lines: List<String>,
        index: Int,
        baseIndent: Int
    ): Pair<YamlElement, Int> {

        var i = index + 1
        if (i >= lines.size) return StringYamlElement("") to i

        var line = lines[i]
        var indent = indentation(line)

        while (i < lines.size && line.trim().isEmpty()) {
            i++
            if (i >= lines.size) return StringYamlElement("") to i
            line = lines[i]
            indent = indentation(line)
        }

        return when {
            line.trimStart().startsWith("- ") ->
                parseList(lines, i, indent)

            isLikelyMapStart(line) ->
                parseMap(lines, i, indent)

            else -> {
                val sb = StringBuilder()

                var j = i
                var lastIndent = indent

                while (j < lines.size) {
                    val l = lines[j]
                    val t = l.trim()

                    if (t.startsWith("- ") || isLikelyMapStart(l) && indentation(l) <= baseIndent) break
                    if (isLikelyKeyLine(t)) break

                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(t)

                    lastIndent = indentation(l)
                    j++
                }

                StringYamlElement(unquote(sb.toString().trim())) to j
            }
        }
    }

    private fun parseMap(
        lines: List<String>,
        start: Int,
        baseIndent: Int
    ): Pair<YamlElement, Int> {

        val map = LinkedHashMap<String, YamlElement>()
        var i = start

        var lastKey: String? = null

        while (i < lines.size) {
            val line = lines[i]
            val indent = indentation(line)
            val trimmed = line.trim()

            if (trimmed.isEmpty()) {
                i++
                continue
            }

            if (indent < baseIndent) break
            if (trimmed.startsWith("- ")) break

            val (key, value, inline) = readKeyValue(trimmed)

            if (key.isEmpty()) {
                lastKey?.let {
                    val prev = map[it]
                    map[it] = StringYamlElement(
                        ((prev as? StringYamlElement)?.content ?: prev.toString()) +
                                " " + trimmed
                    )
                }
                i++
                continue
            }

            lastKey = key

            if (inline) {
                map[key] = StringYamlElement(value)
                i++
                continue
            }

            val (element, next) = readValue(lines, i, indent)
            map[key] = element
            i = next
        }

        return MapYamlElement(map) to i
    }

    private fun parseList(
        lines: List<String>,
        start: Int,
        baseIndent: Int
    ): Pair<YamlElement, Int> {

        val list = mutableListOf<YamlElement>()
        var i = start

        var pendingMap: LinkedHashMap<String, YamlElement>? = null
        var lastMapKey: String? = null

        while (i < lines.size) {
            val line = lines[i]
            val indent = indentation(line)
            val trimmed = line.trim()

            if (indent < baseIndent) break
            if (!trimmed.startsWith("- ")) break

            val content = trimmed.removePrefix("- ").trim()

            // scalar item
            if (!content.contains(":")) {
                list += StringYamlElement(unquote(content))
                i++
                continue
            }

            val map = LinkedHashMap<String, YamlElement>()
            pendingMap = map

            parseKeyValueIntoMap(map, content)
            lastMapKey = map.keys.last()

            i++

            while (i < lines.size) {
                val next = lines[i]
                val nextTrim = next.trim()
                val nextIndent = indentation(next)

                if (nextIndent <= indent && nextTrim.startsWith("- ")) break
                if (nextIndent <= indent && isLikelyKeyLine(nextTrim)) break

                if (nextTrim.startsWith("- ")) {
                    val (subList, newI) = parseList(lines, i, nextIndent)
                    lastMapKey.let { map[it] = subList }
                    i = newI
                    continue
                }

                if (isLikelyKeyLine(nextTrim)) {
                    parseNestedMapEntry(map, nextTrim)
                    i++
                    continue
                }

                // broken continuation (fo=1 style)
                lastMapKey.let {
                    val prev = map[it]
                    map[it] = StringYamlElement(
                        ((prev as? StringYamlElement)?.content ?: "") +
                                " " + nextTrim
                    )
                }

                i++
            }

            list += MapYamlElement(map)
        }

        return ListYamlElement(list) to i
    }

    private fun readKeyValue(line: String): Triple<String, String, Boolean> {
        val idx = line.indexOf(':')
        if (idx == -1) return Triple("", "", false)

        val key = unquote(line.substring(0, idx).trim())
        val value = line.substring(idx + 1).trim()

        return if (value.isNotEmpty())
            Triple(key, unquote(value), true)
        else
            Triple(key, "", false)
    }

    private fun parseKeyValueIntoMap(map: MutableMap<String, YamlElement>, line: String) {
        val idx = line.indexOf(':')
        val key = unquote(line.substring(0, idx).trim())
        val value = line.substring(idx + 1).trim()
        map[key] = StringYamlElement(unquote(value))
    }

    private fun parseNestedMapEntry(map: MutableMap<String, YamlElement>, line: String) {
        val idx = line.indexOf(':')
        if (idx == -1) return

        val key = unquote(line.substring(0, idx).trim())
        val value = line.substring(idx + 1).trim()

        map[key] = StringYamlElement(unquote(value))
    }

    private fun isLikelyMapStart(line: String): Boolean =
        ':' in line

    private fun isLikelyKeyLine(line: String): Boolean =
        ':' in line || line.startsWith("- ")

    private fun indentation(line: String): Int {
        var i = 0
        while (i < line.length && line[i].isWhitespace()) i++
        return i
    }

    private fun preprocess(lines: Sequence<String>): List<String> {
        val out = ArrayList<String>(1024)

        for (raw in lines) {
            val noComment = raw.substringBefore('#').trimEnd()
            if (noComment.isBlank() || noComment == "---") continue

            out += noComment
        }

        return out
    }

    private fun unquote(value: String): String {
        val t = value.trim()
        if (t.length < 2) return t

        return if (
            (t.startsWith('"') && t.endsWith('"')) ||
            (t.startsWith('\'') && t.endsWith('\''))
        ) t.substring(1, t.length - 1)
        else t
    }
}