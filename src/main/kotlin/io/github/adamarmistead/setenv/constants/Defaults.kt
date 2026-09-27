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
        // US & Canada
        "use1" to "us-east-1",
        "use2" to "us-east-2",
        "usw1" to "us-west-1",
        "usw2" to "us-west-2",
        "cac1" to "ca-central-1",
        // South America
        "sae1" to "sa-east-1",
        // Europe
        "euc1" to "eu-central-1",
        "euw1" to "eu-west-1",
        "euw2" to "eu-west-2",
        "euw3" to "eu-west-3",
        "eun1" to "eu-north-1",
        "eus1" to "eu-south-1",
        "eus2" to "eu-south-2",
        // Middle East
        "mes1" to "me-south-1",
        "mec1" to "me-central-1",
        // Africa
        "afs1" to "af-south-1",
        // Asia Pacific – South
        "aps1" to "ap-south-1",
        "aps2" to "ap-south-2",
        "apse1" to "ap-southeast-1",
        "apse2" to "ap-southeast-2",
        "apse3" to "ap-southeast-3",
        "apse4" to "ap-southeast-4",
        // Asia Pacific – North
        "apne1" to "ap-northeast-1",
        "apne2" to "ap-northeast-2",
        "apne3" to "ap-northeast-3",
    )

    val REGION_SHORT_CODES: Map<String, String> = REGIONS.entries.associate { (short, full) -> full to short }

    // ── Helpers ────────────────────────────────────────────────────────
    fun cacheFileName(targetEnv: String): String = ".env-$targetEnv"
}
