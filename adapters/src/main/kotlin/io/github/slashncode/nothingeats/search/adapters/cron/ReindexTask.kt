package io.github.slashncode.nothingeats.search.adapters.cron

import io.github.slashncode.nothingeats.platform.core.run.CronTask
import io.github.slashncode.nothingeats.search.application.Reindex

/** `cron reindex`: a fresh generation from the catalog topics, swapped in without downtime. Run after mapping changes. */
class ReindexTask(
    private val reindex: Reindex,
) : CronTask {
    override val name = "reindex"

    override suspend fun run() {
        reindex()
    }
}
