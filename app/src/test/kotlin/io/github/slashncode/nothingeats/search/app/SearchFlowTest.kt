package io.github.slashncode.nothingeats.search.app

import io.github.slashncode.nothingeats.platform.core.run.RunMode
import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.kafka.CloudEvent
import io.github.slashncode.nothingeats.platform.kafka.EventJson
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.platform.testing.KafkaTestCluster
import io.github.slashncode.nothingeats.platform.testing.TestTokens
import io.github.slashncode.nothingeats.search.adapters.ElasticsearchTestServer
import io.github.slashncode.nothingeats.search.adapters.SeedCatalog
import io.github.slashncode.nothingeats.search.adapters.messaging.CatalogTopics
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private fun freePort() = ServerSocket(0).use { it.localPort }

private fun Producer<String, ByteArray>.publish(
    topic: String,
    event: CloudEvent,
) {
    send(ProducerRecord(topic, event.subject, EventJson.encodeToString(CloudEvent.serializer(), event).toByteArray())).get()
}

private fun JsonObject.items() = getValue("items").jsonArray.map { it.jsonObject }

private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

/** Catalog events on Kafka in, search results over HTTP out — through the real composition root. */
class SearchFlowTest :
    FunSpec({
        val http = HttpClient.newHttpClient()

        fun get(url: String): Pair<Int, JsonObject> {
            val response = http.send(HttpRequest.newBuilder(URI(url)).GET().build(), BodyHandlers.ofString())
            return response.statusCode() to Json.parseToJsonElement(response.body()).jsonObject
        }

        test("the catalog becomes searchable, newer versions win, and a reindex swaps generations without losing anything") {
            val topics = CatalogTopics(KafkaTestCluster.topic("catalog-dish"), KafkaTestCluster.topic("catalog-restaurant"))
            KafkaTestCluster.createTopics("search-service.dlq")
            val port = freePort()
            val config =
                ServiceConfig(
                    http = HttpServerConfig(port = port),
                    elasticsearch = ElasticsearchTestServer.config(),
                    kafka = KafkaConfig(KafkaTestCluster.bootstrapServers),
                    catalogTopics = topics,
                    lifecycle = LifecycleConfig(drainDelay = Duration.ZERO),
                )
            val tokens = TestTokens()
            val service = ServiceComponent::class.create(config, tokens.jwtAuth)
            val base = "http://localhost:$port/api/search/v1"
            val fries = SeedCatalog.dishes.first { it.slug == "truffle-fries-of-tomorrow" }

            fun searchFries(query: String): JsonObject? = get(query).second.items().firstOrNull { it.text("slug") == fries.slug }

            service.lifecycle.start()
            try {
                KafkaClients.producer(config.kafka, "test").use { producer ->
                    SeedCatalog.restaurants.forEach { producer.publish(topics.restaurants, SeedCatalog.restaurantEvent(it)) }
                    SeedCatalog.dishes.forEach { producer.publish(topics.dishes, SeedCatalog.dishEvent(it)) }
                    producer.publish(topics.dishes, SeedCatalog.dishEvent(fries.copy(priceCents = 490)))
                }

                // The later event wins: the projection ends at 4.90, whatever order the writes ran in.
                val hit =
                    eventually(30.seconds) {
                        searchFries("$base/dishes?q=Pommes&lang=de")!!.also {
                            it.text("priceCents") shouldBe
                                "490"
                        }
                    }
                hit.text("name") shouldBe "Trüffelpommes von morgen"
                hit.getValue("restaurant").jsonObject.text("slug") shouldBe "air-burger"
                eventually(30.seconds) { get("$base/dishes?size=50").second.text("total") shouldBe SeedCatalog.dishes.size.toString() }

                val suggestions = get("$base/suggestions?q=${URLEncoder.encode("trüf", Charsets.UTF_8)}&lang=de").second.items()
                suggestions.first().text("text") shouldBe "Trüffelpommes von morgen"
                get("$base/dishes?size=500").first shouldBe 422

                val before = service.search.live()
                ServiceComponent::class.create(config, tokens.jwtAuth).runner.run(RunMode.Cron("reindex"))
                service.search.live() shouldNotBe before

                // After the swap the live projection writes into the new generation.
                KafkaClients.producer(config.kafka, "test").use { producer ->
                    producer.publish(topics.dishes, SeedCatalog.dishEvent(fries.copy(priceCents = 390)))
                }
                eventually(30.seconds) { searchFries("$base/dishes?q=fries&lang=en")!!.text("priceCents") shouldBe "390" }
                get("$base/dishes?size=50").second.text("total") shouldBe SeedCatalog.dishes.size.toString()
            } finally {
                service.lifecycle.stop()
            }
        }
    })
