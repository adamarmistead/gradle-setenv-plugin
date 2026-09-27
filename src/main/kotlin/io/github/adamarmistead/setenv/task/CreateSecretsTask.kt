package io.github.adamarmistead.setenv.task

import groovy.json.JsonSlurper
import io.github.adamarmistead.setenv.constants.AwsCli
import io.github.adamarmistead.setenv.constants.Defaults
import io.github.adamarmistead.setenv.constants.PropertyKeys
import io.github.adamarmistead.setenv.dsl.Secrets
import io.github.adamarmistead.setenv.internal.CommandExecutor
import io.github.adamarmistead.setenv.internal.EnvFileIO
import io.github.adamarmistead.setenv.internal.PropertyMapBuilder
import org.apache.commons.text.StringSubstitutor
import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Fetches secrets from AWS Secrets Manager and merges them into the shared `.env` file.
 *
 * **Caching is handled by Gradle's up-to-date mechanism.** The task declares its inputs
 * (`target`, `region`, `refresh`, `secrets`) and outputs (`cacheFile`). Gradle skips
 * the task entirely when no inputs have changed, so no AWS calls are made on redundant
 * runs. When any input changes (e.g. region switches from `use1` to `use2`), Gradle
 * re-runs the task and fresh values are fetched.
 *
 * The [cacheFile] serves as a persistent snapshot of the last successful fetch, useful
 * for inspection or debugging. It is always a complete, all-or-nothing set of secrets
 * for the configured environment — no partial state.
 */
