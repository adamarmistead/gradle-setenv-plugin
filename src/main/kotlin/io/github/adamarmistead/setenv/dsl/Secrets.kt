package io.github.adamarmistead.setenv.dsl

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import javax.inject.Inject

abstract class Secrets @Inject constructor(
    @get:Internal val name: String,
) {

    /**
     * AWS CLI profile to use when fetching this secret. Defaults to "default".
     */
    @get:Input
    abstract val profile: Property<String>

    /**
     * Override the region for this specific secret (e.g. the secret lives in a different
     * AWS region than the task-level default). Same credentials work across regions —
     * this just changes the `--region` flag on the CLI call, no re-login needed.
     */
    @get:Input
    @get:Optional
    abstract val region: Property<String>

    /**
     * The secret ID / name in AWS Secrets Manager.
     * Supports placeholder substitution, e.g. "myapp/\${target}/db".
     */
    @get:Input
    abstract val secretId: Property<String>

    /**
     * Limit which keys are extracted from the secret JSON. When empty, all keys are written.
     * Useful when a shared secret contains keys that should not land in every project's .env.
     */
    @get:Input
    @get:Optional
    abstract val secretKeys: ListProperty<String>

    /**
     * Treat the secret value as plain text rather than a JSON object.
     * Use [plaintextKey] to set the key name written to the env file.
     */
    @get:Input
    abstract val plaintext: Property<Boolean>

    /**
     * Key name used when [plaintext] is true.
     */
    @get:Input
    @get:Optional
    abstract val plaintextKey: Property<String>

    /**
     * Rename keys from the remote secret before writing to the env file.
     * Map of originalKey to newKey.
     */
    @get:Input
    @get:Optional
    abstract val renameKeys: MapProperty<String, String>

}
