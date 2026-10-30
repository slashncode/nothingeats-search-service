package io.github.slashncode.nothingeats.search.application

import io.github.slashncode.nothingeats.search.domain.CatalogVocabulary
import io.github.slashncode.nothingeats.search.domain.RankFusion
import io.github.slashncode.nothingeats.search.domain.RankedDish
import io.github.slashncode.nothingeats.search.domain.Retrieval
import io.github.slashncode.nothingeats.search.domain.SearchableDish
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

data class RetrievedDish(
    val dish: SearchableDish,
    val rank: RankedDish,
)

/** [openRestaurantIds]: the restaurants open right now, so callers can tell what can be ordered. */
data class RetrievalResult(
    val dishes: List<RetrievedDish>,
    val semantic: Boolean,
    val openRestaurantIds: Set<String>,
)

/**
 * Hybrid retrieval for the Craving Coach: the keyword leg (BM25 with the search analyzers and
 * synonyms) finds what the words say, the meaning leg (kNN on dish embeddings) what they mean
 * ("something warm for a rainy evening" has no dish name in it). Both run with the same hard
 * filters; weighted reciprocal rank fusion merges them.
 *
 * The query is embedded here, by the same model as the dishes, so one service owns the vector
 * space. Without the model the retrieval still answers, by keywords only ([RetrievalResult.semantic]).
 */
class RetrieveDishes(
    private val index: DishIndex,
    private val restaurants: RestaurantDirectory,
    private val embeddings: EmbeddingModel,
    private val clock: Clock = Clock.system(BERLIN),
) {
    private val log = LoggerFactory.getLogger(RetrieveDishes::class.java)

    suspend operator fun invoke(retrieval: Retrieval): RetrievalResult {
        val open = restaurants.openAt(clock)
        val vector = if (retrieval.isBlank) null else embed(retrieval)
        val candidates = index.retrieve(retrieval, vector, open.takeIf { retrieval.filters.openNow }, CANDIDATES)
        val semantic = candidates.semantic
        val byId = (candidates.lexical + semantic.orEmpty()).associateBy { it.id }
        val ranked =
            RankFusion.fuse(
                lexical = candidates.lexical.map { it.id },
                semantic = semantic.orEmpty().map { it.id },
                semanticWeight = if (semantic == null) 0.0 else retrieval.semanticWeight,
            )
        return RetrievalResult(
            dishes = ranked.take(retrieval.limit).map { RetrievedDish(byId.getValue(it.id), it) },
            semantic = semantic != null,
            openRestaurantIds = open,
        )
    }

    private suspend fun embed(retrieval: Retrieval): DishVector? =
        try {
            DishVector(embeddings.id, embeddings.embed(listOf(retrieval.semanticText)).single())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not embed a retrieval query; answering by keywords only", e)
            null
        }

    private companion object {
        /** Per leg: enough for the fusion to find agreement beyond the first page. */
        const val CANDIDATES = 30
    }
}

data class DishesNow(
    val dishes: List<SearchableDish>,
    val openRestaurantIds: Set<String>,
)

/** Dishes by id, e.g. the ones a model wants to recommend, checked against the catalog. */
class GetDishes(
    private val index: DishIndex,
    private val restaurants: RestaurantDirectory,
    private val clock: Clock = Clock.system(BERLIN),
) {
    suspend operator fun invoke(ids: List<String>): DishesNow {
        if (ids.size > MAX_IDS) throw invalid("ids", "must be at most $MAX_IDS")
        val wanted = ids.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return DishesNow(emptyList(), emptySet())
        return DishesNow(index.get(wanted), restaurants.openAt(clock))
    }

    private companion object {
        const val MAX_IDS = 20
    }
}

/** What a model may filter by: built from the live catalog, so it never offers a tag no dish has. */
class DescribeCatalog(
    private val index: DishIndex,
) {
    suspend operator fun invoke(): CatalogVocabulary = index.vocabulary()
}