abstract class CreateSecretsTask @Inject constructor(
    /** The Gradle project, used to resolve properties for placeholder substitution. */
    @Internal private val project: Project,
) : DefaultTask() {

    init {
        group = Defaults.TASK_GROUP
        description = "Fetch or reuse cached secrets and merge them into the active env file"
    }

    /** The target environment name (e.g. `dev`, `stg`). */
    @get:Input
    abstract val target: Property<String>

    /** The AWS region (full name or short code). */
    @get:Input
    abstract val region: Property<String>

    /** When `true`, bypasses the local cache and re-fetches all secrets from AWS. */
    @get:Input
    abstract val refresh: Property<Boolean>

    /** Timeout in seconds for non-interactive AWS CLI commands (fetch, STS check). */
    @get:Input
    abstract val commandTimeout: Property<Int>

    /**
     * Timeout in seconds for the interactive login step. Longer than [commandTimeout]
     * because SSO login opens a browser and waits for user authentication.
     */
    @get:Input
    abstract val loginTimeout: Property<Int>

    /**
     * Command used to authenticate before fetching secrets. The `{profile}` placeholder
     * is replaced with the secret's AWS CLI profile. Defaults to `aws sso login --profile {profile}`.
     */
    @get:Input
    abstract val loginCommand: Property<String>

    /** The collection of [Secrets] blocks to process. Populated from the `env { secrets { } }` DSL. */
    @get:Nested
    abstract val secrets: NamedDomainObjectContainer<Secrets>

    /**
     * The final `.env` file that secrets are merged into.
     * Marked `@Internal` (not `@OutputFile`) because [CreateEnvTask] already declares
     * this file as its output; declaring it on both tasks would cause Gradle to report
     * overlapping outputs in a real build.
     */
    @get:Internal
    abstract val envFile: RegularFileProperty

    /**
     * Per-environment cache file (e.g. `.env-dev`). Holds the combined values of all
     * configured secrets for this environment. Managed automatically by the plugin —
     * do not edit manually.
     *
     * The task uses an all-or-nothing strategy: if this file exists and `refresh` is
     * false, it is read directly (no AWS calls). Otherwise, all secrets are fetched
     * from AWS and the file is rebuilt from scratch.
     */
    @get:OutputFile
    abstract val cacheFile: RegularFileProperty

    /**
     * Pluggable command executor for unit testing without calling live AWS CLI.
     *
     * Signature: `(command: String, timeoutSeconds: Int, writeOutput: Boolean) -> String`
     * - `command`: the shell command to execute
     * - `timeoutSeconds`: maximum time allowed before the process is killed
     * - `writeOutput`: when `true`, captured output is printed to stdout
     * - returns the combined stdout/stderr output of the command
     */
    @get:Internal
    var commandExecutor: (command: String, timeoutSeconds: Int, writeOutput: Boolean) -> String =
        { cmd, timeout, write -> CommandExecutor.execute(cmd, timeout, write) }

    /**
     * Task action: fetches all configured secrets from AWS and merges them into the `.env` file.
     *
     * **Caching is delegated to Gradle's up-to-date mechanism.** This method is only called
     * when Gradle determines the task is out-of-date (i.e. an input changed: region, target,
     * refresh flag, or secret configuration). When it runs, it always fetches fresh values
     * from AWS — no internal file-existence checks needed.
     *
     * The [cacheFile] is written as a persistent snapshot of the last successful fetch,
     * useful for inspection. It is always a complete, all-or-nothing set of secrets.
     *
     * Skips gracefully (no-op) when no secrets blocks are configured.
     */
    @TaskAction
    fun createSecretsFile() {
        if (secrets.isEmpty()) {
            logger.lifecycle("No secrets blocks configured; skipping secret fetch")
            return
        }

        val cacheFile = cacheFile.get().asFile
        val envFile = envFile.get().asFile
        logger.lifecycle("targeting - environment:${target.get()} region:${region.get()}")

        val combined = linkedMapOf<String, String>()
        secrets.forEach { secret ->
            if (secret.region.isPresent) {
                logger.lifecycle(
                    "overriding target region:${region.get()} with:${secret.region.get()} for ${secret.name}",
                )
            }

            val targetSecretRegion = secret.region.orElse(region).get()
            val propertiesMap = PropertyMapBuilder.build(project, target.get(), targetSecretRegion)
            val substitutor = StringSubstitutor(propertiesMap).apply { setValueDelimiter(':') }

            // Authenticate once per unique profile (STS check is cheap if already authed)
            authenticate(secret.profile.get())

            val targetSecretId = substitutor.replace(secret.secretId.get())
            val result: String = fetchAwsSecret(secret, targetSecretId, propertiesMap)

            // Parse JSON secret or treat as a single plaintext value
            val remoteProperties: Map<String, Any?> = if (!secret.plaintext.get()) {
                parseJsonSecret(result, secret.name)
            } else {
                val key = secret.plaintextKey.orNull
                    ?: throw IllegalStateException(
                        "Secret '${secret.name}' has plaintext = true but no plaintextKey configured. " +
                            "Set plaintextKey to the env variable name, e.g. plaintextKey = 'API_TOKEN'",
                    )
                mapOf(key to result.trim())
            }

            // Rename → filter, as a pure pipeline (no mutable state)
            val renameKeys = secret.renameKeys.orNull ?: emptyMap()
            val filterKeys = secret.secretKeys.orNull ?: emptyList()

            remoteProperties
                .mapKeys { (original, _) -> renameKeys[original] ?: original }
                .filterKeys { key -> filterKeys.isEmpty() || key in filterKeys || key in renameKeys.values }
                .forEach { (key, value) -> combined[key] = value.toString() }
        }

        // Write the complete snapshot to the cache file (replaces previous content)
        EnvFileIO.write(cacheFile, combined)
        logger.lifecycle("Wrote ${combined.size} secret(s) to ${cacheFile.name}")

        // Merge into the shared .env, preserving comments and manual edits
        EnvFileIO.mergeInto(envFile, cacheFile)
        logger.lifecycle("Merged secrets into ${envFile.name}")
    }

    /**
     * Fetches a secret from AWS Secrets Manager via the `aws` CLI.
     * Assumes the configured profile is already authenticated (see [authenticate]).
     *
     * @param secret the [Secrets] block providing profile and other settings
     * @param targetSecretId the fully-resolved secret ID (placeholders already substituted)
     * @param propertiesMap the property map used to resolve the region for the CLI call
     * @return the raw secret string as returned by the AWS CLI
     */
    private fun fetchAwsSecret(
        secret: Secrets,
        targetSecretId: String,
        propertiesMap: Map<String, String>,
    ): String {
        logger.lifecycle("Fetching AWS secret ${secret.name} into cache")

        val awsArgs = listOf(
            "--query SecretString",
            "--profile ${secret.profile.get()}",
            "--region ${propertiesMap[PropertyKeys.REGION]}",
            "--secret-id $targetSecretId",
            "--output text",
        ).joinToString(" ")

        return commandExecutor(
            "${AwsCli.SECRETS_MANAGER} $awsArgs",
            commandTimeout.get(),
            false,
        )
    }

    /**
     * Ensures the AWS CLI profile is authenticated before any secret fetch.
     *
     * Checks the current identity via `aws sts get-caller-identity`; if that fails
     * or returns nothing, runs the configured [loginCommand] so the user can
     * authenticate however they prefer (SSO, custom script, etc.).
     *
     * @param profile the AWS CLI profile name to check and authenticate
     */
    private fun authenticate(profile: String) {
        val alreadyAuthed = try {
            commandExecutor("${AwsCli.STS_IDENTITY} --profile $profile", commandTimeout.get(), false).isNotBlank()
        } catch (_: Exception) {
            false
        }

        if (alreadyAuthed) {
            logger.lifecycle("Already authenticated (profile '$profile'); skipping login")
            return
        }

        val loginCmd = loginCommand.get()
            .replace("{profile}", profile)
        logger.lifecycle("Not authenticated; running login: $loginCmd")
        commandExecutor(loginCmd, loginTimeout.get(), false)
    }

    /**
     * Parses an AWS Secrets Manager response as a JSON object.
     *
     * Provides helpful error messages for common misconfigurations (empty response,
     * non-JSON content, JSON array instead of object).
     *
     * @param result the raw string returned by the AWS CLI
     * @param secretName the name of the secrets block, used in error messages
     * @return a mutable map of key-value pairs from the JSON object
     * @throws IllegalStateException if the response is empty, not valid JSON, or not a JSON object
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseJsonSecret(result: String, secretName: String): MutableMap<String, Any?> {
        if (result.isBlank()) {
            throw IllegalStateException(
                "Secret '$secretName' returned an empty response. " +
                    "Check that the secret ID is correct and the secret exists in AWS Secrets Manager.",
            )
        }

        val parsed: Any = try {
            JsonSlurper().parseText(result)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Secret '$secretName' is not valid JSON: ${e.message}. " +
                    "If this is a plain-text secret (e.g. a token or password), set plaintext = true " +
                    "and plaintextKey = 'YOUR_ENV_VAR_NAME' in the secrets block.",
            )
        }

        if (parsed !is Map<*, *>) {
            throw IllegalStateException(
                "Secret '$secretName' is a JSON ${parsed::class.java.simpleName}, not an object. " +
                    "Expected a JSON object like {\"KEY\": \"value\"}. " +
                    "If this is a plain-text secret, set plaintext = true and plaintextKey = 'YOUR_ENV_VAR_NAME'.",
            )
        }

        return parsed as MutableMap<String, Any?>
    }
}
