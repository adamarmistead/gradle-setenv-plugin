package io.github.adamarmistead.setenv.constants

/**
 * Central home for all default values and well-known constants used across the plugin.
 */
object Defaults {

    const val TASK_GROUP = "setenv"

    // ── Default convention values ──────────────────────────────────────
    const val TIMEOUT_SECONDS = 60
    const val LOGIN_TIMEOUT_SECONDS = 300
    const val ENV = "dev"
    const val ENV_FILE = ".env"
    const val PROFILE = "default"
    const val REGION = "us-east-1"
    const val IS_PLAINTEXT = false

    // ── Built-in region mappings (short-code ↔ full name) ─────────────
    val REGIONS: Map<String, String> = mapOf(
        "use1" to "us-east-1",
        "use2" to "us-east-2",
        "usw1" to "us-west-1",
        "usw2" to "us-west-2",
    )

    val REGION_SHORT_CODES: Map<String, String> = mapOf(
        "us-east-1" to "use1",
        "us-east-2" to "use2",
        "us-west-1" to "usw1",
        "us-west-2" to "usw2",
    )

    // ── Helpers ────────────────────────────────────────────────────────
    fun cacheFileName(targetEnv: String): String = ".env-$targetEnv"
}
