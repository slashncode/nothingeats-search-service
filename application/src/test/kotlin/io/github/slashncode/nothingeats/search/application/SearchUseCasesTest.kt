package io.github.slashncode.nothingeats.search.application

import io.github.slashncode.nothingeats.platform.core.analytics.AnalyticsContext
import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.testing.RecordingAnalytics
import io.github.slashncode.nothingeats.search.domain.DishFilters
import io.github.slashncode.nothingeats.search.domain.Language
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class SearchUseCasesTest :
    FunSpec({
        test("the first start creates and publishes a generation; later starts keep it") {
            val indices = InMemorySearchIndices()

            val first = EnsureLiveGeneration(indices)()
            val second = EnsureLiveGeneration(indices)()

            second shouldBe first
            indices.live() shouldBe first
        }

        test("projection keeps the newest version of each document") {
            val indices = InMemorySearchIndices()
            EnsureLiveGeneration(indices)()
            val project = ProjectCatalog(indices, DishVectors(FakeEmbeddingModel()))

            project(CatalogEvent.DishPublished(dish("d1").copy(priceCents = 990), version = 5))
            project(CatalogEvent.DishPublished(dish("d1").copy(priceCents = 1290), version = 3))

            (indices.generations.getValue(indices.liveName!!).getValue("dish:d1") as CatalogEvent.DishPublished).dish.priceCents shouldBe
                990
        }

        test("reindex replays, swaps, catches up what came in meanwhile, and drops the old generation") {
            val indices = InMemorySearchIndices()
            val old = EnsureLiveGeneration(indices)()
            val log =
                FakeCatalogLog(
                    listOf(
                        CatalogEvent.RestaurantPublished(restaurant("phantom-pizza"), 0),
                        CatalogEvent.DishPublished(dish("d1"), 0),
                        CatalogEvent.DishPublished(dish("d2"), 1),
                    ),
                )
            log.producedDuringReindex += CatalogEvent.DishPublished(dish("d3"), 2)

            val result = Reindex(indices, log, DishVectors(FakeEmbeddingModel()))()

            result.replaced shouldBe old
            result.events shouldBe 4
            indices.live() shouldBe result.generation
            indices.documents() shouldContainExactly listOf("dish:d1", "dish:d2", "dish:d3", "restaurant:phantom-pizza")
            indices.generations.keys shouldBe setOf(result.generation.name)
        }

        test("open now is decided in Berlin time and passed to the index as restaurant ids") {
            val index = RecordingDishIndex()
            val analytics = RecordingAnalytics()
            // 22:30 UTC on 24 Oct 2026 is 00:30 in Berlin.
            val clock = Clock.fixed(Instant.parse("2026-10-24T22:30:00Z"), ZoneId.of("UTC"))
            val restaurants = FixedRestaurants(listOf(restaurant("phantom-pizza"), restaurant("air-burger", "18:00", "02:00")))
            val search = SearchDishes(index, restaurants, analytics, clock)

            search(" Pizza ", Language.DE, DishFilters(openNow = true), page = 0, size = 20)
            search("pizza", Language.DE, DishFilters(), page = 0, size = 20)

            index.searches.map { it.second } shouldBe listOf(setOf("air-burger"), null)
            index.searches
                .first()
                .first.text shouldBe "Pizza"
        }

        test("every search is counted, with filters but without personal data") {
            val analytics = RecordingAnalytics()
            val search = SearchDishes(RecordingDishIndex(), FixedRestaurants(emptyList()), analytics)

            // The browser's distinct id arrives with the request (ktor-posthog puts it into the coroutine context).
            withContext(AnalyticsContext("visitor-1", "session-1")) {
                search("Vegane Pizza", Language.DE, DishFilters(dietary = setOf("vegan"), maxSpiceLevel = 1), page = 0, size = 20)
            }

            val event = analytics.events.last()
            event.name shouldBe "search_performed"
            event.distinctId shouldBe "visitor-1"
            event.properties["query"] shouldBe "vegane pizza"
            event.properties["results"] shouldBe 0L
            event.properties["filters"] shouldBe listOf("dietary", "spice")
        }

        test("invalid searches are 422 with the field") {
            val search = SearchDishes(RecordingDishIndex(), FixedRestaurants(emptyList()), RecordingAnalytics())

            shouldThrow<AppError.Invalid> { search("x", Language.DE, noFilters, page = 0, size = 500) }.violations.single().field shouldBe
                "size"
            SuggestDishes(RecordingDishIndex())("p", Language.DE) shouldBe emptyList()
        }
    })
