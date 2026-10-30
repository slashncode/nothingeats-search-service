package io.github.slashncode.nothingeats.search.adapters.grpc

import io.github.slashncode.nothingeats.search.application.DescribeCatalog
import io.github.slashncode.nothingeats.search.application.GetDishes
import io.github.slashncode.nothingeats.search.application.RetrieveDishes
import io.github.slashncode.nothingeats.search.application.validQuery
import io.github.slashncode.nothingeats.search.domain.DishFilters
import io.github.slashncode.nothingeats.search.domain.FacetValue
import io.github.slashncode.nothingeats.search.domain.Language
import io.github.slashncode.nothingeats.search.domain.Retrieval
import io.github.slashncode.nothingeats.search.domain.SearchableDish
import io.github.slashncode.nothingeats.search.grpc.v1.DescribeCatalogRequest
import io.github.slashncode.nothingeats.search.grpc.v1.DescribeCatalogResponse
import io.github.slashncode.nothingeats.search.grpc.v1.Dish
import io.github.slashncode.nothingeats.search.grpc.v1.Filters
import io.github.slashncode.nothingeats.search.grpc.v1.GetDishesRequest
import io.github.slashncode.nothingeats.search.grpc.v1.GetDishesResponse
import io.github.slashncode.nothingeats.search.grpc.v1.RetrieveRequest
import io.github.slashncode.nothingeats.search.grpc.v1.RetrieveResponse
import io.github.slashncode.nothingeats.search.grpc.v1.SearchServiceGrpcKt
import io.github.slashncode.nothingeats.search.grpc.v1.VocabularyValue
import io.github.slashncode.nothingeats.search.grpc.v1.describeCatalogResponse
import io.github.slashncode.nothingeats.search.grpc.v1.dish
import io.github.slashncode.nothingeats.search.grpc.v1.getDishesResponse
import io.github.slashncode.nothingeats.search.grpc.v1.retrieveResponse
import io.github.slashncode.nothingeats.search.grpc.v1.retrievedDish
import io.github.slashncode.nothingeats.search.grpc.v1.vocabularyValue

/**
 * `nothingeats.search.v1.SearchService`, called by the assistant on the customer's behalf (their
 * token travels along; platform-grpc verifies it). Validation errors become INVALID_ARGUMENT with
 * the reason SEARCH_INVALID.
 */
class SearchGrpcService(
    private val retrieve: RetrieveDishes,
    private val getDishes: GetDishes,
    private val describe: DescribeCatalog,
) : SearchServiceGrpcKt.SearchServiceCoroutineImplBase() {
    override suspend fun retrieve(request: RetrieveRequest): RetrieveResponse {
        val language = Language.parse(request.language)
        val retrieval =
            validQuery {
                Retrieval(
                    text = request.query.trim(),
                    language = language,
                    keywords = request.keywordsList.map { it.trim() },
                    negativeKeywords = request.negativeKeywordsList.map { it.trim() },
                    filters = request.filters.domain(),
                    semanticWeight = if (request.hasSemanticWeight()) request.semanticWeight else Retrieval.DEFAULT_SEMANTIC_WEIGHT,
                    limit = if (request.hasLimit()) request.limit else Retrieval.DEFAULT_LIMIT,
                )
            }
        val result = retrieve(retrieval)
        return retrieveResponse {
            dishes +=
                result.dishes.map { retrieved ->
                    retrievedDish {
                        dish = retrieved.dish.message(language, retrieved.dish.restaurantId in result.openRestaurantIds)
                        score = retrieved.rank.score
                        lexicalRank = retrieved.rank.lexicalRank ?: 0
                        semanticRank = retrieved.rank.semanticRank ?: 0
                    }
                }
            semantic = result.semantic
        }
    }

    override suspend fun getDishes(request: GetDishesRequest): GetDishesResponse {
        val language = Language.parse(request.language)
        val found = getDishes(request.idsList)
        return getDishesResponse {
            dishes += found.dishes.map { it.message(language, it.restaurantId in found.openRestaurantIds) }
        }
    }

    override suspend fun describeCatalog(request: DescribeCatalogRequest): DescribeCatalogResponse {
        val vocabulary = describe()
        return describeCatalogResponse {
            cuisines += vocabulary.cuisines.map { it.message() }
            dietary += vocabulary.dietary.map { it.message() }
            moods += vocabulary.moods.map { it.message() }
            allergens += vocabulary.allergens.map { it.message() }
        }
    }

    private fun Filters.domain() =
        DishFilters(
            cuisines = cuisinesList.toSet(),
            dietary = dietaryList.toSet(),
            excludeAllergens = excludeAllergensList.toSet(),
            moods = moodsList.toSet(),
            maxSpiceLevel = if (hasMaxSpiceLevel()) maxSpiceLevel else null,
            maxPriceCents = if (hasMaxPriceCents()) maxPriceCents else null,
            openNow = openNow,
        )

    private fun SearchableDish.message(
        language: Language,
        open: Boolean,
    ): Dish {
        val source = this
        return dish {
            id = source.id
            restaurantId = source.restaurantId
            restaurantSlug = source.restaurantSlug
            restaurantName = source.restaurantName
            cuisine = source.cuisine
            slug = source.slug
            name = source.name.of(language)
            description = source.description.of(language)
            section = source.section.of(language)
            priceCents = source.priceCents
            dietary += source.dietary.sorted()
            allergens += source.allergens.sorted()
            spiceLevel = source.spiceLevel
            calories = source.calories
            moods += source.moods.sorted()
            available = source.available
            openNow = open
        }
    }

    private fun FacetValue.message(): VocabularyValue {
        val source = this
        return vocabularyValue {
            value = source.value
            dishes = source.count
        }
    }
}
