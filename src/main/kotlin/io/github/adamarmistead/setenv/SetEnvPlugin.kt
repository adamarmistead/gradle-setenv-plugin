package io.github.adamarmistead.setenv

import io.github.adamarmistead.setenv.constants.AwsCli
import io.github.adamarmistead.setenv.constants.Defaults
import io.github.adamarmistead.setenv.dsl.EnvExtension
import io.github.adamarmistead.setenv.dsl.Secrets
import io.github.adamarmistead.setenv.internal.ProjectUtils
import io.github.adamarmistead.setenv.task.CreateEnvTask
import io.github.adamarmistead.setenv.task.CreateSecretsTask
import io.github.adamarmistead.setenv.task.SetEnvTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * Entry point for the `io.github.adamarmistead.setenv` Gradle plugin.
 *
 * Wires together the [EnvExtension] DSL, the three tasks ([SetEnvTask], [CreateEnvTask],
 * [CreateSecretsTask]), and applies conventions so that a minimal build script works out of the box.
 *
 * Usage:
 * ```kotlin
 * plugins { id("io.github.adamarmistead.setenv") }
 * env { target = "dev" }
 * ```
 */
@Suppress("unused") // Loaded by Gradle via reflection from the plugin descriptor
class SetEnvPlugin : Plugin<Project> {

    /**
     * Applies the plugin to [project]: registers the `env` extension, creates the three tasks,
     * wires conventions, and performs post-evaluation validation.
     *
     * @param project the Gradle project this plugin is applied to
     */
    override fun apply(project: Project) {
        val secretsContainer = project.objects.domainObjectContainer(Secrets::class.java)
        val envExtension = project.extensions.create("env", EnvExtension::class.java, secretsContainer)

        envExtension.target.convention(Defaults.ENV)
        envExtension.region.convention(Defaults.REGION)
        envExtension.envFile.convention(project.rootProject.layout.projectDirectory.file(Defaults.ENV_FILE))
        envExtension.environmentVariables.convention(emptyMap())
        envExtension.regions.convention(Defaults.REGIONS)
        envExtension.regionShortCodes.convention(Defaults.REGION_SHORT_CODES)

        val setEnv: TaskProvider<SetEnvTask> = project.tasks.register("setEnv", SetEnvTask::class.java) { task ->
            task.target.convention(envExtension.target)
            task.region.convention(envExtension.region)
            task.refresh.convention(false)
        }

        val createEnv: TaskProvider<CreateEnvTask> = project.tasks.register("createEnv", CreateEnvTask::class.java) { task ->
            task.envFile.set(envExtension.envFile)
            task.environmentVariables.set(envExtension.environmentVariables)
            task.target.set(setEnv.flatMap { it.target })
            task.region.set(setEnv.flatMap { it.region })
        }

        val createSecrets: TaskProvider<CreateSecretsTask> = project.tasks.register("createSecrets", CreateSecretsTask::class.java) { task ->
            task.commandTimeout.convention(Defaults.TIMEOUT_SECONDS)
            task.loginTimeout.convention(Defaults.LOGIN_TIMEOUT_SECONDS)
            task.loginCommand.convention(AwsCli.LOGIN)
            task.envFile.set(envExtension.envFile)
            task.target.set(setEnv.flatMap { it.target })
            task.region.set(setEnv.flatMap { it.region })
            task.refresh.set(setEnv.flatMap { it.refresh })
            task.cacheFile.convention(
                setEnv.flatMap { t ->
                    t.target.map { env ->
                        project.rootProject.layout.projectDirectory.file(Defaults.cacheFileName(env))
                    }
                },
            )
            task.mustRunAfter(createEnv)
        }

        setEnv.configure { task ->
            task.dependsOn(createEnv, createSecrets)
        }

        secretsContainer.configureEach { secret ->
            secret.profile.convention(Defaults.PROFILE)
            secret.secretKeys.convention(emptyList())
            secret.plaintext.convention(Defaults.IS_PLAINTEXT)
            secret.renameKeys.convention(emptyMap())
        }

        project.afterEvaluate {
            createSecrets.configure { task ->
                task.secrets.addAll(secretsContainer)
            }

            // Validate that all file paths are within the project directory
            val projectDir = project.rootProject.projectDir.absoluteFile.normalize()
            ProjectUtils.validatePathWithinProject(envExtension.envFile.get().asFile, projectDir, "envFile")
            ProjectUtils.validatePathWithinProject(
                createSecrets.get().cacheFile.get().asFile,
                projectDir,
                "cacheFile",
            )

            if (System.getenv("CI") != null) {
                createEnv.configure { it.enabled = false }
                createSecrets.configure { it.enabled = false }
                setEnv.configure { it.enabled = false }
            }
        }
    }
}
