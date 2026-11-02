package io.github.slashncode.nothingeats.search.application

import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class ReindexingTest :
    FunSpec({
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        afterSpec { scope.cancel() }

        fun setup(
            log: CatalogLog =
                FakeCatalogLog(
                    listOf(CatalogEvent.RestaurantPublished(restaurant("phantom-pizza"), 0), CatalogEvent.DishPublished(dish("d1"), 0)),
                ),
        ): Triple<Reindexing, InMemorySearchIndices, InMemoryReindexRuns> {
            val indices = InMemorySearchIndices()
            val runs = InMemoryReindexRuns()
            return Triple(Reindexing(Reindex(indices, log, DishVectors(FakeEmbeddingModel())), runs, indices, scope), indices, runs)
        }

        test("a run started over HTTP goes on in the background and reports how it ended") {
            val (reindexing, indices, runs) = setup()
            EnsureLiveGeneration(indices)()

            val started = reindexing.start()

            eventually(5.seconds) { runs.finished?.id shouldBe started.id }
            val status = reindexing.status()
            status.running.shouldBeNull()
            status.last?.events shouldBe 2
            status.last?.generation shouldBe status.live?.name
            status.counts shouldBe IndexCounts(dishes = 1, restaurants = 1)
        }

        test("only one run at a time, and a failed run lets go of its claim") {
            val (reindexing, _, runs) =
                setup(
                    object : CatalogLog {
                        override suspend fun replay(handle: suspend (CatalogEvent) -> Unit): LogPosition = error("Kafka is gone")

                        override suspend fun catchUp(
                            from: LogPosition,
                            handle: suspend (CatalogEvent) -> Unit,
                        ): LogPosition = from
                    },
                )
            runs.claimed.set(ReindexRun("other", Instant.now()))

            shouldThrow<AppError.Conflict> { reindexing.runNow() }.code.value shouldBe "REINDEX_RUNNING"

            runs.claimed.set(null)
            reindexing.runNow().failed shouldBe true
            runs.running().shouldBeNull()
        }
    })
