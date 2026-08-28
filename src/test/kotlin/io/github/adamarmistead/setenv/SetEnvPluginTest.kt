package io.github.adamarmistead.setenv

import io.github.adamarmistead.setenv.dsl.EnvExtension
import io.github.adamarmistead.setenv.internal.ProjectUtils
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SetEnvPluginTest {

    @Nested
    inner class TaskRegistration {

        @Test
        fun `plugin registers all tasks`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            assertNotNull(project.tasks.findByName("setEnv"))
            assertNotNull(project.tasks.findByName("createEnv"))
            assertNotNull(project.tasks.findByName("createSecrets"))
        }

        @Test
        fun `setEnv task depends on createEnv and createSecrets`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val setEnv = project.tasks.getByName("setEnv")
            val deps = setEnv.taskDependencies.getDependencies(setEnv).map { it.toString() }
            assertTrue(deps.any { it.contains("createEnv") }, "setEnv should depend on createEnv, got: $deps")
            assertTrue(deps.any { it.contains("createSecrets") }, "setEnv should depend on createSecrets, got: $deps")
        }
    }

    @Nested
    inner class ExtensionDefaults {

        @Test
        fun `plugin creates env extension with default conventions`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val extension = project.extensions.getByType(EnvExtension::class.java)
            assertEquals("dev", extension.target.get())
            assertEquals("us-east-1", extension.region.get())
            assertEquals(emptyMap<String, String>(), extension.environmentVariables.get())
        }

        @Test
        fun `secrets container supports creating named secrets`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val extension = project.extensions.getByType(EnvExtension::class.java)
            extension.secrets.create("db").apply {
                secretId.set("myapp/\${target}/db")
                renameKeys.set(mapOf("username" to "DB_USER"))
            }

            val secret = extension.secrets.getByName("db")
            assertEquals("myapp/\${target}/db", secret.secretId.get())
            assertEquals(mapOf("username" to "DB_USER"), secret.renameKeys.get())
        }
    }

    @Nested
    inner class PathValidation {

        @Test
        fun `envFile within project directory is accepted`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val extension = project.extensions.getByType(EnvExtension::class.java)
            // Default is already within project dir — should not throw
            val projectDir = project.rootProject.projectDir
            ProjectUtils.validatePathWithinProject(
                projectDir.resolve(".env"),
                projectDir,
                "envFile",
            )
        }

        @Test
        fun `envFile in subdirectory is accepted`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val projectDir = project.rootProject.projectDir
            ProjectUtils.validatePathWithinProject(
                projectDir.resolve("config/.env"),
                projectDir,
                "envFile",
            )
        }

        @Test
        fun `envFile outside project directory is rejected`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val projectDir = project.rootProject.projectDir
            val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                ProjectUtils.validatePathWithinProject(
                    java.io.File("/tmp/.env"),
                    projectDir,
                    "envFile",
                )
            }
            assertTrue(ex.message!!.contains("outside the project directory"))
            assertTrue(ex.message!!.contains("envFile"))
        }

        @Test
        fun `envFile with path traversal is rejected`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val projectDir = project.rootProject.projectDir
            val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                ProjectUtils.validatePathWithinProject(
                    projectDir.resolve("../../etc/passwd"),
                    projectDir,
                    "envFile",
                )
            }
            assertTrue(ex.message!!.contains("outside the project directory"))
        }

        @Test
        fun `secretsFile outside project directory is rejected`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val projectDir = project.rootProject.projectDir
            val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                ProjectUtils.validatePathWithinProject(
                    java.io.File("/var/secrets/.env-dev"),
                    projectDir,
                    "secretsFile for 'db'",
                )
            }
            assertTrue(ex.message!!.contains("secretsFile for 'db'"))
            assertTrue(ex.message!!.contains("outside the project directory"))
        }
    }
}
