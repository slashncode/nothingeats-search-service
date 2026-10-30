package io.github.slashncode.nothingeats.search.adapters.elasticsearch

import io.github.slashncode.nothingeats.search.application.Candidates
import io.github.slashncode.nothingeats.search.application.CatalogEvent
import io.github.slashncode.nothingeats.search.application.DishIndex
import io.github.slashncode.nothingeats.search.application.DishVector
import io.github.slashncode.nothingeats.search.application.Generation
import io.github.slashncode.nothingeats.search.application.RestaurantDirectory
import io.github.slashncode.nothingeats.search.application.SearchIndices
import io.github.slashncode.nothingeats.search.domain.CatalogVocabulary
import io.github.slashncode.nothingeats.search.domain.DishFilters
import io.github.slashncode.nothingeats.search.domain.DishQuery
import io.github.slashncode.nothingeats.search.domain.DishResults
import io.github.slashncode.nothingeats.search.domain.FacetValue
import io.github.slashncode.nothingeats.search.domain.Facets
import io.github.slashncode.nothingeats.search.domain.Language
import io.github.slashncode.nothingeats.search.domain.Retrieval
import io.github.slashncode.nothingeats.search.domain.SearchableDish
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
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.time.Clock

/**
 * The search read model in Elasticsearch: two indices per generation (`dishes-<gen>`,
 * `restaurants-<gen>`) behind the aliases `dishes` and `restaurants`.
 *
 * Writes use the Kafka offset as external version, so a redelivered or older event never
 * overwrites a newer one. Bump [MAPPING_VERSION] with every mapping change and run `cron reindex`.
 *
 * Dishes carry their embedding in a `dense_vector` field of [vectorDimensions] dimensions (it comes
 * from the embedding model's configuration, so it is added to the mapping when a generation is
 * created). Searches never read it back.
 */
