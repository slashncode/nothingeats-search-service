package io.github.slashncode.nothingeats.search.adapters.elasticsearch

import io.github.slashncode.nothingeats.search.adapters.ElasticsearchTestServer
import io.github.slashncode.nothingeats.search.adapters.SeedCatalog
import io.github.slashncode.nothingeats.search.adapters.embeddings.RecordedEmbeddingModel
import io.github.slashncode.nothingeats.search.application.DishVectors
import io.github.slashncode.nothingeats.search.application.EnsureLiveGeneration
import io.github.slashncode.nothingeats.search.application.RestaurantDirectory
import io.github.slashncode.nothingeats.search.application.RetrieveDishes
import io.github.slashncode.nothingeats.search.domain.DishFilters
import io.github.slashncode.nothingeats.search.domain.Language
import io.github.slashncode.nothingeats.search.domain.Retrieval
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** A craving as a customer would say it, and every dish that would satisfy it. */
private data class Craving(
    val language: Language,
    val text: String,
    val relevant: List<String>,
    val filters: DishFilters = DishFilters(),
)

private const val K = 5

private val SPICY = SeedCatalog.dishes.filter { it.spiceLevel >= 2 }.map { it.slug }
private val SNACKS =
    listOf(
        "onion-rings-without-regret",
        "spring-roll-spirits",
        "garlic-ghost-bread",
        "truffle-fries-of-tomorrow",
        "edamame-echo",
        "garlic-naan-ghost",
        "tzatziki-in-theory",
    )
private val SEAFOOD =
    listOf(
        "salmon-nigiri-illusion",
        "dragon-roll-delusion",
        "spicy-tuna-specter",
        "spicy-shrimp-specter",
        "salmon-poke-placebo",
        "tuna-tataki-trick",
        "seaweed-salad-sigh",
    )
private val SWEETS = SeedCatalog.dishes.filter { "sweet" in it.moods }.map { it.slug }

// Cravings, not dish names: the words rarely appear on the menu, so the keyword leg alone struggles.
private val cravings =
    listOf(
        Craving(
            Language.DE,
            "Etwas Warmes für einen verregneten Abend",
            listOf(
                "pho-bo-of-nothing",
                "tofu-pho-phantom",
                "miso-soup-of-silence",
                "chana-masala-mirage",
                "butter-chicken-but-not",
                "palak-paneer-placebo",
                "lamb-vindaloo-from-the-void",
            ),
        ),
        Craving(
            Language.EN,
            "I'm hungover and need something greasy",
            listOf(
                "the-big-nothing",
                "gyros-plate-of-air",
                "truffle-fries-of-tomorrow",
                "crispy-chicken-cloud",
                "onion-rings-without-regret",
            ),
        ),
        Craving(Language.DE, "Ich habe Liebeskummer und brauche was Süßes", SWEETS),
        Craving(
            Language.EN,
            "something light and healthy after the gym",
            listOf(
                "salmon-nigiri-illusion",
                "avocado-maki-of-air",
                "seaweed-salad-sigh",
                "salmon-poke-placebo",
                "edamame-echo",
                "halloumi-haunt",
                "chicken-souvlaki-specter",
                "tuna-tataki-trick",
                "tofu-pho-phantom",
                "miso-soup-of-silence",
            ),
        ),
        Craving(
            Language.DE,
            "Es ist so heiß, ich will was Kaltes und Fruchtiges",
            listOf("mango-sorbet-mirage", "mango-lassi-of-longing", "coconut-water-of-want", "mochi-mirage"),
        ),
        Craving(Language.EN, "snacks to share on movie night", SNACKS),
        Craving(Language.DE, "Fingerfood zum Teilen", SNACKS),
        Craving(Language.EN, "something from the sea", SEAFOOD),
        Craving(Language.DE, "Ich hätte gern Fisch oder Meeresfrüchte", SEAFOOD),
        Craving(Language.DE, "ein cremiges Curry ohne Fleisch", listOf("palak-paneer-placebo", "chana-masala-mirage")),
        Craving(Language.EN, "a fresh Mediterranean salad", listOf("halloumi-haunt", "burrata-mirage", "seaweed-salad-sigh")),
        Craving(Language.DE, "Ich will es richtig scharf", SPICY),
        Craving(Language.EN, "noodle soup", listOf("pho-bo-of-nothing", "tofu-pho-phantom")),
        Craving(Language.EN, "a proper burger", listOf("the-big-nothing", "beyond-air-burger", "crispy-chicken-cloud")),
        // With filters, as the model would set them.
        Craving(
            Language.DE,
            "etwas Süßes",
            SeedCatalog.dishes.filter { "sweet" in it.moods && "vegan" in it.dietary }.map { it.slug },
            DishFilters(dietary = setOf("vegan")),
        ),
        Craving(
            Language.EN,
            "comfort food, nothing with nuts",
            SeedCatalog.dishes.filter { "comfort" in it.moods && "nuts" !in it.allergens && "peanuts" !in it.allergens }.map { it.slug },
            DishFilters(excludeAllergens = setOf("nuts", "peanuts")),
        ),
    )

