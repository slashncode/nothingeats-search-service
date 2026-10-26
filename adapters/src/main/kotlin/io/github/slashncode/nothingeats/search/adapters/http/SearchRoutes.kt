package io.github.slashncode.nothingeats.search.adapters.http

import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.auth.public
import io.github.slashncode.nothingeats.search.application.SearchDishes
import io.github.slashncode.nothingeats.search.application.SuggestDishes
import io.github.slashncode.nothingeats.search.domain.DishFilters
import io.github.slashncode.nothingeats.search.domain.DishQuery
import io.github.slashncode.nothingeats.search.domain.FacetValue
import io.github.slashncode.nothingeats.search.domain.Language
import io.github.slashncode.nothingeats.search.domain.SearchableDish
import io.github.slashncode.nothingeats.search.domain.Suggestion
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.acceptLanguage
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class DishRestaurantResponse(
    val slug: String,
    val name: String,
    val cuisine: String,
)

/** Shaped like the catalog's dish, plus where it comes from, so the web app renders the same DishCard. */
@Serializable
data class DishHitResponse(
    val id: String,
    val slug: String,
    val name: String,
    val description: String,
    val section: String,
    val priceCents: Long,
    val dietary: List<String>,
    val allergens: List<String>,
    val spiceLevel: Int,
    val calories: Int,
    val moods: List<String>,
    val available: Boolean,
    val restaurant: DishRestaurantResponse,
)

@Serializable
data class FacetValueResponse(
    val value: String,
    val count: Long,
)

@Serializable
data class FacetsResponse(
    val cuisine: List<FacetValueResponse>,
    val dietary: List<FacetValueResponse>,
    val mood: List<FacetValueResponse>,
    val spiceLevel: List<FacetValueResponse>,
    val price: List<FacetValueResponse>,
)

@Serializable
data class DishSearchResponse(
    val items: List<DishHitResponse>,
    val total: Long,
    val page: Int,
    val size: Int,
    val facets: FacetsResponse,
)

@Serializable
data class SuggestionResponse(
    val text: String,
    val kind: String,
    val target: String,
    val restaurantSlug: String,
)

@Serializable
data class SuggestionsResponse(
    val items: List<SuggestionResponse>,
)

/**
 * `/api/search/v1`, public. Multi-value filters repeat the parameter (`?diet=vegan&diet=gluten_free`)
 * or separate values with commas. The language comes from `?lang=`, then Accept-Language.
 */
class SearchRoutes(
    private val search: SearchDishes,
    private val suggest: SuggestDishes,
) : HttpRoutes {
    override fun Route.register() {
        route("/api/search/v1") {
            public {
                get("/dishes") {
                    val parameters = call.request.queryParameters
                    val language = call.language()
                    val page = parameters.int("page") ?: 0
                    val size = parameters.int("size") ?: DishQuery.DEFAULT_SIZE
                    val results =
                        search(
                            text = parameters["q"].orEmpty(),
                            language = language,
                            filters =
                                DishFilters(
                                    cuisines = parameters.values("cuisine"),
                                    dietary = parameters.values("diet"),
                                    excludeAllergens = parameters.values("excludeAllergen"),
                                    moods = parameters.values("mood"),
                                    maxSpiceLevel = parameters.int("maxSpice"),
                                    maxPriceCents = parameters["maxPrice"]?.let { it.toLongOrNull() ?: -1 },
                                    openNow = parameters["openNow"] == "true",
                                ),
                            page = page,
                            size = size,
                        )
                    call.respond(
                        DishSearchResponse(
                            items = results.dishes.map { it.toResponse(language) },
                            total = results.total,
                            page = page,
                            size = size,
                            facets =
                                FacetsResponse(
                                    cuisine = results.facets.cuisine.map { it.toResponse() },
                                    dietary = results.facets.dietary.map { it.toResponse() },
                                    mood = results.facets.mood.map { it.toResponse() },
                                    spiceLevel = results.facets.spiceLevel.map { it.toResponse() },
                                    price = results.facets.price.map { it.toResponse() },
                                ),
                        ),
                    )
                }
                get("/suggestions") {
                    val suggestions = suggest(call.request.queryParameters["q"].orEmpty(), call.language())
                    call.respond(SuggestionsResponse(suggestions.map { it.toResponse() }))
                }
            }
        }
    }

    private fun ApplicationCall.language(): Language {
        val language = Language.parse(request.queryParameters["lang"] ?: request.acceptLanguage())
        response.header(HttpHeaders.ContentLanguage, language.tag)
        response.header(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        return language
    }

    // Unparseable numbers are passed on as out of range, so the use case answers with a 422 naming the field.
    private fun Parameters.int(name: String): Int? = get(name)?.let { it.toIntOrNull() ?: Int.MIN_VALUE }

    private fun Parameters.values(name: String): Set<String> =
        getAll(name)
            .orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun SearchableDish.toResponse(language: Language) =
        DishHitResponse(
            id = id,
            slug = slug,
            name = name.of(language),
            description = description.of(language),
            section = section.of(language),
            priceCents = priceCents,
            dietary = dietary.sorted(),
            allergens = allergens.sorted(),
            spiceLevel = spiceLevel,
            calories = calories,
            moods = moods.sorted(),
            available = available,
            restaurant = DishRestaurantResponse(restaurantSlug, restaurantName, cuisine),
        )

    private fun FacetValue.toResponse() = FacetValueResponse(value, count)

    private fun Suggestion.toResponse() = SuggestionResponse(text, kind.name.lowercase(), target, restaurantSlug)
}
