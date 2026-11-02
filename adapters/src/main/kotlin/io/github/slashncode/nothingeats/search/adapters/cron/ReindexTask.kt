package io.github.slashncode.nothingeats.search.adapters.cron

import io.github.slashncode.nothingeats.platform.core.run.CronTask
import io.github.slashncode.nothingeats.search.application.Reindexing

/**
 * `cron reindex`: a fresh generation from the catalog topics, swapped in without downtime. Run after
 * mapping changes. Waits for nobody: while the backoffice's run holds the claim, it fails.
 */
class ReindexTask(
    private val reindexing: Reindexing,
) : CronTask {
    override val name = "reindex"

    override suspend fun run() {
        check(!reindexing.runNow().failed) { "Reindex failed; the old generation stays live" }
    }
}
