package io.github.slashncode.nothingeats.search.adapters.messaging

import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventPosition
import io.github.slashncode.nothingeats.platform.kafka.PositionedEventHandler
import io.github.slashncode.nothingeats.search.application.ProjectCatalog

/**
 * The live projection of one catalog topic. No inbox: the record offset is the document version,
 * so redeliveries and out-of-order writes are dropped by Elasticsearch itself.
 */
class CatalogEventHandler(
    override val topic: String,
    private val project: ProjectCatalog,
) : PositionedEventHandler {
    override suspend fun handleAt(
        event: CloudEvent,
        position: EventPosition,
    ) {
        event.toCatalogEvent(position.offset)?.let { project(it) }
    }
}
