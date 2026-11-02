package io.github.slashncode.nothingeats.search.adapters.admin

import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.auth.requireRole
import io.github.slashncode.nothingeats.search.application.ReindexRun
import io.github.slashncode.nothingeats.search.application.Reindexing
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable

@Serializable
data class ReindexRunResponse(
    val id: String,
    val startedAt: String,
    val finishedAt: String?,
    val events: Int?,
    val generation: String?,
    val replaced: String?,
    val failed: Boolean,
)

@Serializable
data class IndexStatusResponse(
    val live: String?,
    val dishes: Long,
    val restaurants: Long,
    val running: ReindexRunResponse?,
    val last: ReindexRunResponse?,
)

/**
 * `/api/search/v1/admin` (role `admin`, the backoffice): the live index and the reindex runs;
 * `POST /reindex` starts one (202) unless one runs already (409 `REINDEX_RUNNING`).
 */
class AdminSearchRoutes(
    private val reindexing: Reindexing,
) : HttpRoutes {
    override fun Route.register() {
        requireRole("admin") {
            route("/api/search/v1/admin") {
                get("/index") {
                    val status = reindexing.status()
                    call.respond(
                        IndexStatusResponse(
                            status.live?.name,
                            status.counts.dishes,
                            status.counts.restaurants,
                            status.running?.response(),
                            status.last?.response(),
                        ),
                    )
                }
                post("/reindex") { call.respond(HttpStatusCode.Accepted, reindexing.start().response()) }
            }
        }
    }

    private fun ReindexRun.response() =
        ReindexRunResponse(id, startedAt.toString(), finishedAt?.toString(), events, generation, replaced, failed)
}

/** Where reindexes started over HTTP run; cancelled when the service stops (the claim then times out). */
class BackgroundJobs : LifecycleComponent {
    override val name = "background-jobs"
    override val order = LifecycleOrder.APPLICATION

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun stop() = scope.cancel()
}
