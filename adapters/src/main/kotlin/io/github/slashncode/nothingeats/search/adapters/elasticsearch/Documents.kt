package io.github.slashncode.nothingeats.search.adapters.elasticsearch

import io.github.slashncode.nothingeats.search.application.DishVector
import io.github.slashncode.nothingeats.search.domain.SearchableDish
import io.github.slashncode.nothingeats.search.domain.SearchableRestaurant
import io.github.slashncode.nothingeats.search.domain.Text
import kotlinx.serialization.Serializable
import java.time.LocalTime

@Serializable
internal data class TextDocument(
    val de: String,
    val en: String,
)

/** What a dish looks like in the `dishes` index (mapping: resources/elasticsearch/dishes.json). */
@Serializable
internal data class DishDocument(
    val id: String,
    val restaurantId: String,
    val restaurantSlug: String,
    val restaurantName: String,
    val cuisine: String,
    val slug: String,
    val section: TextDocument,
    val name: TextDocument,
    val description: TextDocument,
    val priceCents: Long,
    val dietary: List<String>,
    val allergens: List<String>,
    val spiceLevel: Int,
    val calories: Int,
    val moods: List<String>,
    val available: Boolean,
    /** The dish's embedding (never read back: searches exclude it from `_source`). */
    val embedding: List<Float>? = null,
    /** The vector space of [embedding]; the meaning leg only compares vectors of its own model. */
    val embeddingModel: String? = null,
)

@Serializable
internal data class RestaurantDocument(
    val id: String,
    val slug: String,
    val name: String,
    val cuisine: String,
    val opensAt: String,
    val closesAt: String,
)

private fun Text.document() = TextDocument(de, en)

private fun TextDocument.text() = Text(de, en)

internal fun SearchableDish.document(vector: DishVector? = null) =
    DishDocument(
        id = id,
        restaurantId = restaurantId,
        restaurantSlug = restaurantSlug,
        restaurantName = restaurantName,
        cuisine = cuisine,
        slug = slug,
        section = section.document(),
        name = name.document(),
        description = description.document(),
        priceCents = priceCents,
        dietary = dietary.sorted(),
        allergens = allergens.sorted(),
        spiceLevel = spiceLevel,
        calories = calories,
        moods = moods.sorted(),
        available = available,
        embedding = vector?.values?.toList(),
        embeddingModel = vector?.model,
    )

internal fun DishDocument.dish() =
    SearchableDish(
        id = id,
        restaurantId = restaurantId,
        restaurantSlug = restaurantSlug,
        restaurantName = restaurantName,
        cuisine = cuisine,
        slug = slug,
        section = section.text(),
        name = name.text(),
        description = description.text(),
        priceCents = priceCents,
        dietary = dietary.toSet(),
        allergens = allergens.toSet(),
        spiceLevel = spiceLevel,
        calories = calories,
        moods = moods.toSet(),
        available = available,
    )

internal fun SearchableRestaurant.document() = RestaurantDocument(id, slug, name, cuisine, opensAt.toString(), closesAt.toString())

internal fun RestaurantDocument.restaurant() =
    SearchableRestaurant(id, slug, name, cuisine, LocalTime.parse(opensAt), LocalTime.parse(closesAt))
