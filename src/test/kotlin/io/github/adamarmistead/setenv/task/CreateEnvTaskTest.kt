package io.github.adamarmistead.setenv.task

import io.github.adamarmistead.setenv.internal.EnvFileIO
import io.github.adamarmistead.setenv.internal.PropertyMapBuilder
import org.apache.commons.text.StringSubstitutor
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CreateEnvTaskTest {

    @TempDir
    lateinit var projectDir: File

    @Nested
    inner class EnvFileGeneration {

        @Test
        fun `writes env file with placeholder substitution`() {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val envFile = File(projectDir, "test.env")
            val createEnv = project.tasks.named("createEnv", CreateEnvTask::class.java).get()
            createEnv.target.set("dev")
            createEnv.region.set("use1")
            createEnv.envFile.set(envFile)
            createEnv.environmentVariables.set(
                mapOf(
                    "TARGET" to "\${target}",
                    "REGION" to "\${region}",
                    "APP_NAME" to "my-app",
                ),
            )

            createEnv.createEnvFile()

            val lines = envFile.readLines().toSet()
            assertEquals(
                setOf(
                    "TARGET=dev",
                    "APP_NAME=my-app",
                    "REGION=us-east-1",
                ),
                lines,
            )
        }

        @Test
        fun `does not write env file when no environment variables are configured`() {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val envFile = File(projectDir, "empty.env")
            val createEnv = project.tasks.named("createEnv", CreateEnvTask::class.java).get()
            createEnv.target.set("dev")
            createEnv.region.set("use1")
            createEnv.envFile.set(envFile)
            createEnv.environmentVariables.set(emptyMap())

            createEnv.createEnvFile()

            assertFalse(envFile.exists(), "env file should not be created when no variables are configured")
        }
    }

    @Nested
    inner class SubstitutorBehavior {

        @Test
        fun `substitutor uses actual value when property exists`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val properties = PropertyMapBuilder.build(project, "dev", "use1")
            val substitutor = StringSubstitutor(properties).apply {
                setValueDelimiter(':')
            }

            assertEquals("dev", substitutor.replace("\${target:unknown}"))
            assertEquals("us-east-1", substitutor.replace("\${region:unknown}"))
        }

        @Test
        fun `substitutor uses default when property is missing`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val properties = PropertyMapBuilder.build(project, "dev", "use1")
            val substitutor = StringSubstitutor(properties).apply {
                setValueDelimiter(':')
            }

            // "nonexistent" is not in the property map, so the default should be used
            assertEquals("fallback-value", substitutor.replace("\${nonexistent:fallback-value}"))
            assertEquals("my-default", substitutor.replace("\${MISSING_PROP:my-default}"))
            assertEquals("", substitutor.replace("\${does_not_exist:}"))
        }

        @Test
        fun `substitutor leaves unknown property without default as-is`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val properties = PropertyMapBuilder.build(project, "dev", "use1")
            val substitutor = StringSubstitutor(properties).apply {
                setValueDelimiter(':')
            }

            // No default provided — StringSubstitutor leaves it as the literal text
            assertEquals("\${unknown_prop}", substitutor.replace("\${unknown_prop}"))
        }

        @Test
        fun `env file generation uses defaults for missing properties`() {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val envFile = File(projectDir, "defaults.env")
            val createEnv = project.tasks.named("createEnv", CreateEnvTask::class.java).get()
            createEnv.target.set("dev")
            createEnv.region.set("use1")
            createEnv.envFile.set(envFile)
            createEnv.environmentVariables.set(
                mapOf(
                    "KNOWN" to "\${target}",
                    "UNKNOWN_WITH_DEFAULT" to "\${nonexistent:fallback-42}",
                    "UNKNOWN_NO_DEFAULT" to "\${totally_missing}",
                ),
            )

            createEnv.createEnvFile()

            val env = EnvFileIO.read(envFile)
            assertEquals("dev", env["KNOWN"])
            assertEquals("fallback-42", env["UNKNOWN_WITH_DEFAULT"])
            // No default → left as literal placeholder text
            assertEquals("\${totally_missing}", env["UNKNOWN_NO_DEFAULT"])
        }

        @Test
        fun `re-running createEnv does not destroy secrets already merged into the file`() {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val envFile = File(projectDir, "regression.env")
            val createEnv = project.tasks.named("createEnv", CreateEnvTask::class.java).get()
            createEnv.target.set("dev")
            createEnv.region.set("use1")
            createEnv.envFile.set(envFile)
            createEnv.environmentVariables.set(
                mapOf(
                    "APP_NAME" to "my-app",
                    "AWS_REGION" to "\${region}",
                ),
            )

            // First run: writes static vars
            createEnv.createEnvFile()

            // Simulate createSecrets merging secrets into the same file
            EnvFileIO.mergeProperties(envFile, mapOf("DB_PASSWORD" to "secret123", "API_KEY" to "abc456"))

            // Second run: re-runs (as Gradle would when it detects output changed)
            createEnv.createEnvFile()

            // Static vars still present and correct
            val env = EnvFileIO.read(envFile)
            assertEquals("my-app", env["APP_NAME"])
            assertEquals("us-east-1", env["AWS_REGION"])

            // Secrets survived the re-run
            assertEquals("secret123", env["DB_PASSWORD"])
            assertEquals("abc456", env["API_KEY"])
        }

        @Test
        fun `re-running createEnv with changed region updates static vars but preserves secrets`() {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val envFile = File(projectDir, "region-switch.env")
            val createEnv = project.tasks.named("createEnv", CreateEnvTask::class.java).get()
            createEnv.target.set("dev")
            createEnv.region.set("use1")
            createEnv.envFile.set(envFile)
            createEnv.environmentVariables.set(
                mapOf(
                    "APP_NAME" to "my-app",
                    "AWS_REGION" to "\${region}",
                ),
            )

            // First run with use1
            createEnv.createEnvFile()
            EnvFileIO.mergeProperties(envFile, mapOf("DB_PASSWORD" to "secret123"))

            // Switch region and re-run
            createEnv.region.set("use2")
            createEnv.createEnvFile()

            val env = EnvFileIO.read(envFile)
            assertEquals("my-app", env["APP_NAME"])
            assertEquals("us-east-2", env["AWS_REGION"])  // updated (use2 → us-east-2)
            assertEquals("secret123", env["DB_PASSWORD"])  // preserved
        }
    }
}
