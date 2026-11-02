package io.github.slashncode.nothingeats.search.application

import io.github.slashncode.nothingeats.platform.core.error.AppError
import io.github.slashncode.nothingeats.platform.core.error.ErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** One reindex, as the backoffice shows it. */
data class ReindexRun(
    val id: String,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val events: Int? = null,
    val generation: String? = null,
    val replaced: String? = null,
    val failed: Boolean = false,
)

/** Who is reindexing right now and how the last run went, shared by every pod and `cron reindex`. */
interface ReindexRuns {
    /** Claims reindexing for [run], for at most [timeout]; false while another run holds it. */
    suspend fun claim(
        run: ReindexRun,
        timeout: Duration,
    ): Boolean

    /** Records how [run] ended and lets go of its claim. */
    suspend fun finish(run: ReindexRun)

    suspend fun running(): ReindexRun?

    suspend fun last(): ReindexRun?
}

data class IndexCounts(
    val dishes: Long,
    val restaurants: Long,
)

data class IndexStatus(
    val live: Generation?,
    val counts: IndexCounts,
    val running: ReindexRun?,
    val last: ReindexRun?,
)

/**
 * Reindexing on request (the backoffice, role `admin`) and from `cron reindex`, one run at a time
 * across pods: two runs would each swap in their generation and drop the other's. A request
 * answers at once and the run goes on in [scope]; a pod that dies mid-run frees the claim after
 * [timeout].
 */
class Reindexing(
    private val reindex: Reindex,
    private val runs: ReindexRuns,
    private val indices: SearchIndices,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val timeout: Duration = 15.minutes,
) {
    private val log = LoggerFactory.getLogger(Reindexing::class.java)

    suspend fun start(): ReindexRun {
        val run = claim()
        scope.launch { execute(run) }
        return run
    }

    /** `cron reindex`: the same run, in the foreground. */
    suspend fun runNow(): ReindexRun = execute(claim())

    suspend fun status(): IndexStatus = IndexStatus(indices.live(), indices.count(), runs.running(), runs.last())

    private suspend fun claim(): ReindexRun {
        val run = ReindexRun(UUID.randomUUID().toString(), clock.instant())
        if (!runs.claim(run, timeout)) throw AppError.Conflict(ErrorCode("REINDEX_RUNNING"), "A reindex is running already")
        return run
    }

    private suspend fun execute(run: ReindexRun): ReindexRun {
        val finished =
            try {
                val result = reindex()
                run.copy(
                    finishedAt = clock.instant(),
                    events = result.events,
                    generation = result.generation.name,
                    replaced = result.replaced?.name,
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable) { runs.finish(run.copy(finishedAt = clock.instant(), failed = true)) }
                throw e
            } catch (e: Exception) {
                log.error("Reindex {} failed", run.id, e)
                run.copy(finishedAt = clock.instant(), failed = true)
            }
        runs.finish(finished)
        return finished
    }
}
