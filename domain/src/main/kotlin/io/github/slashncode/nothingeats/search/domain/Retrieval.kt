package io.github.slashncode.nothingeats.search.domain

/**
 * A craving in words, usually rewritten by the Craving Coach's model: [text] feeds both legs of the
 * hybrid search, [keywords] weigh in the keyword leg only, and no dish may mention any of
 * [negativeKeywords] ("no mushrooms"). [semanticWeight] tilts the fusion: 0 is keywords only, 1 is
 * meaning only. Filters are hard: they apply to both legs.
 */
data class Retrieval(
    val text: String,
    val language: Language,
    val keywords: List<String> = emptyList(),
    val negativeKeywords: List<String> = emptyList(),
    val filters: DishFilters = DishFilters(),
    val semanticWeight: Double = DEFAULT_SEMANTIC_WEIGHT,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        if (text.length > MAX_TEXT) throw InvalidQuery("query", "must be at most $MAX_TEXT characters")
        checkWords("keywords", keywords)
        checkWords("negative_keywords", negativeKeywords)
        if (semanticWeight.isNaN() || semanticWeight !in 0.0..1.0) throw InvalidQuery("semantic_weight", "must be between 0 and 1")
        if (limit !in 1..MAX_LIMIT) throw InvalidQuery("limit", "must be between 1 and $MAX_LIMIT")
        if (filters.maxSpiceLevel != null &&
            filters.maxSpiceLevel !in 0..3
        ) {
            throw InvalidQuery("max_spice_level", "must be between 0 and 3")
        }
        if (filters.maxPriceCents != null && filters.maxPriceCents < 0) throw InvalidQuery("max_price_cents", "must not be negative")
    }

    /** Nothing to rank by: the filters alone decide, in a stable order. */
    val isBlank: Boolean get() = text.isBlank() && keywords.all { it.isBlank() }

    /** What the meaning leg embeds: the craving and the words the model added to it. */
    val semanticText: String get() = (listOf(text) + keywords).filter { it.isNotBlank() }.joinToString(". ")

    private fun checkWords(
        field: String,
        words: List<String>,
    ) {
        if (words.size > MAX_WORDS) throw InvalidQuery(field, "must be at most $MAX_WORDS")
        if (words.any { it.length > MAX_WORD }) throw InvalidQuery(field, "must each be at most $MAX_WORD characters")
    }

    companion object {
        /**
         * From `RetrievalQualityTest`: Recall@5 is 0.900 from 0.5 to 0.8 and peaks at 0.907 (one
         * craving) at 0.9; 0.7 sits in the middle of the plateau, so a small catalog change does
         * not tip it. Meaning carries cravings, keywords keep exact names on top.
         */
        const val DEFAULT_SEMANTIC_WEIGHT = 0.7
        const val DEFAULT_LIMIT = 8
        const val MAX_LIMIT = 20
        const val MAX_TEXT = 300
        const val MAX_WORDS = 10
        const val MAX_WORD = 40
    }
}

/** A dish in the fused ranking, with where each leg ranked it (1-based, null when it did not find it). */
data class RankedDish(
    val id: String,
    val score: Double,
    val lexicalRank: Int?,
    val semanticRank: Int?,
)

/**
 * Weighted reciprocal rank fusion. Every leg gives a dish `weight / (k + rank)`; ranks instead of
 * scores, so BM25 scores and cosine similarities never need a common scale. A dish only one leg
 * found still ranks, but below the dishes both agree on. A leg with weight 0 adds nothing, and
 * dishes only it found are left out.
 */
object RankFusion {
    const val K = 60

    fun fuse(
        lexical: List<String>,
        semantic: List<String>,
        semanticWeight: Double,
        k: Int = K,
    ): List<RankedDish> {
        val lexicalRanks = lexical.withIndex().associate { (index, id) -> id to index + 1 }
        val semanticRanks = semantic.withIndex().associate { (index, id) -> id to index + 1 }
        return (lexical + semantic)
            .distinct()
            .map { id ->
                val lexicalRank = lexicalRanks[id]
                val semanticRank = semanticRanks[id]
                val score =
                    (lexicalRank?.let { (1 - semanticWeight) / (k + it) } ?: 0.0) +
                        (semanticRank?.let { semanticWeight / (k + it) } ?: 0.0)
                RankedDish(id, score, lexicalRank, semanticRank)
            }.filter { it.score > 0.0 }
            .sortedWith(
                compareByDescending<RankedDish> { it.score }
                    .thenBy { it.lexicalRank ?: Int.MAX_VALUE }
                    .thenBy { it.id },
            )
    }
}

/**
 * The text a dish's embedding is computed from: both languages in one text, so German and English
 * cravings land near the same dishes, plus what the menu says between the lines (cuisine, moods,
 * diet, heat, calories). Changing it changes every vector: reindex, and re-record the test
 * embeddings (`RetrievalQualityTest`).
 */
fun SearchableDish.embeddingText(): String =
    buildString {
        appendLine("${name.en} / ${name.de}")
        appendLine(description.en)
        appendLine(description.de)
        appendLine("Menu section: ${section.en} / ${section.de}")
        appendLine("Restaurant: $restaurantName, $cuisine cuisine")
        if (moods.isNotEmpty()) appendLine("Moods: ${moods.sorted().joinToString(", ")}")
        if (dietary.isNotEmpty()) appendLine("Diet: ${dietary.sorted().joinToString(", ")}")
        appendLine("Spice level: $spiceLevel of 3")
        append("Calories: $calories kcal")
    }

/** Every cuisine, diet tag, mood and allergen the available dishes use, with how many use it. */
data class CatalogVocabulary(
    val cuisines: List<FacetValue>,
    val dietary: List<FacetValue>,
    val moods: List<FacetValue>,
    val allergens: List<FacetValue>,
)
