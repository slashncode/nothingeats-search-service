package io.github.slashncode.nothingeats.search.adapters.elasticsearch

import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthStatus
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

data class ElasticsearchConfig(
    /** The local cluster's Elasticsearch on the host; in the cluster `http://search-es-http:9200`. */
    val url: String = "http://localhost:19200",
    val username: String? = null,
    val password: Secret? = null,
    val timeout: Duration = 5.seconds,
    /** Prefix of every index and alias. Empty in the cluster (the role grants `dishes*` and `restaurants*`). */
    val indexPrefix: String = "",
)

class ElasticsearchException(
    val status: Int,
    body: String,
) : RuntimeException("Elasticsearch answered $status: ${body.take(MAX_BODY_IN_MESSAGE)}") {
    private companion object {
        const val MAX_BODY_IN_MESSAGE = 500
    }
}

/**
 * A thin client over Elasticsearch's REST API: requests are JSON written in Kotlin, answers are
 * parsed JSON. Statuses outside 2xx throw, unless the caller expects them ([accept]).
 */
class ElasticsearchClient(
    private val config: ElasticsearchConfig,
) : HealthCheck {
    override val name = "elasticsearch"

    private val http = HttpClient.newBuilder().connectTimeout(config.timeout.toJavaDuration()).build()
    private val authorization =
        config.username?.let { user ->
            "Basic " + Base64.getEncoder().encodeToString("$user:${config.password?.value.orEmpty()}".toByteArray())
        }

    data class Response(
        val status: Int,
        val body: JsonElement?,
    )

    suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        contentType: String = "application/json",
        accept: Set<Int> = emptySet(),
    ): Response {
        val request =
            HttpRequest
                .newBuilder(URI(config.url.trimEnd('/') + path))
                .timeout(config.timeout.toJavaDuration())
                .header("Content-Type", contentType)
                .apply { authorization?.let { header("Authorization", it) } }
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
                .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        val status = response.statusCode()
        if (status !in SUCCESS && status !in accept) throw ElasticsearchException(status, response.body())
        return Response(status, response.body().takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) })
    }

    override suspend fun check(): HealthStatus {
        val health =
            request("GET", "/_cluster/health")
                .body
                ?.jsonObject
                ?.get("status")
                ?.jsonPrimitive
                ?.content
        return if (health == "green" || health == "yellow") HealthStatus.UP else HealthStatus.down("cluster is $health")
    }

    private companion object {
        val SUCCESS = 200..299
    }
}
