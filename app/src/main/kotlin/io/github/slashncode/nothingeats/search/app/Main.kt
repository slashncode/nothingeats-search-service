package io.github.slashncode.nothingeats.search.app

import io.github.slashncode.nothingeats.platform.core.config.loadConfig
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth

/** `serve` (default) or `cron reindex`. There is no `migrate`: search has no database. */
fun main(args: Array<String>) {
    val config = loadConfig<ServiceConfig>()
    ServiceComponent::class.create(config, JwtAuth(config.jwt)).runner.run(args)
}
