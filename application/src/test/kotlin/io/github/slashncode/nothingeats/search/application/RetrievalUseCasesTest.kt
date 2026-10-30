package io.github.slashncode.nothingeats.search.application

import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.search.domain.DishFilters
import io.github.slashncode.nothingeats.search.domain.Language
import io.github.slashncode.nothingeats.search.domain.Retrieval
import io.github.slashncode.nothingeats.search.domain.embeddingText
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class RetrievalUseCasesTest :
    FunSpec({
        // 12:00 in Berlin: phantom-pizza (11–23) is open, ice-cream (13–21) is not yet.
        val noon = Clock.fixed(Instant.parse("2026-10-30T11:00:00Z"), ZoneId.of("UTC"))
        val restaurants = FixedRestaurants(listOf(restaurant("phantom-pizza"), restaurant("ice-cream", "13:00", "21:00")))

        test("dishes are projected with their vector, restaurants without; a failing model leaves the dish without one") {
            val indices = InMemorySearchIndices()
            EnsureLiveGeneration(indices)()
            val model = FakeEmbeddingModel()
            val project = ProjectCatalog(indices, DishVectors(model))

            project(CatalogEvent.RestaurantPublished(restaurant("phantom-pizza"), 0))
            project(CatalogEvent.DishPublished(dish("d1"), 1))
            model.failing = true
            project(CatalogEvent.DishPublished(dish("d2"), 2))

            model.texts shouldContainExactly listOf(dish("d1").embeddingText())
            indices.vectors.keys shouldBe setOf("d1")
            indices.vectors.getValue("d1").model shouldBe "fake@3"
            indices.documents() shouldContainExactly listOf("dish:d1", "dish:d2", "restaurant:phantom-pizza")
        }

        test("a retrieval embeds the craving with its keywords, fuses both legs and tells which restaurants are open") {
            val index = RecordingDishIndex()
            index.candidates =
                Candidates(
                    lexical = listOf(dish("soup"), dish("pizza")),
                    semantic = listOf(dish("curry", "ice-cream"), dish("soup")),
                )
            val model = FakeEmbeddingModel()
            val retrieve = RetrieveDishes(index, restaurants, model, noon)

            val result = retrieve(Retrieval("etwas Warmes", Language.DE, keywords = listOf("Suppe")))

            model.texts shouldContainExactly listOf("etwas Warmes. Suppe")
            result.semantic shouldBe true
            // Both legs found the soup; the meaning leg weighs more by default, so the curry beats the pizza.
            result.dishes.map { it.dish.id } shouldContainExactly listOf("soup", "curry", "pizza")
            result.dishes
                .first()
                .rank
                .let { it.lexicalRank to it.semanticRank } shouldBe (1 to 2)
            result.openRestaurantIds shouldBe setOf("phantom-pizza")
            // Not asked for "open now": the index gets no restaurant restriction.
            index.retrievals
                .single()
                .third
                .shouldBeNull()
        }

        test("without the model it answers by keywords only, and filters alone need no model at all") {
            val index = RecordingDishIndex()
            index.candidates = Candidates(lexical = listOf(dish("pizza")), semantic = listOf(dish("curry")))
            val model = FakeEmbeddingModel().apply { failing = true }
            val retrieve = RetrieveDishes(index, restaurants, model, noon)

            val byWords = retrieve(Retrieval("Pizza", Language.DE))
            byWords.semantic shouldBe false
            byWords.dishes.map { it.dish.id } shouldContainExactly listOf("pizza")

            model.failing = false
            val byFilters = retrieve(Retrieval("", Language.DE, filters = DishFilters(dietary = setOf("vegan"), openNow = true)))
            byFilters.semantic shouldBe false
            model.texts shouldBe emptyList()
            index.retrievals.last().third shouldBe setOf("phantom-pizza")
        }

        test("dishes by id keep the order asked for; too many ids are refused") {
            val index = RecordingDishIndex()
            index.dishes += listOf(dish("a"), dish("b"))
            val get = GetDishes(index, restaurants, noon)

            get(listOf("b", "missing", "a", "b")).dishes.map { it.id } shouldContainExactly listOf("b", "a")
            shouldThrow<AppError.Invalid> { get((1..21).map { "d$it" }) }.violations.single().field shouldBe "ids"
        }
    })
