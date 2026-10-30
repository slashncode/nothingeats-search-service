package io.github.slashncode.nothingeats.search.application

import io.github.slashncode.nothingeats.search.domain.CatalogVocabulary
import io.github.slashncode.nothingeats.search.domain.DishFilters
import io.github.slashncode.nothingeats.search.domain.DishQuery
import io.github.slashncode.nothingeats.search.domain.DishResults
import io.github.slashncode.nothingeats.search.domain.Facets
import io.github.slashncode.nothingeats.search.domain.Language
import io.github.slashncode.nothingeats.search.domain.Retrieval
import io.github.slashncode.nothingeats.search.domain.SearchableDish
import io.github.slashncode.nothingeats.search.domain.SearchableRestaurant
import io.github.slashncode.nothingeats.search.domain.Suggestion
import io.github.slashncode.nothingeats.search.domain.Text
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Generations as maps of documents, with external versioning like Elasticsearch's. */
class InMemorySearchIndices : SearchIndices {
    val generations = ConcurrentHashMap<String, ConcurrentHashMap<String, CatalogEvent>>()

    /** The vector each dish was last written with, by dish id. */
    val vectors = ConcurrentHashMap<String, DishVector>()
    var liveName: String? = null
    private var counter = 0

    override suspend fun live() = liveName?.let { Generation(it) }

    override suspend fun create(): Generation {
        val name = "gen-${++counter}"
        generations[name] = ConcurrentHashMap()
        return Generation(name)
    }

    override suspend fun write(
        event: CatalogEvent,
        into: Generation?,
        vector: DishVector?,
    ) {
        if (event is CatalogEvent.DishPublished && vector != null) vectors[event.dish.id] = vector
        val docs = generations.getValue((into ?: live() ?: error("no live generation")).name)
        val id =
            when (event) {
                is CatalogEvent.DishPublished -> "dish:${event.dish.id}"
                is CatalogEvent.RestaurantPublished -> "restaurant:${event.restaurant.id}"
            }
        docs.merge(id, event) { stored, new -> if (new.version > stored.version) new else stored }
    }

    override suspend fun publish(generation: Generation): Generation? {
        val previous = live()
        liveName = generation.name
        return previous
    }

    override suspend fun drop(generation: Generation) {
        generations.remove(generation.name)
    }

    fun documents(generation: String? = liveName) = generations.getValue(generation!!).keys.sorted()
}

private data class Offset(
    val value: Int,
) : LogPosition

/** A list of events; [producedDuringReindex] are appended right after the replay ended. */
class FakeCatalogLog(
    events: List<CatalogEvent>,
) : CatalogLog {
    val events = CopyOnWriteArrayList(events)
    val producedDuringReindex = mutableListOf<CatalogEvent>()

    override suspend fun replay(handle: suspend (CatalogEvent) -> Unit): LogPosition {
        val end = events.size
        events.take(end).forEach { handle(it) }
        events += producedDuringReindex
        return Offset(end)
    }

    override suspend fun catchUp(
        from: LogPosition,
        handle: suspend (CatalogEvent) -> Unit,
    ): LogPosition {
        val start = (from as Offset).value
        val end = events.size
        events.subList(start, end).toList().forEach { handle(it) }
        return Offset(end)
    }
}

class RecordingDishIndex : DishIndex {
    val searches = CopyOnWriteArrayList<Pair<DishQuery, Set<String>?>>()
    var results = DishResults(emptyList(), 0, Facets(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))

    /** What [retrieve] answers; `semantic = null` plays an index that cannot run the meaning leg. */
    var candidates = Candidates(emptyList(), emptyList())
    val retrievals = CopyOnWriteArrayList<Triple<Retrieval, DishVector?, Set<String>?>>()
    val dishes = mutableListOf<SearchableDish>()

    override suspend fun search(
        query: DishQuery,
        openRestaurantIds: Set<String>?,
    ): DishResults {
        searches += query to openRestaurantIds
        return results
    }

    override suspend fun suggest(
        prefix: String,
        language: Language,
        limit: Int,
    ): List<Suggestion> = emptyList()

    override suspend fun retrieve(
        retrieval: Retrieval,
        vector: DishVector?,
        openRestaurantIds: Set<String>?,
        candidates: Int,
    ): Candidates {
        retrievals += Triple(retrieval, vector, openRestaurantIds)
        return if (vector == null) this.candidates.copy(semantic = null) else this.candidates
    }

    override suspend fun get(ids: List<String>) = ids.mapNotNull { id -> dishes.firstOrNull { it.id == id } }

    override suspend fun vocabulary() = CatalogVocabulary(emptyList(), emptyList(), emptyList(), emptyList())
}

/** Vectors of one fixed value per text length; [failing] plays a model that is down. */
class FakeEmbeddingModel(
    override val dimensions: Int = 3,
) : EmbeddingModel {
    override val id = "fake@$dimensions"
    var failing = false
    val texts = CopyOnWriteArrayList<String>()

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (failing) throw IllegalStateException("the model is down")
        this.texts += texts
        return texts.map { text -> FloatArray(dimensions) { text.length.toFloat() } }
    }
}

class FixedRestaurants(
    private val restaurants: List<SearchableRestaurant>,
) : RestaurantDirectory {
    override suspend fun all() = restaurants
}

fun restaurant(
    id: String,
    opensAt: String = "11:00",
    closesAt: String = "23:00",
) = SearchableRestaurant(id, id, id.replaceFirstChar { it.uppercase() }, "italian", LocalTime.parse(opensAt), LocalTime.parse(closesAt))

fun dish(
    id: String,
    restaurantId: String = "phantom-pizza",
) = SearchableDish(
    id = id,
    restaurantId = restaurantId,
    restaurantSlug = restaurantId,
    restaurantName = "Phantom Pizza",
    cuisine = "italian",
    slug = id,
    section = Text("Pizza", "Pizza"),
    name = Text("Margherita", "Margherita"),
    description = Text("Tomate, Mozzarella", "Tomato, mozzarella"),
    priceCents = 1090,
    dietary = setOf("vegetarian"),
    allergens = setOf("gluten", "milk"),
    spiceLevel = 0,
    calories = 820,
    moods = setOf("cheesy"),
    available = true,
)

val noFilters = DishFilters()