class ElasticsearchSearch(
    private val client: ElasticsearchClient,
    private val clock: Clock = Clock.systemUTC(),
    /** Prefix of every index and alias; tests use their own, so they can share one Elasticsearch. */
    indexPrefix: String = "",
    private val vectorDimensions: Int = DEFAULT_VECTOR_DIMENSIONS,
) : SearchIndices,
    DishIndex,
    RestaurantDirectory {
    private val log = LoggerFactory.getLogger(ElasticsearchSearch::class.java)
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
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
        client.request("PUT", "/${generation.dishes}", dishesMapping())
        client.request("PUT", "/${generation.restaurants}", resource("restaurants.json"))
        return generation
    }

    override suspend fun write(
        event: CatalogEvent,
        into: Generation?,
        vector: DishVector?,
    ) {
        when (event) {
            is CatalogEvent.DishPublished -> {
                val index = into?.dishes ?: dishAlias
                if (vector != null) {
                    val written = putDocument(index, event.dish.id, event.version, dish(event.dish, vector), BAD_REQUEST)
                    if (written.status != BAD_REQUEST) return
                    // A generation from before vectors (strict mapping) or with other dimensions:
                    // keep the dish searchable by words until `cron reindex` builds a matching one.
                    log.warn("{} does not take the vector of dish {}; indexed without it, run cron reindex", index, event.dish.id)
                }
                putDocument(index, event.dish.id, event.version, dish(event.dish, null))
            }
            is CatalogEvent.RestaurantPublished ->
                putDocument(
                    into?.restaurants ?: restaurantAlias,
                    event.restaurant.id,
                    event.version,
                    json.encodeToString(RestaurantDocument.serializer(), event.restaurant.document()),
                )
        }
    }

    private fun dish(
        dish: SearchableDish,
        vector: DishVector?,
    ) = json.encodeToString(DishDocument.serializer(), dish.document(vector))

    // 409: the index holds this or a newer version already, which is exactly what we want.
    private suspend fun putDocument(
        index: String,
        id: String,
        version: Long,
        document: String,
        vararg alsoAccept: Int,
    ) = client.request(
        "PUT",
        "/$index/_doc/${encode(id)}?version=$version&version_type=external",
        document,
        accept = setOf(CONFLICT) + alsoAccept.toSet(),
    )

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
                withoutVectors()
                putJsonObject("query") {
                    putJsonObject("bool") {
                        put("must", textQuery(query.text, query.language, prefixes = true))
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
        withoutVectors()
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
    private fun textQuery(
        text: String,
        language: Language,
        keywords: List<String> = emptyList(),
        minimumShouldMatch: String? = null,
        prefixes: Boolean = false,
    ): JsonObject {
        if (text.isBlank() && keywords.all { it.isBlank() }) return buildJsonObject { putJsonObject("match_all") {} }
        val lang = language.tag
        return buildJsonObject {
            putJsonObject("bool") {
                putJsonArray("should") {
                    addJsonObject {
                        putJsonObject("multi_match") {
                            put("query", text)
                            put("type", "best_fields")
                            putJsonArray("fields") {
                                add("name.$lang^3")
                                add("restaurantName^2")
                                add("section.$lang^1.5")
                                add("description.$lang")
                            }
                            // The best field counts fully, the others a little: a pizza (section
                            // "Pizza" at Phantom Pizza) beats the tiramisu that only shares the restaurant.
                            put("tie_breaker", TIE_BREAKER)
                            put("fuzziness", FUZZINESS)
                            put("prefix_length", 1)
                            minimumShouldMatch?.let { put("minimum_should_match", it) }
                        }
                    }
                    addJsonObject {
                        putJsonObject("multi_match") {
                            put("query", text)
                            put("type", "most_fields")
                            putJsonArray("fields") {
                                add("moods.$lang")
                                add("dietary.$lang")
                                add("cuisine.$lang")
                            }
                            put("boost", TAG_BOOST)
                        }
                    }
                    // A word start finds the whole word ("burg" → Burger, "Trüff" → Trüffelpommes): the
                    // last word as a prefix, the others whole, all in the same name. People type word
                    // starts into a search box; the coach's model writes whole words, so it goes without.
                    if (prefixes) {
                        addJsonObject {
                            putJsonObject("multi_match") {
                                put("query", text)
                                put("type", "bool_prefix")
                                put("operator", "and")
                                putJsonArray("fields") {
                                    listOf("name.$lang.suggest", "restaurantName.suggest").forEach { field ->
                                        add(field)
                                        add("$field._2gram")
                                        add("$field._3gram")
                                    }
                                }
                                put("boost", PREFIX_BOOST)
                            }
                        }
                    }
                    // The model's own words for the craving: dish names, ingredients.
                    keywords.filter { it.isNotBlank() }.forEach { keyword ->
                        addJsonObject {
                            putJsonObject("multi_match") {
                                put("query", keyword)
                                put("type", "best_fields")
                                putJsonArray("fields") {
                                    add("name.$lang^3")
                                    add("section.$lang^1.5")
                                    add("description.$lang")
                                }
                                put("fuzziness", FUZZINESS)
                                put("prefix_length", 1)
                                put("boost", KEYWORD_BOOST)
                            }
                        }
                    }
                }
                put("minimum_should_match", 1)
            }
        }
    }

    override suspend fun retrieve(
        retrieval: Retrieval,
        vector: DishVector?,
        openRestaurantIds: Set<String>?,
        candidates: Int,
    ): Candidates {
        // Hard filters for both legs: everything a facet would filter, plus "available" and "open now".
        val filters =
            facetFilters(retrieval.filters).values.flatten() +
                listOfNotNull(
                    term("available", true),
                    openRestaurantIds?.let { terms("restaurantId", it) },
                )
        val exclusions =
            listOfNotNull(
                retrieval.filters.excludeAllergens
                    .takeIf { it.isNotEmpty() }
                    ?.let { terms("allergens", it) },
            ) +
                retrieval.negativeKeywords.filter { it.isNotBlank() }.map { mentions(it) }
        val lexical =
            buildJsonObject {
                put("size", candidates)
                withoutVectors()
                putJsonObject("query") {
                    putJsonObject("bool") {
                        // A sentence matches only on most of its words: one stray word ("Abend") is noise
                        // the meaning leg handles better.
                        put("must", textQuery(retrieval.text, retrieval.language, retrieval.keywords, RETRIEVAL_MINIMUM_SHOULD_MATCH))
                        put("filter", JsonArray(filters))
                        put("must_not", JsonArray(exclusions))
                    }
                }
                putJsonArray("sort") {
                    if (!retrieval.isBlank) add("_score")
                    addJsonObject { put("restaurantSlug", "asc") }
                    addJsonObject { put("id", "asc") }
                }
            }
        val semantic =
            vector?.let {
                buildJsonObject {
                    put("size", candidates)
                    withoutVectors()
                    putJsonObject("knn") {
                        put("field", "embedding")
                        putJsonArray("query_vector") { it.values.forEach { value -> add(value) } }
                        put("k", candidates)
                        put("num_candidates", maxOf(KNN_NUM_CANDIDATES, candidates))
                        putJsonObject("filter") {
                            putJsonObject("bool") {
                                put("filter", JsonArray(filters + term("embeddingModel", it.model)))
                                put("must_not", JsonArray(exclusions))
                            }
                        }
                    }
                }
            }
        // Both legs in one round trip; each answers (or fails) on its own.
        val body =
            buildString {
                listOfNotNull(lexical, semantic).forEach { search ->
                    appendLine(buildJsonObject { put("index", dishAlias) })
                    appendLine(search)
                }
            }
        val responses =
            client
                .request("POST", "/_msearch", body, contentType = "application/x-ndjson")
                .body!!
                .jsonObject
                .getValue("responses")
                .jsonArray
                .map { it.jsonObject }
        val lexicalResponse = responses[0]
        lexicalResponse["error"]?.let {
            throw ElasticsearchException(lexicalResponse["status"]?.jsonPrimitive?.content?.toInt() ?: 0, it.toString())
        }
        val semanticResponse = responses.getOrNull(1)
        val semanticDishes =
            when {
                semanticResponse == null -> null
                semanticResponse["error"] != null -> {
                    // E.g. a generation from before vectors: words only until the reindex.
                    log.warn(
                        "The meaning leg failed; retrieving by keywords only: {}",
                        semanticResponse["error"].toString().take(MAX_LOGGED_ERROR),
                    )
                    null
                }
                else -> dishes(semanticResponse)
            }
        return Candidates(dishes(lexicalResponse), semanticDishes)
    }

    override suspend fun get(ids: List<String>): List<SearchableDish> {
        val body = buildJsonObject { putJsonArray("ids") { ids.forEach { add(it) } } }
        val found =
            client
                .request("POST", "/$dishAlias/_mget?_source_excludes=embedding", body.toString())
                .body!!
                .jsonObject
                .getValue("docs")
                .jsonArray
                .map { it.jsonObject }
                .filter { it["found"]?.jsonPrimitive?.content == "true" }
                .map { json.decodeFromJsonElement(DishDocument.serializer(), it.getValue("_source")).dish() }
                .associateBy { it.id }
        return ids.mapNotNull { found[it] }
    }

    override suspend fun vocabulary(): CatalogVocabulary {
        val body =
            buildJsonObject {
                put("size", 0)
                putJsonObject("query") { putJsonObject("bool") { putJsonArray("filter") { add(term("available", true)) } } }
                putJsonObject("aggs") {
                    listOf("cuisine", "dietary", "moods", "allergens").forEach { field ->
                        putJsonObject(field) {
                            putJsonObject("terms") {
                                put("field", field)
                                put("size", FACET_SIZE)
                            }
                        }
                    }
                }
            }
        val aggregations =
            client
                .request("POST", "/$dishAlias/_search", body.toString())
                .body!!
                .jsonObject
                .getValue("aggregations")
                .jsonObject

        fun values(name: String) =
            aggregations.getValue(name).jsonObject.getValue("buckets").jsonArray.map {
                val bucket = it.jsonObject
                FacetValue(bucket.getValue("key").jsonPrimitive.content, bucket.getValue("doc_count").jsonPrimitive.long)
            }
        return CatalogVocabulary(values("cuisine"), values("dietary"), values("moods"), values("allergens"))
    }

    // The keyword as a phrase in the dish's name, section or description, in either language.
    private fun mentions(keyword: String) =
        buildJsonObject {
            putJsonObject("multi_match") {
                put("query", keyword)
                put("type", "phrase")
                putJsonArray("fields") {
                    Language.entries.forEach { language ->
                        add("name.${language.tag}")
                        add("section.${language.tag}")
                        add("description.${language.tag}")
                    }
                }
            }
        }

    private fun dishes(response: JsonElement) = hits(response).map { json.decodeFromJsonElement(DishDocument.serializer(), it).dish() }

    // The vector field is for kNN only: a hit with it would be a thousand floats heavier.
    private fun JsonObjectBuilder.withoutVectors() {
        putJsonObject("_source") { putJsonArray("excludes") { add("embedding") } }
    }

    // The static mapping plus the vector field, whose dimensions come from the embedding model.
    private fun dishesMapping(): String {
        val mapping = Json.parseToJsonElement(resource("dishes.json")).jsonObject
        val mappings = mapping.getValue("mappings").jsonObject
        val properties = mappings.getValue("properties").jsonObject
        val embedding =
            buildJsonObject {
                put("type", "dense_vector")
                put("dims", vectorDimensions)
                put("index", true)
                put("similarity", "cosine")
            }
        val withVector = JsonObject(properties + ("embedding" to embedding))
        return JsonObject(mapping + ("mappings" to JsonObject(mappings + ("properties" to withVector)))).toString()
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

    private fun term(
        field: String,
        value: Boolean,
    ) = buildJsonObject { putJsonObject("term") { put(field, value) } }

    private fun atMost(
        field: String,
        max: Long,
    ) = buildJsonObject { putJsonObject("range") { putJsonObject(field) { put("lte", max) } } }

    companion object {
        /** Bump with every change to `resources/elasticsearch/` or the vector field, then run `cron reindex`. */
        const val MAPPING_VERSION = 4

        /** Matches the embedding model's default (`EMBEDDINGS__DIMENSIONS`). */
        const val DEFAULT_VECTOR_DIMENSIONS = 1024
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val KEYWORD_BOOST = 1.5
        private const val PREFIX_BOOST = 2.0
        private const val TIE_BREAKER = 0.3

        // No typos below five letters (there a word is more likely unfinished, and one edit reaches
        // too much: "marg" → "Magen"), one up to seven, two from eight.
        private const val FUZZINESS = "AUTO:5,8"
        private const val RETRIEVAL_MINIMUM_SHOULD_MATCH = "2<60%"
        private const val KNN_NUM_CANDIDATES = 100
        private const val MAX_LOGGED_ERROR = 300
        private const val FACET_SIZE = 30
        private const val TAG_BOOST = 2.0
        private const val MAX_RESTAURANTS = 1000
        private val PRICE_STEPS = listOf(800L, 1200L, 1500L, 2000L)
    }
}
