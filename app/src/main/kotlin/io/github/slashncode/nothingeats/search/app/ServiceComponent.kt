package io.github.slashncode.nothingeats.search.app

import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleManager
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.core.run.CronTask
import io.github.slashncode.nothingeats.platform.core.run.ServiceRunner
import io.github.slashncode.nothingeats.platform.grpc.AuthInterceptor
import io.github.slashncode.nothingeats.platform.grpc.ErrorInterceptor
import io.github.slashncode.nothingeats.platform.grpc.GrpcServer
import io.github.slashncode.nothingeats.platform.http.ExceptionReporter
import io.github.slashncode.nothingeats.platform.http.HttpExtension
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.HttpServer
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth
import io.github.slashncode.nothingeats.platform.kafka.EventConsumer
import io.github.slashncode.nothingeats.platform.kafka.EventConsumerConfig
import io.github.slashncode.nothingeats.platform.kafka.EventHandler
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.posthog.PostHogAnalytics
import io.github.slashncode.nothingeats.platform.posthog.PostHogExceptionReporter
import io.github.slashncode.nothingeats.platform.posthog.PostHogHttpExtension
import io.github.slashncode.nothingeats.platform.valkey.Valkey
import io.github.slashncode.nothingeats.search.adapters.cron.ReindexTask
import io.github.slashncode.nothingeats.search.adapters.elasticsearch.ElasticsearchClient
import io.github.slashncode.nothingeats.search.adapters.elasticsearch.ElasticsearchSearch
import io.github.slashncode.nothingeats.search.adapters.elasticsearch.SearchIndicesBootstrap
import io.github.slashncode.nothingeats.search.adapters.embeddings.CachedEmbeddingModel
import io.github.slashncode.nothingeats.search.adapters.embeddings.EmbeddingProvider
import io.github.slashncode.nothingeats.search.adapters.embeddings.HashingEmbeddingModel
import io.github.slashncode.nothingeats.search.adapters.embeddings.OpenAiEmbeddingModel
import io.github.slashncode.nothingeats.search.adapters.grpc.SearchGrpcService
import io.github.slashncode.nothingeats.search.adapters.http.SearchRoutes
import io.github.slashncode.nothingeats.search.adapters.messaging.CatalogEventHandler
import io.github.slashncode.nothingeats.search.adapters.messaging.KafkaCatalogLog
import io.github.slashncode.nothingeats.search.application.CatalogLog
import io.github.slashncode.nothingeats.search.application.DescribeCatalog
import io.github.slashncode.nothingeats.search.application.DishIndex
import io.github.slashncode.nothingeats.search.application.DishVectors
import io.github.slashncode.nothingeats.search.application.EmbeddingModel
import io.github.slashncode.nothingeats.search.application.EnsureLiveGeneration
import io.github.slashncode.nothingeats.search.application.GetDishes
import io.github.slashncode.nothingeats.search.application.ProjectCatalog
import io.github.slashncode.nothingeats.search.application.Reindex
import io.github.slashncode.nothingeats.search.application.RestaurantDirectory
import io.github.slashncode.nothingeats.search.application.RetrieveDishes
import io.github.slashncode.nothingeats.search.application.SearchDishes
import io.github.slashncode.nothingeats.search.application.SearchIndices
import io.github.slashncode.nothingeats.search.application.SuggestDishes
import io.grpc.BindableService
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import me.tatarka.inject.annotations.Scope

@Scope
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ServiceScope

/**
 * The composition root: the only place that wires ports to adapters.
 * Lifecycle components, health checks, routes, gRPC services, event handlers and cron tasks are collected with `@IntoSet`.
 * [jwtAuth] is passed in so tests can use test signing keys.
 */
