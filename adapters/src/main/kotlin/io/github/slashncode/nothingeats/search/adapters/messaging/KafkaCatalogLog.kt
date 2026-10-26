package io.github.slashncode.nothingeats.search.adapters.messaging

import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventJson
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.search.application.CatalogEvent
import io.github.slashncode.nothingeats.search.application.CatalogLog
import io.github.slashncode.nothingeats.search.application.LogPosition
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Reads the catalog's compacted topics directly, without a consumer group: assigned partitions,
 * from the beginning (or a recorded position) up to the end offsets taken when reading starts.
 * It never commits, so it cannot disturb the live consumer group.
 */
class KafkaCatalogLog(
    private val config: KafkaConfig,
    private val topics: CatalogTopics = CatalogTopics(),
    private val pollTimeout: Duration = 1.seconds,
) : CatalogLog {
    private val log = LoggerFactory.getLogger(KafkaCatalogLog::class.java)

    /** The next offset to read, per partition. */
    data class Offsets(
        val next: Map<TopicPartition, Long>,
    ) : LogPosition

    override suspend fun replay(handle: suspend (CatalogEvent) -> Unit): LogPosition = read(from = null, handle)

    override suspend fun catchUp(
        from: LogPosition,
        handle: suspend (CatalogEvent) -> Unit,
    ): LogPosition = read(from as Offsets, handle)

    // KafkaConsumer is not thread-safe, so the whole read runs on one dedicated thread.
    private suspend fun read(
        from: Offsets?,
        handle: suspend (CatalogEvent) -> Unit,
    ): Offsets =
        Executors.newSingleThreadExecutor { Thread(it, "catalog-log") }.asCoroutineDispatcher().use { thread ->
            withContext(thread) {
                consumer().use { kafka ->
                    val partitions =
                        topics.all.flatMap { topic ->
                            kafka.partitionsFor(topic).map { TopicPartition(topic, it.partition()) }
                        }
                    kafka.assign(partitions)
                    if (from == null) {
                        kafka.seekToBeginning(partitions)
                    } else {
                        partitions.forEach { kafka.seek(it, from.next[it] ?: 0) }
                    }
                    val end = kafka.endOffsets(partitions)
                    while (partitions.any { kafka.position(it) < end.getValue(it) }) {
                        for (record in kafka.poll(pollTimeout.toJavaDuration())) {
                            // Compacted topics keep no deletes today, but a tombstone must not stop a reindex.
                            val value = record.value() ?: continue
                            val event = EventJson.decodeFromString(CloudEvent.serializer(), value.decodeToString())
                            event.toCatalogEvent(record.offset())?.let { handle(it) }
                        }
                    }
                    Offsets(partitions.associateWith { kafka.position(it) }).also {
                        log.info("Read the catalog log up to {}", it.next)
                    }
                }
            }
        }

    private fun consumer() =
        KafkaConsumer(
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to config.bootstrapServers,
                ConsumerConfig.CLIENT_ID_CONFIG to "search-service-reindex",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.ISOLATION_LEVEL_CONFIG to "read_committed",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG to MAX_POLL_RECORDS,
            ),
            StringDeserializer(),
            ByteArrayDeserializer(),
        )

    private companion object {
        const val MAX_POLL_RECORDS = 500
    }
}
