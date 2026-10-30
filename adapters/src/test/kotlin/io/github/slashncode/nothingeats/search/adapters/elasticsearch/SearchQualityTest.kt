package io.github.slashncode.nothingeats.search.adapters.elasticsearch

import io.github.slashncode.nothingeats.search.adapters.ElasticsearchTestServer
import io.github.slashncode.nothingeats.search.adapters.SeedCatalog
import io.github.slashncode.nothingeats.search.application.EnsureLiveGeneration
import io.github.slashncode.nothingeats.search.domain.DishQuery
import io.github.slashncode.nothingeats.search.domain.Language
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.Locale

/** A query and every dish a person typing it would want to see. */
private data class Golden(
    val language: Language,
    val query: String,
    val relevant: List<String>,
)

private const val K = 5

private val SPICY =
    listOf("dan-dan-daydream", "lamb-vindaloo-from-the-void", "spicy-shrimp-specter", "spicy-tuna-specter", "diavola-invisibile")

private val ICE_CREAM =
    listOf(
        "pistachio-dream",
        "salted-caramel-specter",
        "dark-chocolate-void",
        "mango-sorbet-mirage",
        "cookie-dough-cloud",
        "banana-split-of-nothing",
    )

private val ITALIAN =
    listOf(
        "margherita-of-nothing",
        "diavola-invisibile",
        "vegan-vapor-pizza",
        "garlic-ghost-bread",
        "burrata-mirage",
        "tiramisu-of-tomorrow",
    )

private fun withMood(mood: String) = SeedCatalog.dishes.filter { mood in it.moods }.map { it.slug }

private val golden =
    listOf(
        Golden(Language.DE, "Pommes", listOf("truffle-fries-of-tomorrow", "gyros-plate-of-air")),
        Golden(Language.DE, "Trüffelpommes", listOf("truffle-fries-of-tomorrow")),
        Golden(Language.DE, "Pizza", listOf("margherita-of-nothing", "diavola-invisibile", "vegan-vapor-pizza")),
        Golden(Language.DE, "vegane Pizza", listOf("vegan-vapor-pizza")),
        Golden(Language.DE, "Margarita", listOf("margherita-of-nothing")),
        Golden(Language.DE, "Knoblauch", listOf("garlic-ghost-bread", "garlic-naan-ghost")),
        Golden(Language.DE, "Hähnchen", listOf("chicken-souvlaki-specter", "butter-chicken-but-not", "crispy-chicken-cloud")),
        Golden(Language.DE, "Nudeln", listOf("dan-dan-daydream", "pho-bo-of-nothing", "bun-cha-breeze")),
        Golden(Language.DE, "scharf", SPICY),
        Golden(Language.DE, "Eis", ICE_CREAM),
        Golden(Language.DE, "käsig", listOf("margherita-of-nothing", "the-big-nothing", "diavola-invisibile")),
        Golden(Language.DE, "Seelenfutter", withMood("comfort")),
        Golden(Language.DE, "zum Teilen", withMood("sharable")),
        Golden(Language.DE, "italienisch", ITALIAN),
        Golden(Language.DE, "Sushi", listOf("salmon-nigiri-illusion", "dragon-roll-delusion", "avocado-maki-of-air")),
        Golden(Language.DE, "Lachs", listOf("salmon-nigiri-illusion", "salmon-poke-placebo")),
        Golden(Language.EN, "fries", listOf("truffle-fries-of-tomorrow", "gyros-plate-of-air")),
        Golden(Language.EN, "chips", listOf("truffle-fries-of-tomorrow")),
        Golden(Language.EN, "noodles", listOf("dan-dan-daydream", "pho-bo-of-nothing", "bun-cha-breeze")),
        Golden(Language.EN, "vegan burger", listOf("beyond-air-burger")),
        Golden(Language.EN, "garlic", listOf("garlic-ghost-bread", "garlic-naan-ghost")),
        Golden(Language.EN, "spicy", SPICY),
        Golden(Language.EN, "cheesy", listOf("margherita-of-nothing", "the-big-nothing", "diavola-invisibile")),
        Golden(Language.EN, "ice cream", ICE_CREAM),
        Golden(Language.EN, "chiken", listOf("chicken-souvlaki-specter", "butter-chicken-but-not", "crispy-chicken-cloud")),
        Golden(Language.EN, "salmon", listOf("salmon-nigiri-illusion", "salmon-poke-placebo")),
        Golden(Language.EN, "pizza", listOf("margherita-of-nothing", "diavola-invisibile", "vegan-vapor-pizza")),
        // Word starts, as people type them before they finish (or give up).
        Golden(Language.EN, "burg", listOf("beyond-air-burger", "the-big-nothing", "crispy-chicken-cloud")),
        Golden(Language.EN, "marg", listOf("margherita-of-nothing")),
        Golden(Language.DE, "marg", listOf("margherita-of-nothing")),
        Golden(Language.EN, "pist", listOf("pistachio-dream")),
        Golden(Language.EN, "chick", listOf("chicken-souvlaki-specter", "butter-chicken-but-not", "crispy-chicken-cloud")),
        Golden(Language.DE, "Trüff", listOf("truffle-fries-of-tomorrow")),
        Golden(Language.DE, "Hähn", listOf("chicken-souvlaki-specter", "butter-chicken-but-not", "crispy-chicken-cloud")),
    )

/**
 * Golden queries against the seed catalog: Recall@5 (how much of the top five is relevant, out of
 * what could be) and MRR (how high the first relevant dish ranks). The report lands in
 * `build/reports/search-quality.md`; tune analyzers and boosts against it, not by feel.
 */
class SearchQualityTest :
    FunSpec({
        test("golden queries find what people mean") {
            val config = ElasticsearchTestServer.config()
            val search = ElasticsearchSearch(ElasticsearchClient(config), indexPrefix = config.indexPrefix)
            EnsureLiveGeneration(search)()
            SeedCatalog.events().forEach { search.write(it) }
            search.refresh()

            val rows =
                golden.map { case ->
                    val top = search.search(DishQuery(case.query, case.language, size = K), null).dishes.map { it.slug }
                    val recall =
                        case.relevant
                            .count { it in top }
                            .toDouble() / minOf(K, case.relevant.size)
                    val rank = top.indexOfFirst { it in case.relevant } + 1
                    Triple(case, top, recall to (if (rank == 0) 0.0 else 1.0 / rank))
                }
            val recall = rows.map { it.third.first }.average()
            val mrr = rows.map { it.third.second }.average()

            File("build/reports").mkdirs()
            File("build/reports/search-quality.md").writeText(
                buildString {
                    appendLine("# Search quality: Recall@$K %.3f, MRR %.3f".format(Locale.ROOT, recall, mrr))
                    appendLine()
                    appendLine("| lang | query | recall@$K | RR | top $K |")
                    appendLine("|---|---|---|---|---|")
                    rows.forEach { (case, top, scores) ->
                        val marked = top.joinToString(", ") { if (it in case.relevant) "**$it**" else it }
                        appendLine(
                            "| ${case.language.tag} | ${case.query} | %.2f | %.2f | $marked |".format(
                                Locale.ROOT,
                                scores.first,
                                scores.second,
                            ),
                        )
                    }
                },
            )

            // Every query finds at least one relevant dish on the first screen.
            rows.filter { it.third.second == 0.0 }.map { it.first.query } shouldBe emptyList()
            recall shouldBeGreaterThanOrEqual MIN_RECALL
            mrr shouldBeGreaterThanOrEqual MIN_MRR
        }
    })

private const val MIN_RECALL = 0.95
private const val MIN_MRR = 0.95
