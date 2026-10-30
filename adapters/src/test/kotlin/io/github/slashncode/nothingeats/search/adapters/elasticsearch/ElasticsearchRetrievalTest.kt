package io.github.slashncode.nothingeats.search.adapters.elasticsearch

import io.github.slashncode.nothingeats.search.adapters.ElasticsearchTestServer
import io.github.slashncode.nothingeats.search.adapters.SeedCatalog
import io.github.slashncode.nothingeats.search.adapters.embeddings.HashingEmbeddingModel
import io.github.slashncode.nothingeats.search.application.CatalogEvent
import io.github.slashncode.nothingeats.search.application.DishVector
import io.github.slashncode.nothingeats.search.application.DishVectors
import io.github.slashncode.nothingeats.search.application.EnsureLiveGeneration
import io.github.slashncode.nothingeats.search.domain.DishFilters
import io.github.slashncode.nothingeats.search.domain.Language
import io.github.slashncode.nothingeats.search.domain.Retrieval
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private const val DIMENSIONS = 64
private val model = HashingEmbeddingModel(DIMENSIONS)

private class Seeded(
    val search: ElasticsearchSearch,
    val client: ElasticsearchClient,
    val dishAlias: String,
)

/** The seed catalog with hashed vectors in a live generation of its own. */
private suspend fun seeded(dimensions: Int = DIMENSIONS): Seeded {
    val config = ElasticsearchTestServer.config()
    val client = ElasticsearchClient(config)
    val search = ElasticsearchSearch(client, indexPrefix = config.indexPrefix, vectorDimensions = dimensions)
    EnsureLiveGeneration(search)()
    val vectors = DishVectors(model)
    SeedCatalog.events().forEach { search.write(it, vector = vectors.of(it)) }
    search.refresh()
    return Seeded(search, client, "${config.indexPrefix}dishes")
}

private suspend fun vector(text: String) = DishVector(model.id, model.embed(listOf(text)).single())

class ElasticsearchRetrievalTest :
    FunSpec({
        test("both legs answer under the same hard filters") {
            val search = seeded().search
            val retrieval =
                Retrieval(
                    text = "crispy",
                    language = Language.EN,
                    negativeKeywords = listOf("onion"),
                    filters = DishFilters(dietary = setOf("vegan"), excludeAllergens = setOf("sesame"), maxPriceCents = 1000),
                )

            val candidates = search.retrieve(retrieval, vector("crispy"), openRestaurantIds = null, candidates = 30)

            val semantic = candidates.semantic.shouldNotBeNull()
            semantic.shouldNotBeEmpty()
            candidates.lexical.map { it.slug } shouldContain "spring-roll-spirits"
            (candidates.lexical + semantic).forAll {
                it.dietary shouldContain "vegan"
                it.allergens shouldNotContain "sesame"
                (it.priceCents <= 1000) shouldBe true
                it.name.en shouldNotContain "Onion"
            }
        }

        test("open restaurants and availability restrict both legs; dishes from other models are not compared") {
            val search = seeded().search
            val gone = SeedCatalog.dishes.first { it.slug == "mango-sorbet-mirage" }
            search.write(CatalogEvent.DishPublished(gone.copy(available = false), version = 1_000), vector = vector("mango"))
            search.refresh()

            val sweet =
                search.retrieve(
                    Retrieval("sorbet", Language.EN),
                    vector("Mango Sorbet"),
                    openRestaurantIds = setOf("r-imaginary-ice-cream"),
                    candidates = 30,
                )
            val found = sweet.lexical + sweet.semantic.shouldNotBeNull()
            found.forAll { it.restaurantSlug shouldBe "imaginary-ice-cream" }
            found.map { it.slug } shouldNotContain "mango-sorbet-mirage"

            val elsewhere =
                search.retrieve(
                    Retrieval("sorbet", Language.EN),
                    DishVector("other@64", FloatArray(DIMENSIONS) { 1f }),
                    null,
                    30,
                )
            elsewhere.semantic shouldBe emptyList()
        }

        test("an index that cannot take the vector still gets the dish, and its meaning leg fails alone") {
            // A generation with 32 dimensions, written to by a service that embeds with 64.
            val seeded = seeded(dimensions = 32)
            val search = seeded.search
            val dish = SeedCatalog.dishes.first { it.slug == "truffle-fries-of-tomorrow" }

            search.write(CatalogEvent.DishPublished(dish.copy(priceCents = 1), version = 1_000), vector = vector("fries"))
            search.refresh()

            search.get(listOf(dish.id)).single().priceCents shouldBe 1
            val candidates = search.retrieve(Retrieval("fries", Language.EN), vector("fries"), null, 30)
            candidates.semantic.shouldBeNull()
            candidates.lexical.map { it.slug } shouldContain dish.slug
            seeded.client
                .request("GET", "/${seeded.dishAlias}/_doc/${dish.id}")
                .body
                .toString() shouldNotContain "embeddingModel"
        }

        test("dishes by id come in the order asked; the vocabulary counts available dishes") {
            val search = seeded().search

            search.get(listOf("d-mochi-mirage", "d-unknown", "d-the-big-nothing")).map { it.slug } shouldContainExactly
                listOf("mochi-mirage", "the-big-nothing")
            val vocabulary = search.vocabulary()
            vocabulary.cuisines.map { it.value } shouldContain "italian"
            vocabulary.dietary.first { it.value == "vegan" }.count shouldBe SeedCatalog.dishes.count { "vegan" in it.dietary }.toLong()
            vocabulary.allergens.map { it.value } shouldContain "peanuts"
            vocabulary.moods.map { it.value } shouldContain "comfort"
        }
    })
