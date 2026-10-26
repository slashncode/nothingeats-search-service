package io.github.slashncode.nothingeats.search.adapters

import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.search.application.CatalogEvent
import io.github.slashncode.nothingeats.search.domain.SearchableDish
import io.github.slashncode.nothingeats.search.domain.SearchableRestaurant
import io.github.slashncode.nothingeats.search.domain.Text
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalTime

@Serializable
private data class SeedText(
    val de: String,
    val en: String,
)

@Serializable
private data class SeedDish(
    val slug: String,
    val section: SeedText,
    val name: SeedText,
    val description: SeedText,
    val priceCents: Long,
    val dietary: List<String> = emptyList(),
    val allergens: List<String> = emptyList(),
    val spiceLevel: Int = 0,
    val calories: Int,
    val moods: List<String> = emptyList(),
)

@Serializable
private data class SeedRestaurant(
    val slug: String,
    val name: String,
    val cuisine: String,
    val opensAt: String,
    val closesAt: String,
    val dishes: List<SeedDish>,
)

private fun SeedText.text() = Text(de, en)

/**
 * The catalog service's seed (`seed/catalog.json`, a copy of the catalog's file), as search sees it.
 * Ids are readable: `r-<slug>` for restaurants, `d-<slug>` for dishes.
 */
object SeedCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    private val entries: List<SeedRestaurant> by lazy {
        val text = SeedCatalog::class.java.getResource("/seed/catalog.json")?.readText() ?: error("Seed is missing")
        json.decodeFromString<List<SeedRestaurant>>(text)
    }

    val restaurants: List<SearchableRestaurant> by lazy {
        entries.map {
            SearchableRestaurant("r-${it.slug}", it.slug, it.name, it.cuisine, LocalTime.parse(it.opensAt), LocalTime.parse(it.closesAt))
        }
    }

    val dishes: List<SearchableDish> by lazy {
        entries.flatMap { restaurant ->
            restaurant.dishes.map { dish ->
                SearchableDish(
                    id = "d-${dish.slug}",
                    restaurantId = "r-${restaurant.slug}",
                    restaurantSlug = restaurant.slug,
                    restaurantName = restaurant.name,
                    cuisine = restaurant.cuisine,
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
                    available = true,
                )
            }
        }
    }

    /** Every restaurant and dish as an event, versioned by position like offsets on a one-partition topic. */
    fun events(): List<CatalogEvent> =
        restaurants.mapIndexed { index, it -> CatalogEvent.RestaurantPublished(it, index.toLong()) } +
            dishes.mapIndexed { index, it -> CatalogEvent.DishPublished(it, index.toLong()) }

    fun restaurantEvent(restaurant: SearchableRestaurant): CloudEvent =
        cloudEvent(
            "nothingeats.catalog.restaurant.published.v1",
            restaurant.id,
            buildJsonObject {
                put("restaurantId", restaurant.id)
                put("slug", restaurant.slug)
                put("name", restaurant.name)
                putJsonObject("description") {
                    put("de", "")
                    put("en", "")
                }
                put("cuisine", restaurant.cuisine)
                put("rating", 4.5)
                put("deliveryMinutes", 30)
                put("deliveryFeeCents", 199)
                put("minimumOrderCents", 1000)
                put("latitude", 53.55)
                put("longitude", 9.99)
                put("opensAt", restaurant.opensAt.toString())
                put("closesAt", restaurant.closesAt.toString())
            },
        )

    fun dishEvent(dish: SearchableDish): CloudEvent =
        cloudEvent(
            "nothingeats.catalog.dish.published.v1",
            dish.id,
            buildJsonObject {
                put("dishId", dish.id)
                put("restaurantId", dish.restaurantId)
                put("restaurantSlug", dish.restaurantSlug)
                put("restaurantName", dish.restaurantName)
                put("cuisine", dish.cuisine)
                put("slug", dish.slug)
                putText("section", dish.section)
                putText("name", dish.name)
                putText("description", dish.description)
                put("priceCents", dish.priceCents)
                putJsonArray("dietary") { dish.dietary.sorted().forEach { add(it) } }
                putJsonArray("allergens") { dish.allergens.sorted().forEach { add(it) } }
                put("spiceLevel", dish.spiceLevel)
                put("calories", dish.calories)
                putJsonArray("moods") { dish.moods.sorted().forEach { add(it) } }
                put("available", dish.available)
                put("addedLater", "ignored by tolerant readers")
            },
        )

    private fun kotlinx.serialization.json.JsonObjectBuilder.putText(
        name: String,
        text: Text,
    ) = putJsonObject(name) {
        put("de", text.de)
        put("en", text.en)
    }

    private fun cloudEvent(
        type: String,
        subject: String,
        data: JsonObject,
    ) = CloudEvent(
        id = "$subject-${System.nanoTime()}",
        source = "/nothingeats/catalog",
        type = type,
        subject = subject,
        time = "2026-10-26T12:00:00Z",
        data = data,
    )
}
