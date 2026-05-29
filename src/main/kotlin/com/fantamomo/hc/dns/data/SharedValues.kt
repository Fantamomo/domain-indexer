package com.fantamomo.hc.dns.data

import com.fantamomo.hc.dns.util.KGitCommitGraphAnalyzer
import com.github.syari.kgit.KGit

internal object SharedValues {
    lateinit var git: KGit
    lateinit var commitGraphAnalyzer: KGitCommitGraphAnalyzer
}