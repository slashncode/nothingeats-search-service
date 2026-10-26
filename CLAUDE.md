# CLAUDE.md

Search service of NothingEats: dish search, facets and suggestions over Elasticsearch, a projection of the catalog's Kafka topics. Created from the Kotlin service template. The cross-repo plan and session rules live in `../CLAUDE.md` (workspace root), if present — read it first.

## Commands

- `./gradlew build` — compile (warnings are errors), ktlint, tests. Needs Docker (Elasticsearch 9.5.5 and Kafka via Testcontainers).
- `./gradlew ktlintFormat` — auto-format
- `./gradlew :adapters:test --tests '*SearchQualityTest'` — golden queries; report in `adapters/build/reports/search-quality.md`
- `./gradlew :app:run` / `./gradlew :app:run --args="cron reindex"` — against the k3d cluster (needs `ELASTICSEARCH__USERNAME`, `ELASTICSEARCH__PASSWORD`)

## Rules

- No database and no `migrate` mode: everything in Elasticsearch must be rebuildable from the catalog topics. Never store data here that exists nowhere else.
- Writes always carry the record offset as external version; a 409 means "already newer" and is not an error.
- Readers and the live projection use only the aliases (`dishes`, `restaurants`), never generation names.
- Mapping changes go into `adapters/src/main/resources/elasticsearch/*.json` together with a bump of `ElasticsearchSearch.MAPPING_VERSION`; deploy, then run `cron reindex`.
- Ranking changes are judged by `SearchQualityTest`, not by feel. Add a golden query for every search complaint.
- The catalog event contract lives in the catalog service (`docs/events.md`); the payload classes in `adapters/.../messaging` read only what search needs and ignore the rest.
- Wiring happens only in `app/.../ServiceComponent.kt`; lifecycle components, health checks, routes, event handlers and cron tasks are contributed with `@IntoSet`.
