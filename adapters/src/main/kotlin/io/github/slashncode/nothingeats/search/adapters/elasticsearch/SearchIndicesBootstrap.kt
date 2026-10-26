package io.github.slashncode.nothingeats.search.adapters.elasticsearch

import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleOrder
import io.github.slashncode.nothingeats.search.application.EnsureLiveGeneration
import org.slf4j.LoggerFactory

/** Makes sure a live generation exists before the event consumer starts writing into it. */
class SearchIndicesBootstrap(
    private val ensureLiveGeneration: EnsureLiveGeneration,
) : LifecycleComponent {
    override val name = "search-indices"
    override val order = LifecycleOrder.APPLICATION

    private val log = LoggerFactory.getLogger(SearchIndicesBootstrap::class.java)

    override suspend fun start() {
        log.info("Live search generation: {}", ensureLiveGeneration().name)
    }
}
