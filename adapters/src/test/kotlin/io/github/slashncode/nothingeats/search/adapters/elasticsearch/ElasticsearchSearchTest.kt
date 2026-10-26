package io.github.slashncode.nothingeats.search.adapters.elasticsearch

import io.github.slashncode.nothingeats.search.adapters.ElasticsearchTestServer
import io.github.slashncode.nothingeats.search.adapters.SeedCatalog
import io.github.slashncode.nothingeats.search.application.CatalogEvent
import io.github.slashncode.nothingeats.search.application.EnsureLiveGeneration
import io.github.slashncode.nothingeats.search.domain.DishFilters
import io.github.slashncode.nothingeats.search.domain.DishQuery
import io.github.slashncode.nothingeats.search.domain.Language
import io.github.slashncode.nothingeats.search.domain.SuggestionKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/** The seed catalog in a live generation of its own. */
private suspend fun seeded(): ElasticsearchSearch {
    val config = ElasticsearchTestServer.config()
    val search = ElasticsearchSearch(ElasticsearchClient(config), indexPrefix = config.indexPrefix)
    EnsureLiveGeneration(search)()
    SeedCatalog.events().forEach { search.write(it) }
    search.refresh()
    return search
}

class ElasticsearchSearchTest :
    FunSpec({
        test("an older version never overwrites a newer one") {
            val search = seeded()
            val dish = SeedCatalog.dishes.first()

            search.write(CatalogEvent.DishPublished(dish.copy(priceCents = 1), version = 1_000))
            search.write(CatalogEvent.DishPublished(dish.copy(priceCents = 2), version = 999))
            search.refresh()

            search
                .search(DishQuery(dish.name.de, Language.DE), null)
                .dishes
                .first { it.id == dish.id }
                .priceCents shouldBe 1
        }

        test("a new generation goes live in one step and the old one can be dropped") {
            val search = seeded()
            val old = search.live()!!
            val next = search.create()
            SeedCatalog.events().take(3).forEach { search.write(it, into = next) }

            search.publish(next) shouldBe old
            search.drop(old)
            search.refresh()

            search.live() shouldBe next
            search.all().map { it.slug } shouldBe SeedCatalog.restaurants.take(3).map { it.slug }
        }

        test("German compounds and synonyms: Pommes finds Trüffelpommes, fries finds them in English too") {
            val search = seeded()

            search.search(DishQuery("Pommes", Language.DE), null).dishes.map { it.slug } shouldContain "truffle-fries-of-tomorrow"
            search.search(DishQuery("Fritten", Language.DE), null).dishes.map { it.slug } shouldContain "truffle-fries-of-tomorrow"
            search
                .search(DishQuery("chips", Language.EN), null)
                .dishes
                .first()
                .slug shouldBe "truffle-fries-of-tomorrow"
        }

        test("typos are forgiven") {
            val search = seeded()

            search
                .search(DishQuery("Margarita", Language.DE), null)
                .dishes
                .first()
                .slug shouldBe "margherita-of-nothing"
        }

        test("facet filters narrow the hits but each facet still counts the alternatives") {
            val search = seeded()

            val results = search.search(DishQuery("", Language.EN, DishFilters(cuisines = setOf("italian"))), null)

            results.dishes.forAll { it.cuisine shouldBe "italian" }
            results.total shouldBe 6
            results.facets.cuisine.map { it.value } shouldContainAll listOf("italian", "indian", "japanese")
            results.facets.dietary
                .first { it.value == "vegetarian" }
                .count shouldBe 4
        }

        test("diet tags combine with AND, allergens exclude, price and spice are upper bounds") {
            val search = seeded()

            val results =
                search.search(
                    DishQuery(
                        "",
                        Language.EN,
                        DishFilters(
                            dietary = setOf("vegan", "gluten_free"),
                            excludeAllergens = setOf("soybeans"),
                            maxPriceCents = 800,
                            maxSpiceLevel = 0,
                        ),
                        size = 50,
                    ),
                    null,
                )

            results.total shouldBeGreaterThan 0
            results.dishes.forAll {
                it.dietary shouldContainAll listOf("vegan", "gluten_free")
                it.allergens shouldNotContain "soybeans"
                (it.priceCents <= 800) shouldBe true
                it.spiceLevel shouldBe 0
            }
            results.facets.price.map { it.value } shouldBe listOf("800", "1200", "1500", "2000")
        }

        test("open now restricts to the given restaurants") {
            val search = seeded()

            val results = search.search(DishQuery("", Language.DE, size = 50), setOf("r-air-burger"))

            results.dishes.map { it.restaurantSlug }.toSet() shouldBe setOf("air-burger")
        }

        test("suggestions complete dish and restaurant names, accents folded") {
            val search = seeded()

            val pho = search.suggest("pho", Language.DE, 8)
            pho.map { it.text } shouldContain "Phở Bò aus Nichts"

            val phantom = search.suggest("phan", Language.EN, 8)
            phantom.first { it.kind == SuggestionKind.RESTAURANT }.target shouldBe "phantom-pizza"
            phantom.filter { it.kind == SuggestionKind.DISH }.map { it.text } shouldContain "Falafel Phantom Wrap"
            phantom.map { it.text }.distinct().size shouldBe phantom.size
            search.suggest("zzzz", Language.EN, 8) shouldBe emptyList()
        }
    })
