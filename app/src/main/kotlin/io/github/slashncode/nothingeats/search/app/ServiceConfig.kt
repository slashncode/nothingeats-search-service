package io.github.slashncode.nothingeats.search.app

import io.github.slashncode.nothingeats.platform.http.HttpServerConfig
import io.github.slashncode.nothingeats.platform.http.auth.JwtConfig
import io.github.slashncode.nothingeats.platform.kafka.KafkaConfig
import io.github.slashncode.nothingeats.platform.posthog.PostHogSettings
import io.github.slashncode.nothingeats.search.adapters.elasticsearch.ElasticsearchConfig
import io.github.slashncode.nothingeats.search.adapters.messaging.CatalogTopics
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Everything the service reads from its environment, validated at startup. No database: the search
 * indices are rebuilt from the catalog topics. In the cluster: `ELASTICSEARCH__URL`,
 * `ELASTICSEARCH__USERNAME`, `ELASTICSEARCH__PASSWORD`, `KAFKA__BOOTSTRAP_SERVERS`, `JWT__JWKS_URL`.
 */
data class ServiceConfig(
    val http: HttpServerConfig = HttpServerConfig(),
    val elasticsearch: ElasticsearchConfig = ElasticsearchConfig(),
    val kafka: KafkaConfig = KafkaConfig(),
    val catalogTopics: CatalogTopics = CatalogTopics(),
    val jwt: JwtConfig = JwtConfig(),
    val posthog: PostHogSettings = PostHogSettings(),
    val lifecycle: LifecycleConfig = LifecycleConfig(),
)

data class LifecycleConfig(
    /** Time between readiness going down and the shutdown, so load balancers stop sending traffic first. */
    val drainDelay: Duration = 5.seconds,
)
