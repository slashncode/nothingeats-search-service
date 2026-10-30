package io.github.slashncode.nothingeats.search.adapters.embeddings

import com.sksamuel.hoplite.Secret
import com.sun.net.httpserver.HttpServer
import io.github.slashncode.nothingeats.platform.testing.ValkeyTestServer
import io.github.slashncode.nothingeats.platform.valkey.Valkey
import io.github.slashncode.nothingeats.platform.valkey.ValkeyConfig
import io.github.slashncode.nothingeats.search.application.EmbeddingModel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A stand-in for OpenAI's API: answers [statuses] in turn (then 200), and records the requests. */
private class FakeOpenAi(
    vararg statuses: Int,
) : AutoCloseable {
    val requests = CopyOnWriteArrayList<Pair<String?, String>>()
    private val pending = ConcurrentLinkedQueue(statuses.toList())
    private val server =
        HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/v1/embeddings") { exchange ->
                val body = exchange.requestBody.readAllBytes().decodeToString()
                requests += exchange.requestHeaders.getFirst("Authorization") to body
                val status = pending.poll() ?: 200
                val answer =
                    if (status == 200) {
                        val request = Json.parseToJsonElement(body).jsonObject
                        val dimensions = request.getValue("dimensions").jsonPrimitive.int
                        // In reverse order: the client sorts by index.
                        val data =
                            request.getValue("input").jsonArray.indices.reversed().joinToString(",") { index ->
                                """{"index": $index, "embedding": [${List(dimensions) { "${index + 1}.0" }.joinToString(",")}]}"""
                            }
                        """{"data": [$data]}"""
                    } else {
                        """{"error": {"message": "status $status"}}"""
                    }
                val bytes = answer.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    val url = "http://localhost:${server.address.port}/v1"

    override fun close() = server.stop(0)
}

private fun openAi(url: String) =
    OpenAiEmbeddingModel("text-embedding-3-large", 4, OpenAiConfig(Secret("sk-test-secret"), url, timeout = 5.seconds, maxAttempts = 3))

class EmbeddingModelsTest :
    FunSpec({
        test("OpenAI: one request per batch with the shortened dimensions, vectors in input order") {
            FakeOpenAi().use { api ->
                val vectors = openAi(api.url).embed(listOf("Pommes", "Pizza"))

                vectors.map { it.toList() } shouldContainExactly listOf(List(4) { 1f }, List(4) { 2f })
                val (authorization, body) = api.requests.single()
                authorization shouldBe "Bearer sk-test-secret"
                body shouldContain "\"model\":\"text-embedding-3-large\""
                body shouldContain "\"dimensions\":4"
                body shouldContain "\"encoding_format\":\"float\""
            }
        }

        test("OpenAI: rate limits and server errors are retried, other errors fail at once and never show the key") {
            FakeOpenAi(429, 503).use { api ->
                openAi(api.url).embed(listOf("x")).single().size shouldBe 4
                api.requests.size shouldBe 3
            }
            FakeOpenAi(401).use { api ->
                val error = shouldThrow<EmbeddingException> { openAi(api.url).embed(listOf("x")) }
                error.message shouldContain "401"
                error.message shouldNotContain "sk-test-secret"
                api.requests.size shouldBe 1
            }
            FakeOpenAi(500, 500, 500).use { api ->
                shouldThrow<EmbeddingException> { openAi(api.url).embed(listOf("x")) }
                api.requests.size shouldBe 3
            }
        }

        test("hashing: unit vectors, the same text gives the same vector, shared words bring texts closer") {
            val model = HashingEmbeddingModel(256)
            val (fries, truffleFries, sorbet) = model.embed(listOf("crispy fries", "crispy truffle fries", "mango sorbet"))

            fun dot(
                a: FloatArray,
                b: FloatArray,
            ) = a.indices.sumOf { (a[it] * b[it]).toDouble() }

            sqrt(dot(fries, fries)) shouldBe (1.0 plusOrMinus 1e-6)
            model.embed(listOf("crispy fries")).single().toList() shouldBe fries.toList()
            (dot(fries, truffleFries) > dot(fries, sorbet)) shouldBe true
        }

        test("the cache answers repeated texts without the model, and without Valkey it simply asks the model") {
            val counting = CountingModel()
            val valkey = Valkey(ValkeyTestServer.config()).apply { start() }
            try {
                val cached = CachedEmbeddingModel(counting, valkey, 1.minutes, prefix = "test:${UUID.randomUUID()}:")

                cached.embed(listOf("a", "bb")).map { it.first() } shouldContainExactly listOf(1f, 2f)
                cached.embed(listOf("bb", "ccc")).map { it.first() } shouldContainExactly listOf(2f, 3f)

                counting.texts shouldContainExactly listOf("a", "bb", "ccc")
            } finally {
                valkey.stop()
            }

            val offline = Valkey(ValkeyConfig(port = 1))
            CachedEmbeddingModel(counting, offline, 1.minutes).embed(listOf("dddd")).single().first() shouldBe 4f
        }
    })

/** Vectors filled with the text's length; remembers every text it embedded. */
private class CountingModel : EmbeddingModel {
    override val id = "counting@2"
    override val dimensions = 2
    val texts = CopyOnWriteArrayList<String>()

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        this.texts += texts
        return texts.map { text -> FloatArray(dimensions) { text.length.toFloat() } }
    }
}
