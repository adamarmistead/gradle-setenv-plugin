package io.github.adamarmistead.setenv.task

import io.github.adamarmistead.setenv.constants.Defaults
import io.github.adamarmistead.setenv.internal.EnvFileIO
import io.github.adamarmistead.setenv.internal.PropertyMapBuilder
import org.apache.commons.text.StringSubstitutor
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Writes the `.env` file with static and interpolated environment variables.
 *
 * Reads the `environmentVariables` map from the DSL, performs placeholder substitution
 * (e.g. `${target}`, `${region}`), and writes the result to the configured output file.
 *
 * If no variables are configured, the task is a no-op (no file is created).
 */
abstract class CreateEnvTask @Inject constructor(
    /** The Gradle project, used to resolve properties for placeholder substitution. */
    @Internal private val project: Project,
) : DefaultTask() {

    init {
        group = Defaults.TASK_GROUP
        description = "Create .env file and populate with the provided environment variables"
    }

    /** The target environment name (e.g. `dev`, `stg`). */
    @get:Input
    abstract val target: Property<String>

    /** The AWS region (full name or short code). */
    @get:Input
    abstract val region: Property<String>

    /**
     * Static environment variables to write into the `.env` file.
     * Values support placeholder substitution (e.g. `"${target}"`).
     */
    @get:Input
    abstract val environmentVariables: MapProperty<String, String>

    /** The output `.env` file path. */
    @get:OutputFile
    abstract val envFile: RegularFileProperty

    /**
     * Task action: builds the property map, substitutes placeholders in all configured
     * variables, and writes the result to [envFile].
     *
     * No-op when [environmentVariables] is empty.
     */
    @TaskAction
    fun createEnvFile() {
        logger.lifecycle("targeting - environment:${target.get()} region:${region.get()}")

        val propertiesMap = PropertyMapBuilder.build(project, target.get(), region.get())
        val substitutor = StringSubstitutor(propertiesMap).apply {
            setValueDelimiter(':')
        }

        val variables = environmentVariables.get()
        if (variables.isNotEmpty()) {
            val substituted = variables.mapValues { (_, value) -> substitutor.replace(value) }
            // Use EnvFileIO.write() so the file gets 0600 permissions and a .gitignore entry,
            // even when createSecrets is skipped (e.g. no secrets configured).
            EnvFileIO.write(envFile.get().asFile, substituted)
        }
    }
}
