package com.fantamomo.hc.dns.util

import com.fantamomo.hc.dns.model.dns.Record
import com.fantamomo.hc.dns.model.dns.RecordType
import com.fantamomo.hc.dns.util.yaml.ListYamlElement
import com.fantamomo.hc.dns.util.yaml.MapYamlElement
import com.fantamomo.hc.dns.util.yaml.StringYamlElement
import com.fantamomo.hc.dns.util.yaml.YamlElement

object DnsParser {
    fun parse(host: String, yaml: YamlElement): List<Record> {
        if (yaml !is MapYamlElement) return emptyList()
        val records = mutableListOf<Record>()
        for ((key, value) in yaml.map) {
            if (value !is MapYamlElement) continue
            val record = parseRecord(host, key, value)
            if (record != null) records += record
        }
        return records
    }

    private fun parseRecord(host: String, key: String, data: MapYamlElement): Record? {
        val ttl = (data.map["ttl"] as? StringYamlElement)?.content?.toIntOrNull()
        val type = (data.map["type"] as? StringYamlElement)
            ?.content
            ?.uppercase()
            ?.let { content -> RecordType.entries.find { it.name == content } }
            ?: return null
        val value = (data.map["value"] as? StringYamlElement)?.content
        if (value != null) {
            return Record(
                host = host,
                name = key,
                type = type,
                ttl = ttl,
                value = value
            )
        }
        val values = (data.map["values"] as? ListYamlElement)?.elements
            ?.filterIsInstance<StringYamlElement>()
            ?.map { it.content }
            ?: return null
        return Record(
            host = host,
            name = key,
            type = type,
            ttl = ttl,
            value = values.joinToString(";")
        )
    }
}