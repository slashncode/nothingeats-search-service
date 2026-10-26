# nothingeats-search-service

Search bounded context of [NothingEats](https://github.com/slashncode/nothingeats): dish search with facets and search-as-you-type over the catalog, in German and English. Later it also serves the retrieval API for the Craving Coach (RAG).

Search is a **read model**: it owns no data and has no database. Everything in Elasticsearch is a projection of the catalog's compacted Kafka topics and can be rebuilt from them at any time.

## API (`/api/search/v1`, public)

| Method | Path | What |
|---|---|---|
| `GET` | `/dishes?q=&lang=&cuisine=&diet=&excludeAllergen=&mood=&maxSpice=&maxPrice=&openNow=&page=&size=` | Dishes with total and facet counts |
| `GET` | `/suggestions?q=&lang=` | Up to eight dish and restaurant names for a prefix of two or more characters |

- **Filters**: `cuisine` and `mood` match any of the given values, `diet` all of them, `excludeAllergen` none of them; `maxSpice` (0–3) and `maxPrice` (cents) are upper bounds; `openNow=true` keeps restaurants open right now in Berlin time. Multi-value filters repeat the parameter or separate values with commas.
- **Facets** count with every *other* filter applied (multi-select facets): picking a cuisine still shows how many dishes the other cuisines have. Diet tags combine with AND, so their counts include the own filter. Price buckets are cumulative ("up to 8 €").
- **Paging**: `page` 0–50, `size` 1–50 (default 20). Free text sorts by relevance, an empty query by restaurant.
- **Language**: `?lang=de|en` wins over `Accept-Language`; German otherwise. Responses carry `Content-Language`.
- Errors are RFC 9457 problems; invalid parameters are `422 SEARCH_INVALID` with the field.
- Every search is captured as the PostHog event `search_performed` (normalised query, language, result count, filters used, page) with the visitor's distinct id from the request headers, including zero-result searches.

## How the index works

- **Generations**: each index build creates `dishes-v<mapping>-<millis>` and `restaurants-v<mapping>-<millis>`; readers and the live projection only use the aliases `dishes` and `restaurants`.
- **Live projection**: the `search-service` consumer group reads `catalog.dish.v1` and `catalog.restaurant.v1`. There is no inbox table: every document is written with the Kafka offset as its external version, so redeliveries and stale writes are rejected by Elasticsearch itself (409 is success).
- **Reindex** (`cron reindex`): builds a new generation by reading both topics from the beginning without a consumer group, swaps both aliases in one atomic `_aliases` call, catches up on what arrived during the build, then deletes the old generation. Run it after every mapping change (bump `ElasticsearchSearch.MAPPING_VERSION`).
- **German text**: `dictionary_decompounder` with a food word list splits compounds at index time ("Trüffelpommes" → "trüffel", "pommes"); query-time synonyms bridge German and English ("Pommes" ↔ "fries") and German words to the English mood, diet and cuisine keys ("käsig" → `cheesy`). Suggestions use `search_as_you_type` with ASCII folding ("pho" finds "Phở").
- **Known limit**: a dish carries its restaurant's name from when the dish last changed. A restaurant rename shows up in dish search after the next reindex.

## Search quality

`SearchQualityTest` runs golden queries against the catalog seed (a copy in `adapters/src/testFixtures/resources/seed`) and fails below Recall@5 0.95 or MRR 0.95. The report lands in `adapters/build/reports/search-quality.md`. Tune analyzers and boosts against it, and add a golden query for every search complaint.

## Run

```bash
./gradlew build                     # compile, ktlint, tests (Docker needed: Elasticsearch and Kafka via Testcontainers)

# against the local k3d cluster from the host (Elasticsearch on 19200, Kafka on 19094)
export ELASTICSEARCH__USERNAME=search-service ELASTICSEARCH__PASSWORD=...
./gradlew :app:run --args="cron reindex"
./gradlew :app:run

# in the cluster
kubectl -n nothingeats create job --from=cronjob/search-service-reindex reindex-$(date +%s)
just dev search-service             # Tilt inner loop from the hub repo
```

## License

MIT
