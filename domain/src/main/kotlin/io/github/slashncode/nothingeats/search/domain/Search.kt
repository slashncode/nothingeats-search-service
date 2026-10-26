package io.github.slashncode.nothingeats.search.domain

import java.time.LocalTime

enum class Language(
    val tag: String,
) {
    DE("de"),
    EN("en"),
    ;

    companion object {
        /** Accepts `de`, `en-GB` or an Accept-Language header; anything unknown is German. */
        fun parse(raw: String?): Language {
            val first =
                raw
                    ?.split(',')
                    ?.firstOrNull()
                    ?.trim()
                    ?.lowercase()
                    ?.take(2)
            return entries.firstOrNull { it.tag == first } ?: DE
        }
    }
}

/** Customer-facing text in both languages, as the catalog publishes it. */
data class Text(
    val de: String,
    val en: String,
) {
    fun of(language: Language) =
        when (language) {
            Language.DE -> de
            Language.EN -> en
        }
}

/**
 * A dish as search knows it: the snapshot from `catalog.dish.v1`, including the restaurant's slug,
 * name and cuisine at the time the dish changed (catalog record 024).
 */
data class SearchableDish(
    val id: String,
    val restaurantId: String,
    val restaurantSlug: String,
    val restaurantName: String,
    val cuisine: String,
    val slug: String,
    val section: Text,
    val name: Text,
    val description: Text,
    val priceCents: Long,
    val dietary: Set<String>,
    val allergens: Set<String>,
    val spiceLevel: Int,
    val calories: Int,
    val moods: Set<String>,
    val available: Boolean,
)

/** What search needs of a restaurant: enough for "open now". */
data class SearchableRestaurant(
    val id: String,
    val slug: String,
    val name: String,
    val cuisine: String,
    val opensAt: LocalTime,
    val closesAt: LocalTime,
) {
    /** Handles opening hours that run past midnight (e.g. 18:00–02:00), like the catalog. */
    fun isOpenAt(time: LocalTime): Boolean =
        if (opensAt <= closesAt) {
            time >= opensAt && time < closesAt
        } else {
            time >= opensAt || time < closesAt
        }
}

/** Filters combine with AND; inside one filter, values combine as noted. */
data class DishFilters(
    /** Any of these cuisines. */
    val cuisines: Set<String> = emptySet(),
    /** All of these, e.g. vegan and gluten_free. */
    val dietary: Set<String> = emptySet(),
    /** None of these allergens. */
    val excludeAllergens: Set<String> = emptySet(),
    /** Any of these moods. */
    val moods: Set<String> = emptySet(),
    val maxSpiceLevel: Int? = null,
    val maxPriceCents: Long? = null,
    val openNow: Boolean = false,
)

data class DishQuery(
    val text: String,
    val language: Language,
    val filters: DishFilters = DishFilters(),
    val page: Int = 0,
    val size: Int = DEFAULT_SIZE,
) {
    init {
        if (text.length > MAX_TEXT) throw InvalidQuery("q", "must be at most $MAX_TEXT characters")
        if (page !in 0..MAX_PAGE) throw InvalidQuery("page", "must be between 0 and $MAX_PAGE")
        if (size !in 1..MAX_SIZE) throw InvalidQuery("size", "must be between 1 and $MAX_SIZE")
        if (filters.maxSpiceLevel != null && filters.maxSpiceLevel !in 0..3) throw InvalidQuery("maxSpice", "must be between 0 and 3")
        if (filters.maxPriceCents != null && filters.maxPriceCents < 0) throw InvalidQuery("maxPrice", "must not be negative")
    }

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 50
        const val MAX_PAGE = 50
        const val MAX_TEXT = 100
    }
}

data class FacetValue(
    val value: String,
    val count: Long,
)

/**
 * Counts per filter value. Each facet counts with every *other* filter applied, so picking a
 * cuisine still shows how many dishes the other cuisines would have (multi-select facets).
 */
data class Facets(
    val cuisine: List<FacetValue>,
    val dietary: List<FacetValue>,
    val mood: List<FacetValue>,
    val spiceLevel: List<FacetValue>,
    val price: List<FacetValue>,
)

data class DishResults(
    val dishes: List<SearchableDish>,
    val total: Long,
    val facets: Facets,
)

enum class SuggestionKind { DISH, RESTAURANT }

data class Suggestion(
    val text: String,
    val kind: SuggestionKind,
    /** Dish id or restaurant slug. */
    val target: String,
    val restaurantSlug: String,
)

class InvalidQuery(
    val field: String,
    reason: String,
) : Exception(reason)
