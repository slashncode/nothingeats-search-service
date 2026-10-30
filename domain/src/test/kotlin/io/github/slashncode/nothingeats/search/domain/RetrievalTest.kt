package io.github.slashncode.nothingeats.search.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class RetrievalTest :
    FunSpec({
        test("rank fusion puts what both legs agree on first, then weighs single findings by the leg's weight") {
            val fused = RankFusion.fuse(lexical = listOf("a", "b", "c"), semantic = listOf("d", "a"), semanticWeight = 0.5)

            fused.map { it.id } shouldContainExactly listOf("a", "d", "b", "c")
            fused.first().let { it.lexicalRank to it.semanticRank } shouldBe (1 to 2)
            fused[1].let { it.lexicalRank to it.semanticRank } shouldBe (null to 1)
        }

        test("a leg with weight zero adds nothing and its own findings are dropped") {
            RankFusion.fuse(listOf("a", "b"), listOf("c", "b"), semanticWeight = 0.0).map { it.id } shouldContainExactly listOf("a", "b")
            RankFusion.fuse(listOf("a", "b"), listOf("c", "b"), semanticWeight = 1.0).map { it.id } shouldContainExactly listOf("c", "b")
        }

        test("retrievals are validated with the field names of the gRPC contract") {
            shouldThrow<InvalidQuery> { Retrieval("x", Language.DE, semanticWeight = 1.5) }.field shouldBe "semantic_weight"
            shouldThrow<InvalidQuery> { Retrieval("x", Language.DE, limit = 21) }.field shouldBe "limit"
            shouldThrow<InvalidQuery> { Retrieval("x", Language.DE, keywords = List(11) { "k" }) }.field shouldBe "keywords"
            shouldThrow<InvalidQuery> { Retrieval("x", Language.DE, filters = DishFilters(maxSpiceLevel = 4)) }.field shouldBe
                "max_spice_level"
            Retrieval(" ", Language.DE, keywords = listOf("")).isBlank shouldBe true
            Retrieval("warm", Language.DE, keywords = listOf("", "Suppe")).semanticText shouldBe "warm. Suppe"
        }

        test("the embedding text has both languages and what the menu implies") {
            val dish =
                SearchableDish(
                    id = "d-1",
                    restaurantId = "r-1",
                    restaurantSlug = "void-vindaloo",
                    restaurantName = "Void Vindaloo",
                    cuisine = "indian",
                    slug = "chana",
                    section = Text("Currys", "Curries"),
                    name = Text("Chana Masala", "Chana Masala"),
                    description = Text("Kichererbsen in würziger Sauce", "Chickpeas in spiced sauce"),
                    priceCents = 1190,
                    dietary = setOf("vegan", "gluten_free"),
                    allergens = emptySet(),
                    spiceLevel = 2,
                    calories = 610,
                    moods = setOf("spicy", "comfort"),
                    available = true,
                )

            val text = dish.embeddingText()

            text shouldContain "Kichererbsen in würziger Sauce"
            text shouldContain "Chickpeas in spiced sauce"
            text shouldContain "Restaurant: Void Vindaloo, indian cuisine"
            text shouldContain "Moods: comfort, spicy"
            text shouldContain "Diet: gluten_free, vegan"
            text shouldContain "Spice level: 2 of 3"
        }
    })
