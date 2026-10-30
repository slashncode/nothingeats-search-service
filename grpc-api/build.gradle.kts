// The search service's gRPC contract: protos and generated Kotlin stubs. The assistant (TypeScript)
// generates its client from the same protos with buf; Kotlin consumers would include this repository
// as a composite build and depend on io.github.slashncode.nothingeats.search:grpc-api.
plugins {
    id("nothingeats.kotlin-base")
    id("nothingeats.grpc")
}

group = "io.github.slashncode.nothingeats.search"

// A service may use several contracts; their jars must not all be called grpc-api.jar.
base {
    archivesName = "search-grpc-api"
}

dependencies {
    implementation(platform(libs.nothingeats.platform.bom))
}
