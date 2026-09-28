package io.github.adamarmistead.setenv.dsl

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * The `env { }` DSL block — the primary configuration surface for the plugin.
 *
 * ```kotlin
 * env {
 *     target = "dev"
 *     region = "use1"
 *     environmentVariables = mapOf("APP_NAME" to "my-app")
 *     secrets {
 *         create("db") { secretId = "myapp/\${target}/db" }
 *     }
 * }
 * ```
 *
 * @param secrets the [NamedDomainObjectContainer] of [Secrets] blocks, accessible via the `secrets { }` method
 */
abstract class EnvExtension(
    /** The container of all configured [Secrets] blocks. */
    val secrets: NamedDomainObjectContainer<Secrets>,
) {

    /** Target environment name (e.g. `dev`, `stg`, `prd`). Default: `"dev"`. */
    abstract val target: Property<String>

    /** AWS region — full name (`us-east-1`) or short code (`use1`). Default: `"us-east-1"`. */
    abstract val region: Property<String>

    /** Output `.env` file path. Default: `.env` in the root project directory. */
    abstract val envFile: RegularFileProperty

    /**
     * Static environment variables to write into the `.env` file.
     * Values support placeholder substitution (e.g. `"${target}"`).
     */
    abstract val environmentVariables: MapProperty<String, String>

    /**
     * Custom short-code → full region name mapping.
     * Entries are **added on top of** (and can override) the built-in 25 AWS commercial regions —
     * they do not replace them. Use this to add custom or private regions. Example: `"myr1" to "my-region-1"`.
     */
    abstract val regions: MapProperty<String, String>

    /**
     * Custom full region name → short code mapping.
     * Entries are **added on top of** (and can override) the built-in 25 AWS commercial regions —
     * they do not replace them. Use this to add custom or private regions. Example: `"my-region-1" to "myr1"`.
     */
    abstract val regionShortCodes: MapProperty<String, String>

    /**
     * Configures the [secrets] container using a Gradle [Action].
     *
     * ```kotlin
     * secrets {
     *     create("db") { secretId = "myapp/\${target}/db" }
     * }
     * ```
     *
     * @param action the configuration action applied to the secrets container
     */
    fun secrets(action: Action<in NamedDomainObjectContainer<Secrets>>) {
        action.execute(secrets)
    }
}