@ServiceScope
@Component
abstract class ServiceComponent(
    private val config: ServiceConfig,
    @get:Provides val jwtAuth: JwtAuth,
) {
    abstract val runner: ServiceRunner
    abstract val lifecycle: LifecycleManager
    abstract val search: ElasticsearchSearch

    // Platform

    @ServiceScope
    @Provides
    fun readiness(): Readiness = Readiness()

    @ServiceScope
    @Provides
    fun httpServer(
        readiness: Readiness,
        checks: Set<HealthCheck>,
        routes: Set<HttpRoutes>,
        auth: JwtAuth,
        extensions: Set<HttpExtension>,
        exceptionReporter: ExceptionReporter,
    ): HttpServer = HttpServer(config.http, readiness, HealthChecks(checks), routes, auth, extensions, exceptionReporter)

    @ServiceScope
    @Provides
    fun postHog(): PostHogAnalytics = PostHogAnalytics(config.posthog)

    @Provides
    fun analytics(postHog: PostHogAnalytics): ProductAnalytics = postHog

    @Provides
    fun exceptionReporter(analytics: ProductAnalytics): ExceptionReporter = PostHogExceptionReporter(analytics)

    @IntoSet
    @Provides
    fun postHogLifecycle(postHog: PostHogAnalytics): LifecycleComponent = postHog

    @IntoSet
    @Provides
    fun postHogHttp(): HttpExtension = PostHogHttpExtension()

    @ServiceScope
    @Provides
    fun lifecycleManager(
        components: Set<LifecycleComponent>,
        readiness: Readiness,
    ): LifecycleManager = LifecycleManager(components, readiness, config.lifecycle.drainDelay)

    /** No migrations: there is no database. */
    @Provides
    fun serviceRunner(
        lifecycle: LifecycleManager,
        cronTasks: Set<CronTask>,
    ): ServiceRunner = ServiceRunner(lifecycle, migrations = null, cronTasks = cronTasks)

    @IntoSet
    @Provides
    fun httpLifecycle(server: HttpServer): LifecycleComponent = server

    @ServiceScope
    @Provides
    fun grpcServer(
        services: Set<BindableService>,
        auth: JwtAuth,
    ): GrpcServer = GrpcServer(config.grpc, services, AuthInterceptor(auth), ErrorInterceptor())

    @IntoSet
    @Provides
    fun grpcLifecycle(server: GrpcServer): LifecycleComponent = server

    /** Only the embedding cache uses it, and that fails open: not a readiness check. */
    @ServiceScope
    @Provides
    fun valkey(): Valkey = Valkey(config.valkey)

    @IntoSet
    @Provides
    fun valkeyLifecycle(valkey: Valkey): LifecycleComponent = valkey

    // Embeddings

    @ServiceScope
    @Provides
    fun embeddingModel(valkey: Valkey): EmbeddingModel =
        when (config.embeddings.provider) {
            EmbeddingProvider.HASHING -> HashingEmbeddingModel(config.embeddings.dimensions)
            EmbeddingProvider.OPENAI ->
                CachedEmbeddingModel(
                    OpenAiEmbeddingModel(config.embeddings.model, config.embeddings.dimensions, config.embeddings.openai),
                    valkey,
                    config.embeddings.cacheTtl,
                )
        }

    @Provides
    fun dishVectors(model: EmbeddingModel): DishVectors = DishVectors(model)

    // Elasticsearch

    @ServiceScope
    @Provides
    fun elasticsearchClient(): ElasticsearchClient = ElasticsearchClient(config.elasticsearch)

    @IntoSet
    @Provides
    fun elasticsearchHealth(client: ElasticsearchClient): HealthCheck = client

    @ServiceScope
    @Provides
    fun elasticsearchSearch(client: ElasticsearchClient): ElasticsearchSearch =
        ElasticsearchSearch(client, indexPrefix = config.elasticsearch.indexPrefix, vectorDimensions = config.embeddings.dimensions)

    @Provides
    fun searchIndices(search: ElasticsearchSearch): SearchIndices = search

    @Provides
    fun dishIndex(search: ElasticsearchSearch): DishIndex = search

    @Provides
    fun restaurantDirectory(search: ElasticsearchSearch): RestaurantDirectory = search

    @IntoSet
    @Provides
    fun searchIndicesBootstrap(indices: SearchIndices): LifecycleComponent = SearchIndicesBootstrap(EnsureLiveGeneration(indices))

    // Catalog projection

    @Provides
    fun projectCatalog(
        indices: SearchIndices,
        vectors: DishVectors,
    ): ProjectCatalog = ProjectCatalog(indices, vectors)

    @IntoSet
    @Provides
    fun dishEvents(project: ProjectCatalog): EventHandler = CatalogEventHandler(config.catalogTopics.dishes, project)

    @IntoSet
    @Provides
    fun restaurantEvents(project: ProjectCatalog): EventHandler = CatalogEventHandler(config.catalogTopics.restaurants, project)

    /** No inbox (db = null): documents carry the record offset as version, so every write is idempotent. */
    @ServiceScope
    @Provides
    fun eventConsumer(handlers: Set<EventHandler>): EventConsumer =
        EventConsumer(
            config = EventConsumerConfig(groupId = "search-service"),
            handlers = handlers,
            db = null,
            consumerFactory = { KafkaClients.consumer(config.kafka, "search-service") },
            deadLetters = KafkaClients.producer(config.kafka, "search-service-dlq"),
        )

    @IntoSet
    @Provides
    fun eventConsumerLifecycle(consumer: EventConsumer): LifecycleComponent = consumer

    @IntoSet
    @Provides
    fun eventConsumerHealth(consumer: EventConsumer): HealthCheck = consumer

    @Provides
    fun catalogLog(): CatalogLog = KafkaCatalogLog(config.kafka, config.catalogTopics)

    @IntoSet
    @Provides
    fun reindex(
        indices: SearchIndices,
        log: CatalogLog,
        vectors: DishVectors,
    ): CronTask = ReindexTask(Reindex(indices, log, vectors))

    // Search

    @Provides
    fun searchDishes(
        index: DishIndex,
        restaurants: RestaurantDirectory,
        analytics: ProductAnalytics,
    ): SearchDishes = SearchDishes(index, restaurants, analytics)

    @Provides
    fun suggestDishes(index: DishIndex): SuggestDishes = SuggestDishes(index)

    @IntoSet
    @Provides
    fun searchRoutes(
        search: SearchDishes,
        suggest: SuggestDishes,
    ): HttpRoutes = SearchRoutes(search, suggest)

    // Retrieval for the Craving Coach (gRPC)

    @IntoSet
    @Provides
    fun searchGrpc(
        index: DishIndex,
        restaurants: RestaurantDirectory,
        embeddings: EmbeddingModel,
    ): BindableService =
        SearchGrpcService(
            RetrieveDishes(index, restaurants, embeddings),
            GetDishes(index, restaurants),
            DescribeCatalog(index),
        )
}
