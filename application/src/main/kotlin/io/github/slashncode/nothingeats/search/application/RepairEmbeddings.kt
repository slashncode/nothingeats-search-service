package io.github.slashncode.nothingeats.search.application

import io.github.slashncode.nothingeats.platform.core.error.AppError
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Dishes indexed while the embedding model failed carry no vector: retrieval finds them by
 * keywords only, and no later event would fill the gap. When some dish lacks one and [probe]
 * (the model without its cache, so the answer is fresh) works again, this starts a reindex, the
 * same single run across pods as the backoffice's button. Vectors in the cache cost nothing, so
 * only the missing ones reach the model.
 */
class RepairEmbeddings(
    private val indices: SearchIndices,
    private val probe: EmbeddingModel,
    private val reindexing: Reindexing,
) {
    private val log = LoggerFactory.getLogger(RepairEmbeddings::class.java)

    /** The reindex it started, or null when nothing was missing, the model still fails or a run is on. */
    suspend operator fun invoke(): ReindexRun? {
        val missing = indices.count().dishesWithoutVector
        if (missing == 0L) return null
        try {
            probe.embed(listOf(PROBE))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.info("{} dishes have no vector and the embedding model still fails: {}", missing, e.message)
            return null
        }
        return try {
            reindexing.start().also { log.warn("{} dishes had no vector; the embedding model is back, started reindex {}", missing, it.id) }
        } catch (e: AppError.Conflict) {
            null
        }
    }

    private companion object {
        const val PROBE = "Pizza"
    }
}
