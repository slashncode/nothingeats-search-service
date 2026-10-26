package io.github.slashncode.nothingeats.search.adapters

import io.github.slashncode.nothingeats.search.adapters.elasticsearch.ElasticsearchConfig
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID

/**
 * One Elasticsearch per test JVM (same version as the local cluster, security off), a fresh index
 * prefix per [config], so tests never see each other's documents.
 */
object ElasticsearchTestServer {
    private const val IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:9.5.5"
    private const val PORT = 9200

    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse(IMAGE))
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow").forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3))
            .apply { start() }
    }

    fun config(): ElasticsearchConfig =
        ElasticsearchConfig(
            url = "http://${container.host}:${container.getMappedPort(PORT)}",
            indexPrefix = "test-${UUID.randomUUID().toString().take(8)}-",
        )
}
