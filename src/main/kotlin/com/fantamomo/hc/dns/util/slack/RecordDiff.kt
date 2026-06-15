package com.fantamomo.hc.dns.util.slack

import com.fantamomo.hc.dns.model.RepoWithBranch
import com.fantamomo.hc.dns.model.dns.RecordType

data class RecordDiff(
    val fqdn: String,
    val type: RecordType,
    val source: RepoWithBranch? = null,
    val valueChange: ValueChange? = null,
    val ttlChange: FieldChange<Int>? = null,
    val commit: String? = null,
)

sealed interface ValueChange {
    data class Added(val value: String) : ValueChange
    data class Removed(val value: String) : ValueChange
    data class Modified(val old: String, val new: String) : ValueChange
    data class Unchanged(val value: String) : ValueChange
}

data class FieldChange<T>(val old: T, val new: T) {
    val changed get() = old != new
}

fun RichSectionBuilder.renderDiff(diff: RecordDiff, commitsToSlackId: Map<String, Pair<String?, String?>>) {
    newline()
    bold("Name: ")
    if (diff.type.isNamedRecordALink()) {

        @Suppress("HttpUrlsUsage")
        link("http://${diff.fqdn}", diff.fqdn.cap(120))

        newline()
    } else {
        code(diff.fqdn.cap(120))
        newline()
    }
    bold("Type: ")
    code(diff.type.name)

    diff.source?.let { repo ->
        newline()
        bold("Repo: ")
        link(repo.toUrl(), repo.toCombinedName(), code = true)
    }

    newline()
    bold("Value: ")
    when (val vc = diff.valueChange) {
        is ValueChange.Added -> code(vc.value.cap(120))
        is ValueChange.Removed -> strikeCode(vc.value.cap(120))
        is ValueChange.Modified -> {
            strikeCode(vc.old.cap(80))
            text("  →  ")
            code(vc.new.cap(80))
        }
        is ValueChange.Unchanged -> code(vc.value.cap(120))
        null -> text("—")
    }

    diff.ttlChange?.takeIf { it.changed }?.let { ttl ->
        newline()
        bold("TTL: ")
        text(ttl.old.toString(), strike = true)
        text("  →  ")
        text(ttl.new.toString())
    } ?: diff.ttlChange?.let { ttl ->
        newline()
        italic("TTL: ${ttl.new}")
    }

    val authorAndCommiter = commitsToSlackId[diff.commit]
    if (authorAndCommiter != null) {
        val author = authorAndCommiter.first
        val commiter = authorAndCommiter.second

        if (author == null && commiter == null) return

        newline()
        if (author != null) {
            bold("Author: ")
            user(author)
            if (commiter != null && author != commiter) {
                text(" (Commiter: ")
                user(commiter)
                text(")")
            }
        } else if (commiter != null) {
            bold("Commiter: ")
            user(commiter)
        }
    }
}