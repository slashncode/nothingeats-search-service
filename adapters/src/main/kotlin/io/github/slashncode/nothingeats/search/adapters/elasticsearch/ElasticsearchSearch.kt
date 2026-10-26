package io.github.slashncode.nothingeats.search.adapters.elasticsearch

import io.github.slashncode.nothingeats.search.application.CatalogEvent
import io.github.slashncode.nothingeats.search.application.DishIndex
import io.github.slashncode.nothingeats.search.application.Generation
import io.github.slashncode.nothingeats.search.application.RestaurantDirectory
import io.github.slashncode.nothingeats.search.application.SearchIndices
import io.github.slashncode.nothingeats.search.domain.DishFilters
import io.github.slashncode.nothingeats.search.domain.DishQuery
import io.github.slashncode.nothingeats.search.domain.DishResults
import io.github.slashncode.nothingeats.search.domain.FacetValue
import io.github.slashncode.nothingeats.search.domain.Facets
import io.github.slashncode.nothingeats.search.domain.Language
import io.github.slashncode.nothingeats.search.domain.SearchableRestaurant
import io.github.slashncode.nothingeats.search.domain.Suggestion
import io.github.slashncode.nothingeats.search.domain.SuggestionKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.time.Clock

/**
 * The search read model in Elasticsearch: two indices per generation (`dishes-<gen>`,
 * `restaurants-<gen>`) behind the aliases `dishes` and `restaurants`.
 *
 * Writes use the Kafka offset as external version, so a redelivered or older event never
 * overwrites a newer one. Bump [MAPPING_VERSION] with every mapping change and run `cron reindex`.
 */
