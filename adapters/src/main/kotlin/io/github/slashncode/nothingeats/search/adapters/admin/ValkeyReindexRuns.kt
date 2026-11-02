package io.github.slashncode.nothingeats.search.adapters.admin

import io.github.slashncode.nothingeats.platform.valkey.Valkey
import io.github.slashncode.nothingeats.search.application.ReindexRun
import io.github.slashncode.nothingeats.search.application.ReindexRuns
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.time.Duration

@Serializable
private data class StoredRun(
    val id: String,
    val startedAt: String,
    val finishedAt: String? = null,
    val events: Int? = null,
    val generation: String? = null,
    val replaced: String? = null,
    val failed: Boolean = false,
)

/**
 * The claim is one key with a timeout (`SET NX PX`), holding the run's id and start; the last
 * finished run is another key. Letting go deletes the claim only if it is still this run's.
 */
class ValkeyReindexRuns(
    private val valkey: Valkey,
    private val prefix: String = "search:reindex:",
) : ReindexRuns {
    private val runningKey = "${prefix}running"
    private val lastKey = "${prefix}last"

    override suspend fun claim(
        run: ReindexRun,
        timeout: Duration,
    ): Boolean = valkey.commands().set(runningKey, run.claimValue(), SetArgs.Builder.nx().px(timeout.inWholeMilliseconds)).await() == "OK"

    override suspend fun finish(run: ReindexRun) {
        val commands = valkey.commands()
        commands.set(lastKey, Json.encodeToString(StoredRun.serializer(), run.stored())).await()
        commands
            .eval<Long>(RELEASE, ScriptOutputType.INTEGER, arrayOf(runningKey), run.claimValue())
            .await()
    }

    override suspend fun running(): ReindexRun? =
        valkey.commands().get(runningKey).await()?.let { value ->
            val (id, startedAt) = value.split('|', limit = 2)
            ReindexRun(id, Instant.parse(startedAt))
        }

    override suspend fun last(): ReindexRun? =
        valkey
            .commands()
            .get(lastKey)
            .await()
            ?.let { Json.decodeFromString(StoredRun.serializer(), it).run() }

    private fun ReindexRun.claimValue() = "$id|$startedAt"

    private fun ReindexRun.stored() = StoredRun(id, startedAt.toString(), finishedAt?.toString(), events, generation, replaced, failed)

    private fun StoredRun.run() =
        ReindexRun(id, Instant.parse(startedAt), finishedAt?.let(Instant::parse), events, generation, replaced, failed)

    private companion object {
        const val RELEASE = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
    }
}
