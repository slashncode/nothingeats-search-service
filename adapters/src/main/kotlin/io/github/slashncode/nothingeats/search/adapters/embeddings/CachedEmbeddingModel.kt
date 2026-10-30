package io.github.slashncode.nothingeats.search.adapters.embeddings

import io.github.slashncode.nothingeats.platform.valkey.Valkey
import io.github.slashncode.nothingeats.search.application.EmbeddingModel
import io.lettuce.core.SetArgs
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Vectors in Valkey, keyed by model and a hash of the text: a reindex, a redelivered event or a
 * repeated craving costs no second call. Fails open: without Valkey every text goes to [model].
 */
class CachedEmbeddingModel(
    private val model: EmbeddingModel,
    private val valkey: Valkey,
    private val ttl: Duration,
    private val prefix: String = "search:embedding:",
) : EmbeddingModel by model {
    private val log = LoggerFactory.getLogger(CachedEmbeddingModel::class.java)

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val keys = texts.map(::key)
        val cached = safely("read") { valkey.commands().mget(*keys.toTypedArray()).await() }
        val vectors = keys.mapIndexed { index, _ -> cached?.getOrNull(index)?.getValueOrElse(null)?.let(::decode) }.toMutableList()
        val missing = vectors.indices.filter { vectors[it]?.size != model.dimensions }
        if (missing.isEmpty()) return vectors.map { it!! }
        val computed = model.embed(missing.map { texts[it] })
        missing.forEachIndexed { position, index -> vectors[index] = computed[position] }
        safely("write") {
            val commands = valkey.commands()
            missing.forEachIndexed {
                position,
                index,
                ->
                commands.set(keys[index], encode(computed[position]), SetArgs.Builder.ex(ttl.inWholeSeconds)).await()
            }
        }
        return vectors.map { it!! }
    }

    private fun key(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${model.id}\n$text".toByteArray())
        return prefix + digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun <T> safely(
        what: String,
        block: suspend () -> T,
    ): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not {} the embedding cache; going without", what, e)
            null
        }

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
