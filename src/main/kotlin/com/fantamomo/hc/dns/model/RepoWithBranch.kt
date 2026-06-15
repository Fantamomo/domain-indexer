package com.fantamomo.hc.dns.model

data class RepoWithBranch(
    val user: String,
    val repo: String,
    val branch: String
) {
    fun toUrl() = "https://github.com/$user/$repo/tree/$branch"

    fun toCombinedName() = "$user/$repo:$branch"
}