// What people type into a search box (and what the model passes on when they name a dish): names,
// typos, synonyms. The keyword leg's home ground; the hybrid must not lose it.
private val names =
    listOf(
        Craving(Language.DE, "Trüffelpommes", listOf("truffle-fries-of-tomorrow")),
        Craving(Language.DE, "Margarita", listOf("margherita-of-nothing")),
        Craving(Language.DE, "vegane Pizza", listOf("vegan-vapor-pizza")),
        Craving(Language.DE, "Knoblauch", listOf("garlic-ghost-bread", "garlic-naan-ghost")),
        Craving(Language.DE, "Nudeln", listOf("dan-dan-daydream", "pho-bo-of-nothing", "bun-cha-breeze")),
        Craving(Language.DE, "Sushi", listOf("salmon-nigiri-illusion", "dragon-roll-delusion", "avocado-maki-of-air")),
        Craving(Language.EN, "chiken", listOf("chicken-souvlaki-specter", "butter-chicken-but-not", "crispy-chicken-cloud")),
        Craving(Language.EN, "vegan burger", listOf("beyond-air-burger")),
        Craving(Language.EN, "salmon", listOf("salmon-nigiri-illusion", "salmon-poke-placebo")),
        Craving(Language.EN, "chips", listOf("truffle-fries-of-tomorrow")),
        Craving(Language.EN, "tiramisu", listOf("tiramisu-of-tomorrow")),
        Craving(Language.EN, "souvlaki", listOf("chicken-souvlaki-specter")),
    )

private data class Scores(
    val recall: Double,
    val mrr: Double,
)

/**
 * Golden cravings against the seed catalog, with real `text-embedding-3-large` vectors recorded in
 * `src/test/resources/embeddings/` (no key needed). Compares keywords only, meaning only and the
 * hybrid at every weight; the report lands in `build/reports/retrieval-quality.md`. Tune
 * [Retrieval.DEFAULT_SEMANTIC_WEIGHT] and the embedding text against it.
 */
