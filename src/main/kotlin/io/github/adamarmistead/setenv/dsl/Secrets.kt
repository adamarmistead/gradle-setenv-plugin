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

    /**
     * When `true`, multi-line values that are not valid JSON (e.g. PEM certificates)
     * are written to a file in the `.env-secrets/` directory and the env var is set
     * to the absolute file path instead of the raw value.
     *
     * Multi-line values that ARE valid JSON are always minified inline regardless
     * of this flag (safe, lossless transform).
     *
     * When `false` (default), multi-line non-JSON values cause a build error —
     * the `.env` format is line-based and cannot represent them inline.
     */
    @get:Input
    @get:Optional
    abstract val writeToFile: Property<Boolean>

    /**
     * When `true`, the raw secret value is base64-decoded before being written to the env file.
     * Useful for secrets stored as base64-encoded binary data in AWS Secrets Manager.
     * Only applies to plaintext secrets; JSON secrets are unaffected.
     */
    @get:Input
    @get:Optional
    abstract val decode: Property<Boolean>

}
