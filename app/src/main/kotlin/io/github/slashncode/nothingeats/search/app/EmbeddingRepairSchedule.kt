package io.github.slashncode.nothingeats.search.app

import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import io.github.slashncode.nothingeats.search.application.RepairEmbeddings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Runs [RepairEmbeddings] every [interval] while the service serves (never in cron mode). */
class EmbeddingRepairSchedule(
    private val repair: RepairEmbeddings,
    private val scope: CoroutineScope,
    private val interval: Duration = 15.minutes,
) : LifecycleComponent {
    override val name = "embedding-repair"
    override val order = LifecycleOrder.TRAFFIC

    private val log = LoggerFactory.getLogger(EmbeddingRepairSchedule::class.java)
    private var job: Job? = null

    override suspend fun onReady() {
        job =
            scope.launch {
                while (isActive) {
                    delay(interval)
                    try {
                        repair()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("Embedding repair failed; next try in {}", interval, e)
                    }
                }
            }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
    }
}
