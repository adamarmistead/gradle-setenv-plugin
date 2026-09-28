package io.github.adamarmistead.setenv.task

import io.github.adamarmistead.setenv.constants.Defaults
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option

/**
 * Top-level task that orchestrates environment file creation and secret fetching.
 *
 * Depends on [CreateEnvTask] (writes static/interpolated variables) and
 * [CreateSecretsTask] (fetches AWS secrets), running them in order.
 *
 * Supports CLI overrides:
 * ```
 * gradle setEnv --target=stg --region=use2 --refresh
 * ```
 */
@UntrackedTask(because = "Orchestrator task that delegates to createEnv and createSecrets; produces no output of its own")
abstract class SetEnvTask : DefaultTask() {

    init {
        group = Defaults.TASK_GROUP
        description = "Create environment and secrets files for the configured target"
    }

    /**
     * Target environment name (e.g. `dev`, `stg`, `prd`).
     * Can be overridden via `--target=<value>` on the command line.
     */
    @get:Option(option = "target", description = "Target environment (e.g. dev, stg, prd)")
    @get:Input
    abstract val target: Property<String>

    /**
     * Target AWS region — full name (`us-east-1`) or short code (`use1`).
     * Can be overridden via `--region=<value>` on the command line.
     */
    @get:Option(option = "region", description = "Target AWS region or short code (e.g. us-east-1, use1)")
    @get:Input
    abstract val region: Property<String>

    /**
     * When `true`, forces a re-fetch of all secrets from AWS, bypassing the local cache.
     * Can be overridden via `--refresh` on the command line.
     */
    @get:Option(option = "refresh", description = "Force refresh of cached secrets from AWS")
    @get:Input
    abstract val refresh: Property<Boolean>
}
