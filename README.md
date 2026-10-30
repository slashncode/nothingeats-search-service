# nothingeats-search-service

Search bounded context of [NothingEats](https://github.com/slashncode/nothingeats): dish search with facets and search-as-you-type over the catalog, in German and English, and the hybrid retrieval (keywords and meaning) behind the Craving Coach, the AI chat in `nothingeats-assistant-service`.

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

## Retrieval for the Craving Coach (gRPC, `nothingeats.search.v1.SearchService`)

The contract lives in `grpc-api/src/main/proto` (buf lint and buf breaking in CI); the assistant (TypeScript) generates its client from it. Every call carries the customer's access token, forwarded by the assistant.

| RPC | What |
|---|---|
| `Retrieve` | The dishes that fit a craving: free text, keywords, words no dish may mention, hard filters (cuisine, diet, allergens, mood, spice, price, open now), a semantic weight and a limit (1–20). Unavailable dishes never come back. |
| `GetDishes` | Dishes by id in the order asked (the assistant checks recommendations against the catalog with it). |
| `DescribeCatalog` | Every cuisine, diet tag, mood and allergen the available dishes use, with counts: the vocabulary the model may filter by. |

- **Hybrid**: one `_msearch` runs a keyword leg (BM25 with the search analyzers and synonyms; a sentence must match on most of its words) and a meaning leg (kNN on dish embeddings) under the same hard filters. Weighted reciprocal rank fusion merges them (k = 60, semantic weight 0.7 by default).
- **Embeddings**: one vector per dish from a text with both languages, cuisine, moods, diet, heat and calories (`embeddingText()`), and one per query, both from the same model in this service. `EMBEDDINGS__PROVIDER=OPENAI` uses `text-embedding-3-large` shortened to 1024 dimensions; the default `HASHING` (feature hashing) needs no key and knows no meaning, for tests and clusters without a key. Vectors are cached in Valkey by model and text hash for 30 days.
- **Degrades instead of failing**: a dish the model cannot embed is indexed without a vector (words only until the next reindex); a query it cannot embed is answered by keywords only (`semantic = false`); an index that cannot take vectors (older mapping, other dimensions) gets the dish without one.

## How the index works

- **Generations**: each index build creates `dishes-v<mapping>-<millis>` and `restaurants-v<mapping>-<millis>`; readers and the live projection only use the aliases `dishes` and `restaurants`.
- **Live projection**: the `search-service` consumer group reads `catalog.dish.v1` and `catalog.restaurant.v1`. There is no inbox table: every document is written with the Kafka offset as its external version, so redeliveries and stale writes are rejected by Elasticsearch itself (409 is success).
- **Reindex** (`cron reindex`): builds a new generation by reading both topics from the beginning without a consumer group, swaps both aliases in one atomic `_aliases` call, catches up on what arrived during the build, then deletes the old generation. Run it after every mapping change (bump `ElasticsearchSearch.MAPPING_VERSION`).
- **Word starts and typos**: the last word of a search also matches as a prefix of dish and restaurant names (`search_as_you_type` fields, as in the suggestions), so "burg" finds burgers and "Trüff" Trüffelpommes. Typos are tolerated from five letters on (one edit up to seven, two from eight); below that a word is more likely unfinished, and one edit reaches too far ("marg" → "Magen"). Prefixes and synonyms do not combine: "Hähn" finds the Hähnchen dishes, not the ones called Chicken.
- **German text**: `dictionary_decompounder` with a food word list splits compounds at index time ("Trüffelpommes" → "trüffel", "pommes"); query-time synonyms bridge German and English ("Pommes" ↔ "fries") and German words to the English mood, diet and cuisine keys ("käsig" → `cheesy`). Suggestions use `search_as_you_type` with ASCII folding ("pho" finds "Phở").
- **Known limit**: a dish carries its restaurant's name from when the dish last changed. A restaurant rename shows up in dish search after the next reindex.

## Search quality

`SearchQualityTest` runs golden queries against the catalog seed (a copy in `adapters/src/testFixtures/resources/seed`) and fails below Recall@5 0.95 or MRR 0.95. The report lands in `adapters/build/reports/search-quality.md`. Tune analyzers and boosts against it, and add a golden query for every search complaint.

`RetrievalQualityTest` does the same for retrieval: 16 cravings in the customer's words ("Etwas Warmes für einen verregneten Abend") and 12 dish names as typed into a search box, against real `text-embedding-3-large` vectors recorded in `adapters/src/test/resources/embeddings/` (no key or network needed in CI). It sweeps the semantic weight from 0 to 1 and fails when the default trails either leg or drops below Recall@5 0.88 / MRR 0.9; the report lands in `adapters/build/reports/retrieval-quality.md`. A new golden text or a changed embedding text needs a new recording:

```bash
RECORD_EMBEDDINGS=true OPENAI_API_KEY=... ./gradlew :adapters:test --tests '*RetrievalQualityTest'
```

## Run

```bash
./gradlew build                     # compile, ktlint, tests (Docker needed: Elasticsearch and Kafka via Testcontainers)

# against the local k3d cluster from the host (Elasticsearch on 19200, Kafka on 19094, Valkey on 16379)
export ELASTICSEARCH__USERNAME=search-service ELASTICSEARCH__PASSWORD=... VALKEY__PASSWORD=...
export EMBEDDINGS__PROVIDER=OPENAI EMBEDDINGS__OPENAI__API_KEY=...   # optional; hashing otherwise
./gradlew :app:run --args="cron reindex"
./gradlew :app:run

# in the cluster
kubectl -n nothingeats create job --from=cronjob/search-service-reindex reindex-$(date +%s)
just dev search-service             # Tilt inner loop from the hub repo
```

## License

MIT
