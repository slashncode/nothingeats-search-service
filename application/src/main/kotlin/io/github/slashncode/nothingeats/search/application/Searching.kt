package io.github.slashncode.nothingeats.search.application

import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import io.github.slashncode.nothingeats.platform.core.error.Violation
import io.github.slashncode.nothingeats.search.domain.DishFilters
import io.github.slashncode.nothingeats.search.domain.DishQuery
import io.github.slashncode.nothingeats.search.domain.DishResults
import io.github.slashncode.nothingeats.search.domain.InvalidQuery
import io.github.slashncode.nothingeats.search.domain.Language
import io.github.slashncode.nothingeats.search.domain.Suggestion
import java.time.Clock
import java.time.LocalTime
import java.time.ZoneId

/**
 * Dish search with facets. "Open now" is decided here, in Berlin time, from the restaurants'
 * opening hours; the index only gets the ids. Every search is counted for analytics, including
 * zero-result ones, which show what customers miss in the catalog.
 */
class SearchDishes(
    private val index: DishIndex,
    private val restaurants: RestaurantDirectory,
    private val analytics: ProductAnalytics,
    private val clock: Clock = Clock.system(BERLIN),
) {
    suspend operator fun invoke(
        text: String,
        language: Language,
        filters: DishFilters,
        page: Int,
        size: Int,
    ): DishResults {
        val query = validQuery { DishQuery(text.trim(), language, filters, page, size) }
        val open = if (filters.openNow) restaurants.openAt(clock) else null
        val results = index.search(query, open)
        analytics.capture(
            "search_performed",
            mapOf(
                "query" to query.text.lowercase(),
                "language" to language.tag,
                "results" to results.total,
                "filters" to filters.used(),
                "page" to page,
            ),
        )
        return results
    }

    private fun DishFilters.used() =
        listOfNotNull(
            "cuisine".takeIf { cuisines.isNotEmpty() },
            "dietary".takeIf { dietary.isNotEmpty() },
            "allergens".takeIf { excludeAllergens.isNotEmpty() },
            "mood".takeIf { moods.isNotEmpty() },
            "spice".takeIf { maxSpiceLevel != null },
            "price".takeIf { maxPriceCents != null },
            "open_now".takeIf { openNow },
        )
}

internal val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/** The ids of the restaurants open right now, in Berlin time (opening hours may run past midnight). */
internal suspend fun RestaurantDirectory.openAt(clock: Clock): Set<String> {
    val now = LocalTime.now(clock.withZone(BERLIN))
    return all().filter { it.isOpenAt(now) }.map { it.id }.toSet()
}

/** Search-as-you-type over dish and restaurant names. */
class SuggestDishes(
    private val index: DishIndex,
) {
    suspend operator fun invoke(
        prefix: String,
        language: Language,
    ): List<Suggestion> {
        val trimmed = prefix.trim()
        if (trimmed.length < MIN_PREFIX) return emptyList()
        if (trimmed.length > DishQuery.MAX_TEXT) throw invalid("q", "must be at most ${DishQuery.MAX_TEXT} characters")
        return index.suggest(trimmed, language, LIMIT)
    }

    private companion object {
        const val MIN_PREFIX = 2
        const val LIMIT = 8
    }
}

/** Builds a query, turning the domain's [InvalidQuery] into the error every transport reports as 422 or INVALID_ARGUMENT. */
inline fun <T> validQuery(build: () -> T): T =
    try {
        build()
    } catch (e: InvalidQuery) {
        throw invalid(e.field, e.message ?: "invalid")
    }

@PublishedApi
internal fun invalid(
    field: String,
    reason: String,
) = AppError.Invalid(ErrorCode("SEARCH_INVALID"), "The search is invalid", listOf(Violation(field, reason)))
