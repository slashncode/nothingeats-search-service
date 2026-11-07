package io.github.slashncode.nothingeats.search.application

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.Duration.Companion.seconds

class RepairEmbeddingsTest :
    FunSpec({
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        afterSpec { scope.cancel() }

        test("dishes indexed while the model was down get their vectors once it is back") {
            val indices = InMemorySearchIndices()
            val model = FakeEmbeddingModel()
            val log =
                FakeCatalogLog(
                    listOf(CatalogEvent.RestaurantPublished(restaurant("phantom-pizza"), 0), CatalogEvent.DishPublished(dish("d1"), 0)),
                )
            val reindexing = Reindexing(Reindex(indices, log, DishVectors(model)), InMemoryReindexRuns(), indices, scope)
            val repair = RepairEmbeddings(indices, model, reindexing)

            // The model fails while the dish is indexed: it is searchable, without a vector.
            model.failing = true
            reindexing.runNow()
            indices.count().dishesWithoutVector shouldBe 1

            // Still down: nothing to gain from a reindex.
            repair().shouldBeNull()

            model.failing = false
            repair().shouldNotBeNull()
            eventually(5.seconds) { indices.count().dishesWithoutVector shouldBe 0 }

            // Nothing missing any more: no further run.
            repair().shouldBeNull()
        }
    })
