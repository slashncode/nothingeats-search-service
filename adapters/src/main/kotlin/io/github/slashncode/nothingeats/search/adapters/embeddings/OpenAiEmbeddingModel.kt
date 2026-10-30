package io.github.slashncode.nothingeats.search.adapters.embeddings

import io.github.slashncode.nothingeats.search.application.EmbeddingModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

class EmbeddingException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@Serializable
private data class EmbeddingRequest(
    val model: String,
    val input: List<String>,
    val dimensions: Int,
    @SerialName("encoding_format") val encodingFormat: String,
)

@Serializable
private data class EmbeddingResponse(
    val data: List<EmbeddingData>,
)

@Serializable
private data class EmbeddingData(
    val index: Int,
    val embedding: List<Float>,
)

/**
 * OpenAI's embeddings API over plain HTTP (one request per batch). Rate limits (429), server errors
 * and timeouts are retried with a growing pause; anything else fails at once. Error messages carry
 * OpenAI's status and message, never the key.
 */
class OpenAiEmbeddingModel(
    private val model: String,
    override val dimensions: Int,
    private val config: OpenAiConfig,
) : EmbeddingModel {
    override val id = "$model@$dimensions"

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(config.timeout.toJavaDuration()).build()
    private val key = config.apiKey?.value ?: error("OpenAI needs an API key")

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val body = json.encodeToString(EmbeddingRequest.serializer(), EmbeddingRequest(model, texts, dimensions, "float"))
        val request =
            HttpRequest
                .newBuilder(URI(config.baseUrl.trimEnd('/') + "/embeddings"))
                .timeout(config.timeout.toJavaDuration())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $key")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        var attempt = 1
        while (true) {
            val failure =
                try {
                    val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
                    val status = response.statusCode()
                    if (status in SUCCESS) return vectors(response.body(), texts.size)
                    val message = "OpenAI answered $status: ${response.body().take(MAX_MESSAGE)}"
                    if (status != TOO_MANY_REQUESTS && status < SERVER_ERROR) throw EmbeddingException(message)
                    message
                } catch (e: IOException) {
                    // Includes timeouts.
                    "OpenAI is unreachable: ${e.message}"
                }
            if (attempt >= config.maxAttempts) throw EmbeddingException(failure)
            delay(BACKOFF * attempt)
            attempt++
        }
    }

    private fun vectors(
        body: String,
        expected: Int,
    ): List<FloatArray> {
        val data = json.decodeFromString(EmbeddingResponse.serializer(), body).data.sortedBy { it.index }
        if (data.size != expected) throw EmbeddingException("OpenAI returned ${data.size} vectors for $expected texts")
        return data.map { item ->
            if (item.embedding.size !=
                dimensions
            ) {
                throw EmbeddingException("OpenAI returned ${item.embedding.size} dimensions, not $dimensions")
            }
            item.embedding.toFloatArray()
        }
    }

    private companion object {
        val SUCCESS = 200..299
        const val TOO_MANY_REQUESTS = 429
        const val SERVER_ERROR = 500
        const val MAX_MESSAGE = 300
        val BACKOFF = 500.milliseconds
    }
}