class RetrievalQualityTest :
    FunSpec({
        test("the hybrid finds what cravings mean, better than either leg alone") {
            val model = RecordedEmbeddingModel(File("src/test/resources/embeddings/text-embedding-3-large-1024.json"))
            val config = ElasticsearchTestServer.config()
            val search =
                ElasticsearchSearch(ElasticsearchClient(config), indexPrefix = config.indexPrefix, vectorDimensions = model.dimensions)
            EnsureLiveGeneration(search)()
            val vectors = DishVectors(model)
            SeedCatalog.events().forEach { search.write(it, vector = vectors.of(it)) }
            search.refresh()
            // Noon in Berlin; no craving asks for "open now", so the time only feeds the open flags.
            val noon = Clock.fixed(Instant.parse("2026-10-30T11:00:00Z"), ZoneId.of("UTC"))
            val retrieve = RetrieveDishes(search, search as RestaurantDirectory, model, noon)

            suspend fun top(
                craving: Craving,
                weight: Double,
            ) = retrieve(Retrieval(craving.text, craving.language, filters = craving.filters, semanticWeight = weight, limit = K))
                .dishes
                .map { it.dish.slug }

            suspend fun score(
                weight: Double,
                golden: List<Craving> = cravings + names,
            ): Pair<Scores, List<Pair<Craving, List<String>>>> {
                val rows = golden.map { it to top(it, weight) }
                val recall =
                    rows.map { (craving, top) ->
                        craving.relevant.count { it in top }.toDouble() / minOf(K, craving.relevant.size)
                    }
                val rr =
                    rows.map { (craving, top) ->
                        val rank = top.indexOfFirst { it in craving.relevant } + 1
                        if (rank == 0) 0.0 else 1.0 / rank
                    }
                return Scores(recall.average(), rr.average()) to rows
            }

            val weights = (0..10).map { it / 10.0 }
            val sweep = weights.associateWith { score(it) }
            val cravingSweep = weights.associateWith { score(it, cravings).first }
            val nameSweep = weights.associateWith { score(it, names).first }
            model.save()
            val lexical = sweep.getValue(0.0).first
            val semantic = sweep.getValue(1.0).first
            val (hybrid, rows) = score(Retrieval.DEFAULT_SEMANTIC_WEIGHT)

            File("build/reports").mkdirs()
            File("build/reports/retrieval-quality.md").writeText(
                buildString {
                    appendLine("# Retrieval quality (${model.id}, Recall@$K / MRR)")
                    appendLine()
                    appendLine("${cravings.size} cravings in the customer's words, ${names.size} dish names as typed into a search box.")
                    appendLine()
                    appendLine("| semantic weight | recall@$K | MRR | cravings recall@$K | names recall@$K |")
                    appendLine("|---|---|---|---|---|")
                    sweep.forEach { (weight, result) ->
                        val marker = if (weight == Retrieval.DEFAULT_SEMANTIC_WEIGHT) " (default)" else ""
                        appendLine(
                            "| %.1f$marker | %.3f | %.3f | %.3f | %.3f |".format(
                                Locale.ROOT,
                                weight,
                                result.first.recall,
                                result.first.mrr,
                                cravingSweep.getValue(weight).recall,
                                nameSweep.getValue(weight).recall,
                            ),
                        )
                    }
                    appendLine()
                    appendLine("## At the default weight")
                    appendLine()
                    appendLine("| lang | craving | top $K |")
                    appendLine("|---|---|---|")
                    rows.forEach { (craving, top) ->
                        appendLine(
                            "| ${craving.language.tag} | ${craving.text} | ${top.joinToString(
                                ", ",
                            ) { if (it in craving.relevant) "**$it**" else it }} |",
                        )
                    }
                },
            )

            // Filters hold, whatever the ranking does.
            rows.filter { it.first.filters != DishFilters() }.forAll { (craving, top) ->
                top.map { slug -> SeedCatalog.dishes.single { it.slug == slug } }.forAll { dish ->
                    dish.dietary shouldContainAll craving.filters.dietary
                    dish.allergens.intersect(craving.filters.excludeAllergens) shouldBe emptySet()
                }
            }
            hybrid.recall shouldBeGreaterThanOrEqual maxOf(lexical.recall, semantic.recall) - TOLERANCE
            hybrid.recall shouldBeGreaterThanOrEqual MIN_RECALL
            hybrid.mrr shouldBeGreaterThanOrEqual MIN_MRR
        }
    })

/** A hybrid may trail its better leg by a hair on a small set; it must not lose to it. */
private const val TOLERANCE = 0.02
private const val MIN_RECALL = 0.88
private const val MIN_MRR = 0.9
