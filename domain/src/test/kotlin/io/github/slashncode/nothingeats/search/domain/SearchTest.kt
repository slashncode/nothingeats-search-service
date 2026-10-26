package io.github.slashncode.nothingeats.search.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalTime

class SearchTest :
    FunSpec({
        test("opening hours past midnight") {
            val restaurant = SearchableRestaurant("r1", "air-burger", "Air Burger", "american", LocalTime.of(18, 0), LocalTime.of(2, 0))

            restaurant.isOpenAt(LocalTime.of(23, 30)) shouldBe true
            restaurant.isOpenAt(LocalTime.of(1, 59)) shouldBe true
            restaurant.isOpenAt(LocalTime.of(2, 0)) shouldBe false
            restaurant.isOpenAt(LocalTime.of(12, 0)) shouldBe false
        }

        test("queries are bounded") {
            shouldThrow<InvalidQuery> { DishQuery("x".repeat(101), Language.DE) }.field shouldBe "q"
            shouldThrow<InvalidQuery> { DishQuery("", Language.DE, size = 51) }.field shouldBe "size"
            shouldThrow<InvalidQuery> { DishQuery("", Language.DE, DishFilters(maxSpiceLevel = 4)) }.field shouldBe "maxSpice"
        }

        test("languages from tags and headers, German by default") {
            Language.parse("en-GB,en;q=0.9") shouldBe Language.EN
            Language.parse("fr") shouldBe Language.DE
            Language.parse(null) shouldBe Language.DE
        }
    })