class ElasticsearchSearch(
    private val client: ElasticsearchClient,
    private val clock: Clock = Clock.systemUTC(),
    /** Prefix of every index and alias; tests use their own, so they can share one Elasticsearch. */
    indexPrefix: String = "",
) : SearchIndices,
    DishIndex,
    RestaurantDirectory {
    private val json = Json { ignoreUnknownKeys = true }
    private val dishAlias = "${indexPrefix}dishes"
    private val restaurantAlias = "${indexPrefix}restaurants"

    override suspend fun live(): Generation? {
        val aliases = client.request("GET", "/_alias/$dishAlias", accept = setOf(NOT_FOUND))
        if (aliases.status == NOT_FOUND) return null
        val index =
            aliases.body!!
                .jsonObject.keys
                .single()
        return Generation(index.removePrefix("$dishAlias-"))
    }

    override suspend fun create(): Generation {
        val generation = Generation("v$MAPPING_VERSION-${clock.millis()}")
        client.request("PUT", "/${generation.dishes}", resource("dishes.json"))
        client.request("PUT", "/${generation.restaurants}", resource("restaurants.json"))
        return generation
    }

    override suspend fun write(
        event: CatalogEvent,
        into: Generation?,
    ) {
        val (index, id, document) =
            when (event) {
                is CatalogEvent.DishPublished ->
                    Triple(
                        into?.dishes ?: dishAlias,
                        event.dish.id,
                        json.encodeToString(DishDocument.serializer(), event.dish.document()),
                    )
                is CatalogEvent.RestaurantPublished ->
                    Triple(
                        into?.restaurants ?: restaurantAlias,
                        event.restaurant.id,
                        json.encodeToString(RestaurantDocument.serializer(), event.restaurant.document()),
                    )
            }
        // 409: the index holds this or a newer version already, which is exactly what we want.
        client.request(
            "PUT",
            "/$index/_doc/${encode(id)}?version=${event.version}&version_type=external",
            document,
            accept = setOf(CONFLICT),
        )
    }

    override suspend fun publish(generation: Generation): Generation? {
        val previous = live()
        val actions =
            buildJsonObject {
                putJsonArray("actions") {
                    previous?.let {
                        addJsonObject { putJsonObject("remove") { alias(it.dishes, dishAlias) } }
                        addJsonObject { putJsonObject("remove") { alias(it.restaurants, restaurantAlias) } }
                    }
                    addJsonObject { putJsonObject("add") { alias(generation.dishes, dishAlias) } }
                    addJsonObject { putJsonObject("add") { alias(generation.restaurants, restaurantAlias) } }
                }
            }
        client.request("POST", "/_aliases", actions.toString())
        return previous
    }

    override suspend fun drop(generation: Generation) {
        client.request("DELETE", "/${generation.dishes},${generation.restaurants}", accept = setOf(NOT_FOUND))
    }

    /** Makes recent writes searchable at once; for tests, production relies on the 1s refresh. */
    suspend fun refresh() {
        client.request("POST", "/$dishAlias,$restaurantAlias/_refresh")
    }

    override suspend fun all(): List<SearchableRestaurant> {
        val response = client.request("POST", "/$restaurantAlias/_search", """{"size": $MAX_RESTAURANTS, "query": {"match_all": {}}}""")
        return hits(response.body!!).map { json.decodeFromJsonElement(RestaurantDocument.serializer(), it).restaurant() }
    }

    override suspend fun search(
        query: DishQuery,
        openRestaurantIds: Set<String>?,
    ): DishResults {
        val facetFilters = facetFilters(query.filters)
        val body =
            buildJsonObject {
                put("from", query.page * query.size)
                put("size", query.size)
                put("track_total_hits", true)
                putJsonObject("query") {
                    putJsonObject("bool") {
                        put("must", textQuery(query))
                        // Hard filters, not facets: they narrow what the facets count, too.
                        putJsonArray("filter") {
                            if (openRestaurantIds != null) add(terms("restaurantId", openRestaurantIds))
                        }
                        putJsonArray("must_not") {
                            if (query.filters.excludeAllergens.isNotEmpty()) add(terms("allergens", query.filters.excludeAllergens))
                        }
                    }
                }
                // Facet filters narrow the hits after the aggregations ran (multi-select facets).
                putJsonObject("post_filter") { putJsonObject("bool") { put("filter", JsonArray(facetFilters.values.flatten())) } }
                putJsonObject("aggs") {
                    facet("cuisine", facetFilters, ignoring = "cuisine") { terms("cuisine") }
                    // Diet tags combine with AND, so their counts keep the own filter: "how many are left if I add this one".
                    facet("dietary", facetFilters, ignoring = null) { terms("dietary") }
                    facet("mood", facetFilters, ignoring = "mood") { terms("moods") }
                    facet("spiceLevel", facetFilters, ignoring = "spiceLevel") { terms("spiceLevel") }
                    facet("price", facetFilters, ignoring = "price") {
                        // Cumulative "up to" buckets, matching the maxPrice filter.
                        putJsonObject("range") {
                            put("field", "priceCents")
                            putJsonArray("ranges") {
                                PRICE_STEPS.forEach { step ->
                                    addJsonObject {
                                        put("key", step.toString())
                                        put("to", step + 1)
                                    }
                                }
                            }
                        }
                    }
                }
                putJsonArray("sort") {
                    // Without text, everything scores the same: a stable order by restaurant and dish.
                    if (query.text.isNotBlank()) add("_score")
                    addJsonObject { put("restaurantSlug", "asc") }
                    addJsonObject { put("id", "asc") }
                }
            }
        val response = client.request("POST", "/$dishAlias/_search", body.toString()).body!!.jsonObject
        val aggregations = response.getValue("aggregations").jsonObject
        return DishResults(
            dishes = hits(response).map { json.decodeFromJsonElement(DishDocument.serializer(), it).dish() },
            total =
                response
                    .getValue("hits")
                    .jsonObject
                    .getValue("total")
                    .jsonObject
                    .getValue("value")
                    .jsonPrimitive.long,
            facets =
                Facets(
                    cuisine = buckets(aggregations, "cuisine"),
                    dietary = buckets(aggregations, "dietary"),
                    mood = buckets(aggregations, "mood"),
                    spiceLevel = buckets(aggregations, "spiceLevel").sortedBy { it.value },
                    price = buckets(aggregations, "price"),
                ),
        )
    }

    override suspend fun suggest(
        prefix: String,
        language: Language,
        limit: Int,
    ): List<Suggestion> {
        val restaurants =
            client
                .request("POST", "/$dishAlias/_search", suggestion(prefix, "restaurantName.suggest", limit, collapse = "restaurantSlug"))
                .body!!
        val dishes = client.request("POST", "/$dishAlias/_search", suggestion(prefix, "name.${language.tag}.suggest", limit)).body!!
        val restaurantSuggestions =
            hits(restaurants).map {
                val document = json.decodeFromJsonElement(DishDocument.serializer(), it)
                Suggestion(document.restaurantName, SuggestionKind.RESTAURANT, document.restaurantSlug, document.restaurantSlug)
            }
        val dishSuggestions =
            hits(dishes).map {
                val dish = json.decodeFromJsonElement(DishDocument.serializer(), it).dish()
                Suggestion(dish.name.of(language), SuggestionKind.DISH, dish.id, dish.restaurantSlug)
            }
        return (restaurantSuggestions + dishSuggestions).distinctBy { it.kind to it.text.lowercase() }.take(limit)
    }

    private fun suggestion(
        prefix: String,
        field: String,
        limit: Int,
        collapse: String? = null,
    ) = buildJsonObject {
        put("size", limit)
        putJsonObject("query") {
            putJsonObject("multi_match") {
                put("query", prefix)
                put("type", "bool_prefix")
                putJsonArray("fields") {
                    add(field)
                    add("$field._2gram")
                    add("$field._3gram")
                }
            }
        }
        collapse?.let { putJsonObject("collapse") { put("field", it) } }
    }.toString()

    // Free text over name (most important), restaurant, menu section and description, fuzzy for
    // typos. Mood, diet and cuisine keys are matched through their text subfields, where the query
    // synonyms bridge German words to the English keys ("käsig" finds the mood "cheesy").
    private fun textQuery(query: DishQuery): JsonObject {
        if (query.text.isBlank()) return buildJsonObject { putJsonObject("match_all") {} }
        val lang = query.language.tag
        return buildJsonObject {
            putJsonObject("bool") {
                putJsonArray("should") {
                    addJsonObject {
                        putJsonObject("multi_match") {
                            put("query", query.text)
                            put("type", "best_fields")
                            putJsonArray("fields") {
                                add("name.$lang^3")
                                add("restaurantName^2")
                                add("section.$lang^1.5")
                                add("description.$lang")
                            }
                            put("fuzziness", "AUTO")
                            put("prefix_length", 1)
                        }
                    }
                    addJsonObject {
                        putJsonObject("multi_match") {
                            put("query", query.text)
                            put("type", "most_fields")
                            putJsonArray("fields") {
                                add("moods.$lang")
                                add("dietary.$lang")
                                add("cuisine.$lang")
                            }
                            put("boost", TAG_BOOST)
                        }
                    }
                }
                put("minimum_should_match", 1)
            }
        }
    }

    private fun facetFilters(filters: DishFilters): Map<String, List<JsonObject>> =
        buildMap {
            if (filters.cuisines.isNotEmpty()) put("cuisine", listOf(terms("cuisine", filters.cuisines)))
            // All of them: one term filter per diet tag.
            if (filters.dietary.isNotEmpty()) put("dietary", filters.dietary.map { term("dietary", it) })
            if (filters.moods.isNotEmpty()) put("mood", listOf(terms("moods", filters.moods)))
            filters.maxSpiceLevel?.let { put("spiceLevel", listOf(atMost("spiceLevel", it.toLong()))) }
            filters.maxPriceCents?.let { put("price", listOf(atMost("priceCents", it))) }
        }

    // A facet counts with every facet filter applied except its own (when [ignoring] names it).
    private fun JsonObjectBuilder.facet(
        name: String,
        filters: Map<String, List<JsonObject>>,
        ignoring: String?,
        aggregation: JsonObjectBuilder.() -> Unit,
    ) {
        putJsonObject(name) {
            putJsonObject("filter") {
                putJsonObject("bool") { put("filter", JsonArray(filters.filterKeys { it != ignoring }.values.flatten())) }
            }
            putJsonObject("aggs") { putJsonObject("values", aggregation) }
        }
    }

    private fun JsonObjectBuilder.terms(field: String) {
        putJsonObject("terms") {
            put("field", field)
            put("size", FACET_SIZE)
        }
    }

    private fun buckets(
        aggregations: JsonObject,
        name: String,
    ): List<FacetValue> =
        aggregations
            .getValue(name)
            .jsonObject
            .getValue("values")
            .jsonObject
            .getValue("buckets")
            .jsonArray
            .map {
                val bucket = it.jsonObject
                FacetValue(bucket.getValue("key").jsonPrimitive.content, bucket.getValue("doc_count").jsonPrimitive.long)
            }

    private fun hits(response: JsonElement): List<JsonElement> =
        response.jsonObject
            .getValue("hits")
            .jsonObject
            .getValue("hits")
            .jsonArray
            .map { it.jsonObject.getValue("_source") }

    private fun resource(name: String): String =
        ElasticsearchSearch::class.java.getResource("/elasticsearch/$name")?.readText() ?: error("Missing mapping $name")

    private fun encode(id: String) = URLEncoder.encode(id, Charsets.UTF_8)

    private val Generation.dishes get() = "$dishAlias-$name"
    private val Generation.restaurants get() = "$restaurantAlias-$name"

    private fun JsonObjectBuilder.alias(
        index: String,
        alias: String,
    ) {
        put("index", index)
        put("alias", alias)
    }

    private fun terms(
        field: String,
        values: Collection<String>,
        boost: Double? = null,
    ) = buildJsonObject {
        putJsonObject("terms") {
            putJsonArray(field) { values.forEach { add(it) } }
            boost?.let { put("boost", it) }
        }
    }

    private fun term(
        field: String,
        value: String,
    ) = buildJsonObject { putJsonObject("term") { put(field, value) } }

    private fun atMost(
        field: String,
        max: Long,
    ) = buildJsonObject { putJsonObject("range") { putJsonObject(field) { put("lte", max) } } }

    companion object {
        /** Bump with every change to `resources/elasticsearch/`, then run `cron reindex`. */
        const val MAPPING_VERSION = 1
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val FACET_SIZE = 30
        private const val TAG_BOOST = 2.0
        private const val MAX_RESTAURANTS = 1000
        private val PRICE_STEPS = listOf(800L, 1200L, 1500L, 2000L)
    }
}
