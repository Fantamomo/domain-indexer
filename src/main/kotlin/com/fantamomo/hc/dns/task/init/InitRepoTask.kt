package com.fantamomo.hc.dns.task.init

import com.fantamomo.hc.dns.data.Config
import com.fantamomo.hc.dns.data.SharedValues
import com.fantamomo.hc.dns.task.InitTask
import com.fantamomo.hc.dns.util.KGitCommitGraphAnalyzer
import com.fantamomo.hc.dns.util.humanReadable
import com.github.syari.kgit.KGit
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.time.measureTime

object InitRepoTask : InitTask(
    "init-repo",
    shortDescription = "Initialize git repository",
    longDescription = "Clones the git repository if it doesn't exist, otherwise opens it and fetches updates from the remote"
) {
    private const val REMOTE_URL = "https://github.com/hackclub/dns.git"

    override fun disableLogAndStateSetting() = false

    override suspend fun run() {
        val repoDir = Config.REPO_DIR
        val dir = Path(repoDir.absolutePathString() + ".git").toFile()
        if (dir.exists()) {
            logger.info("Open repo at: $repoDir")
            val openDuration = try {
                measureTime {
                    SharedValues.git = KGit.open(dir)
                }
            } catch (e: Exception) {
                logger.error("Failed to open repo at $repoDir", e)
                markFailed()
                return
            }
            logger.info("Repo opened in ${openDuration.humanReadable()}")
            logger.info("Fetching updates for repo")
            try {
                val duration = measureTime {
                    SharedValues.git.fetch()
                }
                logger.info("Fetched updates in ${duration.humanReadable()}")
            } catch (e: Exception) {
                logger.error("Failed to fetch updates for repo at $repoDir", e)
            }
        } else {
            logger.info("Cloning into $REMOTE_URL")
            try {
                SharedValues.git = KGit.cloneRepository {
                    setBare(true)
                    setURI(REMOTE_URL)
                    setDirectory(dir)
                    setNoCheckout(true)
                    setCloneAllBranches(true)
                    setCloneSubmodules(false)
                    setRemote("origin")
                }
            } catch (e: Exception) {
                logger.error("Failed to clone repo: $REMOTE_URL", e)
                markFailed()
                return
            }
            logger.info("Repo cloned to: $repoDir")
        }
        logger.info("Creating commit graph analyser")
        SharedValues.commitGraphAnalyzer = KGitCommitGraphAnalyzer(SharedValues.git)
        logger.info("Commit graph analyser created")
    }
}