package io.github.slashncode.nothingeats.search.adapters.embeddings

import com.sksamuel.hoplite.Secret
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

enum class EmbeddingProvider {
    /** Feature hashing: no key, no costs, no meaning. Local runs, tests and CI clusters. */
    HASHING,

    /** OpenAI's embeddings API; needs [OpenAiConfig.apiKey]. */
    OPENAI,
}

/**
 * The embedding model for dishes and retrieval queries. In the cluster: `EMBEDDINGS__PROVIDER=OPENAI`
 * and `EMBEDDINGS__OPENAI__API_KEY`. [dimensions] shortens OpenAI's vectors (text-embedding-3 models
 * are trained so that a prefix still works) and sizes the index's vector field.
 */
data class EmbeddingsConfig(
    val provider: EmbeddingProvider = EmbeddingProvider.HASHING,
    val model: String = "text-embedding-3-large",
    val dimensions: Int = 1024,
    val openai: OpenAiConfig = OpenAiConfig(),
    /** How long Valkey keeps a vector; the same text never costs twice in that time. */
    val cacheTtl: Duration = 30.days,
) {
    init {
        require(dimensions in 1..MAX_DIMENSIONS) { "EMBEDDINGS__DIMENSIONS must be between 1 and $MAX_DIMENSIONS" }
        require(provider != EmbeddingProvider.OPENAI || openai.apiKey != null) {
            "EMBEDDINGS__OPENAI__API_KEY is required with EMBEDDINGS__PROVIDER=OPENAI"
        }
    }

    private companion object {
        /** Elasticsearch's limit for an indexed dense_vector. */
        const val MAX_DIMENSIONS = 4096
    }
}

data class OpenAiConfig(
    val apiKey: Secret? = null,
    val baseUrl: String = "https://api.openai.com/v1",
    val timeout: Duration = 10.seconds,
    /** Including the first; only rate limits, server errors and timeouts are retried. */
    val maxAttempts: Int = 3,
)
