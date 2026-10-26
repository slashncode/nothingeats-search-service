package io.github.slashncode.nothingeats.search.adapters.messaging

import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.search.application.CatalogEvent
import io.github.slashncode.nothingeats.search.domain.SearchableDish
import io.github.slashncode.nothingeats.search.domain.SearchableRestaurant
import io.github.slashncode.nothingeats.search.domain.Text
import kotlinx.serialization.Serializable
import java.time.LocalTime

/** The catalog's topics. Configurable so tests get their own; the contract is the catalog's `docs/events.md`. */
data class CatalogTopics(
    val dishes: String = "catalog.dish.v1",
    val restaurants: String = "catalog.restaurant.v1",
) {
    val all get() = listOf(dishes, restaurants)
}

// Tolerant readers: only the fields search needs, unknown fields are ignored (EventJson).

@Serializable
internal data class TextV1(
    val de: String,
    val en: String,
)

@Serializable
internal data class RestaurantPublishedV1(
    val restaurantId: String,
    val slug: String,
    val name: String,
    val cuisine: String,
    val opensAt: String,
    val closesAt: String,
)

@Serializable
internal data class DishPublishedV1(
    val dishId: String,
    val restaurantId: String,
    val restaurantSlug: String,
    val restaurantName: String,
    val cuisine: String,
    val slug: String,
    val section: TextV1,
    val name: TextV1,
    val description: TextV1,
    val priceCents: Long,
    val dietary: List<String> = emptyList(),
    val allergens: List<String> = emptyList(),
    val spiceLevel: Int = 0,
    val calories: Int,
    val moods: List<String> = emptyList(),
    val available: Boolean = true,
)

private const val RESTAURANT_PUBLISHED = "nothingeats.catalog.restaurant.published.v1"
private const val DISH_PUBLISHED = "nothingeats.catalog.dish.published.v1"

private fun TextV1.text() = Text(de, en)

/** The catalog event at [offset], or null for event types search does not care about. */
internal fun CloudEvent.toCatalogEvent(offset: Long): CatalogEvent? =
    when (type) {
        DISH_PUBLISHED -> {
            val dish = decode(DishPublishedV1.serializer())
            CatalogEvent.DishPublished(
                SearchableDish(
                    id = dish.dishId,
                    restaurantId = dish.restaurantId,
                    restaurantSlug = dish.restaurantSlug,
                    restaurantName = dish.restaurantName,
                    cuisine = dish.cuisine,
                    slug = dish.slug,
                    section = dish.section.text(),
                    name = dish.name.text(),
                    description = dish.description.text(),
                    priceCents = dish.priceCents,
                    dietary = dish.dietary.toSet(),
                    allergens = dish.allergens.toSet(),
                    spiceLevel = dish.spiceLevel,
                    calories = dish.calories,
                    moods = dish.moods.toSet(),
                    available = dish.available,
                ),
                version = offset,
            )
        }
        RESTAURANT_PUBLISHED -> {
            val restaurant = decode(RestaurantPublishedV1.serializer())
            CatalogEvent.RestaurantPublished(
                SearchableRestaurant(
                    id = restaurant.restaurantId,
                    slug = restaurant.slug,
                    name = restaurant.name,
                    cuisine = restaurant.cuisine,
                    opensAt = LocalTime.parse(restaurant.opensAt),
                    closesAt = LocalTime.parse(restaurant.closesAt),
                ),
                version = offset,
            )
        }
        else -> null
    }
