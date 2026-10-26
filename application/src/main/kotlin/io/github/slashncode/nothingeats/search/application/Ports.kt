package io.github.slashncode.nothingeats.search.application

import io.github.slashncode.nothingeats.search.domain.DishQuery
import io.github.slashncode.nothingeats.search.domain.DishResults
import io.github.slashncode.nothingeats.search.domain.Language
import io.github.slashncode.nothingeats.search.domain.SearchableDish
import io.github.slashncode.nothingeats.search.domain.SearchableRestaurant
import io.github.slashncode.nothingeats.search.domain.Suggestion

/**
 * A change from the catalog's compacted topics. [version] is the record's offset: per aggregate it
 * only grows, so an index can drop anything older than what it has (external versioning).
 */
sealed interface CatalogEvent {
    val version: Long

    data class DishPublished(
        val dish: SearchableDish,
        override val version: Long,
    ) : CatalogEvent

    data class RestaurantPublished(
        val restaurant: SearchableRestaurant,
        override val version: Long,
    ) : CatalogEvent
}

/** One set of search indices, built from one replay of the catalog. */
@JvmInline
value class Generation(
    val name: String,
)

interface SearchIndices {
    /** The generation readers and the live projection use. */
    suspend fun live(): Generation?

    suspend fun create(): Generation

    /** Writes into [into], or into the live generation; older versions than stored are dropped. */
    suspend fun write(
        event: CatalogEvent,
        into: Generation? = null,
    )

    /** Makes [generation] live in one atomic step; returns the one it replaced. */
    suspend fun publish(generation: Generation): Generation?

    suspend fun drop(generation: Generation)
}

/** Where the replayed log ended, so a catch-up can continue exactly there. */
interface LogPosition

/** The catalog's compacted topics, read from the start (record 024: they hold the whole catalog). */
interface CatalogLog {
    suspend fun replay(handle: suspend (CatalogEvent) -> Unit): LogPosition

    /** From [from] to the current end. */
    suspend fun catchUp(
        from: LogPosition,
        handle: suspend (CatalogEvent) -> Unit,
    ): LogPosition
}

interface DishIndex {
    /** [openRestaurantIds] restricts to these restaurants ("open now"); null means no restriction. */
    suspend fun search(
        query: DishQuery,
        openRestaurantIds: Set<String>?,
    ): DishResults

    suspend fun suggest(
        prefix: String,
        language: Language,
        limit: Int,
    ): List<Suggestion>
}

interface RestaurantDirectory {
    suspend fun all(): List<SearchableRestaurant>
}
