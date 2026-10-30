package io.github.slashncode.nothingeats.search.adapters.embeddings

import io.github.slashncode.nothingeats.search.application.EmbeddingModel
import kotlin.math.sqrt

/**
 * Feature hashing as a stand-in model: every word (lower case, at least two letters) adds ±1 to one
 * of [dimensions] buckets, and the vector is scaled to length 1. Texts that share words come out
 * close, texts that share only a meaning do not. Deterministic, free and offline: for tests, local
 * runs and clusters without a key.
 */
class HashingEmbeddingModel(
    override val dimensions: Int,
) : EmbeddingModel {
    override val id = "hashing@$dimensions"

    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map(::vector)

    private fun vector(text: String): FloatArray {
        val values = FloatArray(dimensions)
        WORD
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 2 }
            .forEach { word ->
                val hash = word.hashCode()
                values[Math.floorMod(hash, dimensions)] += if (hash and SIGN_BIT != 0) -1f else 1f
            }
        val length = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        if (length > 0f) values.indices.forEach { values[it] /= length }
        return values
    }

    private companion object {
        val WORD = Regex("\\p{L}+")
        const val SIGN_BIT = 1 shl 30
    }
}
