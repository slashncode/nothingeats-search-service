package io.github.slashncode.nothingeats.search.application

import org.slf4j.LoggerFactory

/** The live projection: every catalog change goes into the live generation. */
class ProjectCatalog(
    private val indices: SearchIndices,
) {
    suspend operator fun invoke(event: CatalogEvent) = indices.write(event)
}

data class ReindexResult(
    val generation: Generation,
    val replaced: Generation?,
    val events: Int,
)

/**
 * `cron reindex`: builds a fresh generation from the start of the catalog's topics and makes it live
 * without a gap (index → alias swap → catch-up → drop the old one).
 *
 * The live projection keeps writing into the old generation until the swap. Everything it wrote
 * after the replay ended is still in the log, so the catch-up after the swap brings it into the new
 * generation. Versions make the overlap with the live projection harmless.
 */
class Reindex(
    private val indices: SearchIndices,
    private val log: CatalogLog,
) {
    private val logger = LoggerFactory.getLogger(Reindex::class.java)

    suspend operator fun invoke(): ReindexResult {
        val generation = indices.create()
        var events = 0
        val end =
            log.replay { event ->
                indices.write(event, into = generation)
                events++
            }
        val replaced = indices.publish(generation)
        log.catchUp(end) { event ->
            indices.write(event, into = generation)
            events++
        }
        replaced?.let { indices.drop(it) }
        logger.info("Reindexed {} events into {}, replacing {}", events, generation.name, replaced?.name)
        return ReindexResult(generation, replaced, events)
    }
}

/** On start: the very first deployment has no generation yet; the live consumer then fills it from offset zero. */
class EnsureLiveGeneration(
    private val indices: SearchIndices,
) {
    suspend operator fun invoke(): Generation = indices.live() ?: indices.create().also { indices.publish(it) }
}
