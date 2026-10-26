// HTTP routes, Elasticsearch and Kafka live here, behind the application's ports. No database: search is a read model.
plugins {
    id("nothingeats.kotlin-base")
    id("nothingeats.kotlinx-serialization")
    `java-test-fixtures`
}

dependencies {
    implementation(platform(libs.nothingeats.platform.bom))
    api(project(":application"))
    api(libs.nothingeats.platform.http)
    api(libs.nothingeats.ktor.posthog)
    api(libs.nothingeats.platform.kafka)

    // Elasticsearch in a container and the catalog seed as events, shared with the app tests.
    testFixturesApi(libs.nothingeats.platform.testing)

    testImplementation(testFixtures(project(":application")))
    testImplementation(libs.nothingeats.platform.testing)
    testImplementation(libs.ktor.server.test.host)
}
