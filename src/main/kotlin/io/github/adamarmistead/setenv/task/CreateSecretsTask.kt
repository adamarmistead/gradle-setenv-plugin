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
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Fetches secrets from AWS Secrets Manager (or reuses local cache) and merges them
 * into the shared `.env` file.
 *
 * For each configured [Secrets] block:
 * 1. Checks the per-secret cache file; skips AWS if present and `refresh` is false.
 * 2. Authenticates via STS identity check, running [loginCommand] if needed.
 * 3. Fetches the secret (JSON or plaintext) from AWS Secrets Manager.
 * 4. Applies key renaming and filtering.
 * 5. Writes to the cache file.
 *
 * After all secrets are processed, merges every cache file into the final `.env`,
 * preserving comments and manual edits.
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
     * The final `.env` file that all secret cache files are merged into.
     * Marked `@Internal` (not `@OutputFile`) because [CreateEnvTask] already declares
     * this file as its output; declaring it on both tasks would cause Gradle to report
     * overlapping outputs in a real build.
     */
    @get:Internal
    abstract val envFile: RegularFileProperty

    /**
     * Pluggable command executor for unit testing without calling live AWS CLI.
     *
     * @param command the shell command to execute
     * @param timeoutSeconds maximum time allowed before the process is killed
     * @param writeOutput when `true`, captured output is printed to stdout
     * @return the combined stdout/stderr output of the command
     */
    @get:Internal
    var commandExecutor: (command: String, timeoutSeconds: Int, writeOutput: Boolean) -> String =
        { cmd, timeout, write -> CommandExecutor.execute(cmd, timeout, write) }

    /**
     * Task action: processes all configured secrets and merges results into the `.env` file.
     *
     * Skips gracefully (no-op) when no secrets blocks are configured.
     */
    @TaskAction
    fun createSecretsFile() {
        if (secrets.isEmpty()) {
            logger.lifecycle("No secrets blocks configured; skipping secret fetch")
            return
        }

        logger.lifecycle("targeting - environment:${target.get()} region:${region.get()}")

        val cacheFiles = mutableListOf<java.io.File>()
        val shouldRefresh = refresh.get()

        secrets.forEach { secret ->
            if (secret.environment.isPresent) {
                logger.lifecycle(
                    "overriding target environment:${target.get()} with:${secret.environment.get()} for ${secret.name}",
                )
            }
            if (secret.region.isPresent) {
                logger.lifecycle(
                    "overriding target region:${region.get()} with:${secret.region.get()} for ${secret.name}",
                )
            }

            val targetSecretEnv = secret.environment.orElse(target).get()
            val targetSecretRegion = secret.region.orElse(region).get()
            val propertiesMap = PropertyMapBuilder.build(project, targetSecretEnv, targetSecretRegion)
            val substitutor = StringSubstitutor(propertiesMap).apply { setValueDelimiter(':') }

            val cacheFile = secret.secretsFile.get().asFile
            cacheFiles.add(cacheFile)

            if (!shouldRefresh && cacheFile.exists()) {
                logger.lifecycle("Using cached secrets for ${secret.name} from ${cacheFile.name}")
                return@forEach
            }

            // We're about to hit AWS — make sure we can authenticate first.
            authenticate(secret.profile.get())

            val targetSecretId = substitutor.replace(secret.secretId.get())

            val result: String = fetchAwsSecret(secret, targetSecretId, propertiesMap)

            // Parse JSON secret or treat as a single plaintext value
            val remoteProperties: MutableMap<String, Any?> = if (!secret.plaintext.get()) {
                parseJsonSecret(result, secret.name)
            } else {
                val key = secret.plaintextKey.orNull
                    ?: throw IllegalStateException(
                        "Secret '${secret.name}' has plaintext = true but no plaintextKey configured. " +
                            "Set plaintextKey to the env variable name, e.g. plaintextKey = 'API_TOKEN'",
                    )
                mutableMapOf(key to result.trim())
            }

            // Rename → filter, as a pure pipeline (no mutable state)
            val renameKeys = secret.renameKeys.orNull ?: emptyMap()
            val filterKeys = secret.secretKeys.orNull ?: emptyList()

            val selectedProperties: Map<String, String> = remoteProperties
                .mapKeys { (original, _) -> renameKeys[original] ?: original }
                .filterKeys { key -> filterKeys.isEmpty() || key in filterKeys || key in renameKeys.values }
                .mapValues { (_, value) -> value.toString() }

            // Write to per-secret cache file, merging with any previously cached keys
            val cached = EnvFileIO.read(cacheFile)
            cached.putAll(selectedProperties)
            EnvFileIO.write(cacheFile, cached)
        }

        // Merge all cache files into the shared .env, preserving comments and manual edits
        if (cacheFiles.isNotEmpty()) {
            EnvFileIO.mergeInto(envFile.get().asFile, *cacheFiles.toTypedArray())
            logger.lifecycle("Merged cached secrets into ${envFile.get().asFile.name}")
        }
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
        } catch (e: Exception) {
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
