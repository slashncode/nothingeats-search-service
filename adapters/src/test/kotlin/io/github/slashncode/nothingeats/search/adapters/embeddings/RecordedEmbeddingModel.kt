package io.github.slashncode.nothingeats.search.adapters.embeddings

import com.sksamuel.hoplite.Secret
import io.github.slashncode.nothingeats.search.application.EmbeddingModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

@Serializable
private data class Recording(
    val model: String,
    val dimensions: Int,
    /** By SHA-256 of the text. */
    val vectors: Map<String, RecordedVector>,
)

@Serializable
private data class RecordedVector(
    val text: String,
    /** Little-endian float32, Base64. */
    val vector: String,
)

/**
 * Real embeddings, recorded once into [file]: quality tests run on them without a key, network or
 * costs, and give the same numbers every time. A text without a recording fails the test, unless
 * `RECORD_EMBEDDINGS=true` and `OPENAI_API_KEY` are set: then OpenAI embeds it and [save] writes
 * the file (commit it).
 */
class RecordedEmbeddingModel(
    private val file: File,
    private val model: String = "text-embedding-3-large",
    override val dimensions: Int = 1024,
) : EmbeddingModel {
    override val id = "$model@$dimensions"

    private val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    private val vectors: MutableMap<String, RecordedVector> =
        if (file.exists()) {
            json
                .decodeFromString(Recording.serializer(), file.readText())
                .also {
                    check(it.model == model && it.dimensions == dimensions) { "$file holds ${it.model}@${it.dimensions}, not $id" }
                }.vectors
                .toMutableMap()
        } else {
            mutableMapOf()
        }
    private val recorder =
        if (System.getenv("RECORD_EMBEDDINGS") == "true") {
            OpenAiEmbeddingModel(
                model,
                dimensions,
                OpenAiConfig(
                    Secret(
                        System.getenv("OPENAI_API_KEY") ?: error("Recording needs OPENAI_API_KEY"),
                    ),
                ),
            )
        } else {
            null
        }
    private var changed = false

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        val missing = texts.distinct().filter { hash(it) !in vectors }
        if (missing.isNotEmpty()) {
            val openAi =
                recorder
                    ?: throw AssertionError(
                        "No recorded embedding for \"${missing.first().take(60)}\" (and ${missing.size - 1} more). Record with: " +
                            "RECORD_EMBEDDINGS=true OPENAI_API_KEY=… ./gradlew :adapters:test --tests '*RetrievalQualityTest'",
                    )
            openAi.embed(missing).forEachIndexed { index, vector ->
                vectors[hash(missing[index])] =
                    RecordedVector(missing[index], encode(vector))
            }
            changed = true
        }
        return texts.map { decode(vectors.getValue(hash(it)).vector) }
    }

    /** Writes new recordings, sorted, so the file diffs well. */
    fun save() {
        if (!changed) return
        file.parentFile.mkdirs()
        file.writeText(json.encodeToString(Recording.serializer(), Recording(model, dimensions, vectors.toSortedMap())) + "\n")
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun encode(vector: FloatArray): String {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    private fun decode(value: String): FloatArray {
        val buffer = ByteBuffer.wrap(Base64.getDecoder().decode(value)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(buffer.remaining() / Float.SIZE_BYTES) { buffer.getFloat() }
    }
}
