package io.github.slashncode.nothingeats.search.application

import io.github.slashncode.nothingeats.search.domain.embeddingText
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns texts into vectors of one space. [id] names model and dimensions: vectors of different ids
 * are never compared, so changing either needs a reindex.
 */
interface EmbeddingModel {
    val id: String
    val dimensions: Int

    /** One vector per text, in order. Throws when the model is unavailable. */
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/** A dish's vector and the space it lives in. */
class DishVector(
    val model: String,
    val values: FloatArray,
)

/**
 * The vector for a dish event, from the dish's [embeddingText]. A model that fails gives null: the
 * dish is then found by its words only until the next reindex, which beats holding up the
 * projection (and every catalog change behind it) while the model is down.
 */
class DishVectors(
    private val model: EmbeddingModel,
) {
    private val log = LoggerFactory.getLogger(DishVectors::class.java)

    suspend fun of(event: CatalogEvent): DishVector? {
        if (event !is CatalogEvent.DishPublished) return null
        return try {
            DishVector(model.id, model.embed(listOf(event.dish.embeddingText())).single())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("No embedding for dish {}; it is indexed without one", event.dish.id, e)
            null
        }
    }
}
