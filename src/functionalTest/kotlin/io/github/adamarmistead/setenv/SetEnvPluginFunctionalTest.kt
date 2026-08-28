package io.github.adamarmistead.setenv

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File


class SetEnvPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private val buildFile by lazy { projectDir.resolve("build.gradle") }
    private val settingsFile by lazy { projectDir.resolve("settings.gradle") }

    private fun runner(vararg args: String) = GradleRunner.create()
        .forwardOutput()
        .withPluginClasspath()
        .withArguments(*args)
        .withProjectDir(projectDir)

    @Test
    fun `plugin applies and registers all tasks`() {
        settingsFile.writeText("")
        buildFile.writeText(
            """
            plugins {
                id('io.github.adamarmistead.setenv')
            }
            """.trimIndent(),
        )

        val result = runner("tasks", "--all").build()

        assertTrue(result.output.contains("setEnv"))
        assertTrue(result.output.contains("createEnv"))
        assertTrue(result.output.contains("createSecrets"))
    }

    @Nested
    inner class CreateEnv {

        @Test
        fun `createEnv writes env file with configured variables`() {
            settingsFile.writeText("")
            buildFile.writeText(
                """
                plugins {
                    id('io.github.adamarmistead.setenv')
                }

                env {
                    target = 'dev'
                    region = 'use1'
                    environmentVariables = [
                        APP_NAME: 'my-app',
                        TARGET: '${'$'}{target}',
                        REGION: '${'$'}{region}',
                    ]
                }
                """.trimIndent(),
            )

            runner("createEnv").build()

            val envFile = projectDir.resolve(".env")
            assertTrue(envFile.exists(), "Expected .env file to be created")
            val content = envFile.readText()
            assertTrue(content.contains("APP_NAME=my-app"))
            assertTrue(content.contains("TARGET=dev"))
            assertTrue(content.contains("REGION=us-east-1"))
        }

        @Test
        fun `single-line env variable is not encoded`() {
            settingsFile.writeText("")
            buildFile.writeText(
                """
                plugins {
                    id('io.github.adamarmistead.setenv')
                }

                env {
                    target = 'dev'
                    region = 'use1'
                    environmentVariables = [
                        API_KEY: 'ghp_abc123xyz',
                    ]
                }
                """.trimIndent(),
            )

            runner("createEnv").build()

            val envFile = projectDir.resolve(".env")
            val lines = envFile.readLines()
            assertEquals("API_KEY=ghp_abc123xyz", lines.first { it.startsWith("API_KEY=") })
        }

        @Test
        fun `createEnv adds env file to gitignore`() {
            settingsFile.writeText("")
            buildFile.writeText(
                """
                plugins {
                    id('io.github.adamarmistead.setenv')
                }

                env {
                    target = 'dev'
                    region = 'use1'
                    environmentVariables = [APP: 'test']
                }
                """.trimIndent(),
            )

            runner("createEnv").build()

            val gitignore = projectDir.resolve(".gitignore")
            assertTrue(gitignore.exists(), ".gitignore should be created")
            assertTrue(gitignore.readText().contains(".env*"))
        }
    }

    @Nested
    inner class CreateSecrets {

        @Test
        fun `createSecrets merges cached secret into env file`() {
            settingsFile.writeText("")
            buildFile.writeText(
                """
                plugins {
                    id('io.github.adamarmistead.setenv')
                }

                env {
                    target = 'dev'
                    region = 'use1'
                    environmentVariables = [APP: 'my-app']

                    secrets {
                        db {
                            secretId = 'myapp/dev/db'
                        }
                    }
                }
                """.trimIndent(),
            )

            // Pre-populate the cache file (.env-dev) that createSecrets will merge from
            val cacheFile = projectDir.resolve(".env-dev")
            cacheFile.writeText("DB_HOST=db.example.com\nDB_PASS=s3cret\n")

            runner("createSecrets").build()

            val envFile = projectDir.resolve(".env")
            assertTrue(envFile.exists(), ".env should be created")
            val content = envFile.readText()
            assertTrue(content.contains("DB_HOST=db.example.com"))
            assertTrue(content.contains("DB_PASS=s3cret"))
        }

        @Test
        fun `createSecrets preserves manual entries in env file`() {
            settingsFile.writeText("")
            buildFile.writeText(
                """
                plugins {
                    id('io.github.adamarmistead.setenv')
                }

                env {
                    target = 'dev'
                    region = 'use1'
                    environmentVariables = [APP: 'my-app']

                    secrets {
                        db {
                            secretId = 'myapp/dev/db'
                        }
                    }
                }
                """.trimIndent(),
            )

            // Pre-populate cache
            val cacheFile = projectDir.resolve(".env-dev")
            cacheFile.writeText("DB_HOST=new-host\n")

            // Pre-create .env with a manual entry and a comment
            val envFile = projectDir.resolve(".env")
            envFile.writeText("# My manual config\nMANUAL_KEY=keep-me\nAPP=my-app\n")

            runner("createSecrets").build()

            val content = envFile.readText()
            assertTrue(content.contains("# My manual config"), "Comment should be preserved")
            assertTrue(content.contains("MANUAL_KEY=keep-me"), "Manual entry should be preserved")
            assertTrue(content.contains("DB_HOST=new-host"), "Secret should be merged in")
        }
    }

    @Nested
    inner class SetEnvOrchestrator {

        @Test
        fun `setEnv runs createEnv and createSecrets in order`() {
            settingsFile.writeText("")
            buildFile.writeText(
                """
                plugins {
                    id('io.github.adamarmistead.setenv')
                }

                env {
                    target = 'dev'
                    region = 'use1'
                    environmentVariables = [
                        APP: 'my-app',
                        REGION: '${'$'}{region}',
                    ]

                    secrets {
                        db {
                            secretId = 'myapp/dev/db'
                        }
                    }
                }
                """.trimIndent(),
            )

            // Pre-populate cache so createSecrets doesn't need AWS
            val cacheFile = projectDir.resolve(".env-dev")
            cacheFile.writeText("DB_HOST=db.example.com\n")

            val result = runner("setEnv").build()

            // Both tasks should have run
            assertTrue(result.output.contains("createEnv") || result.output.contains("targeting"))
            assertTrue(result.output.contains("createSecrets") || result.output.contains("Merged"))

            // .env should have both the env var and the secret
            val envFile = projectDir.resolve(".env")
            val content = envFile.readText()
            assertTrue(content.contains("APP=my-app"))
            assertTrue(content.contains("REGION=us-east-1"))
            assertTrue(content.contains("DB_HOST=db.example.com"))
        }

        @Test
        fun `setEnv with no secrets still creates env file`() {
            settingsFile.writeText("")
            buildFile.writeText(
                """
                plugins {
                    id('io.github.adamarmistead.setenv')
                }

                env {
                    target = 'dev'
                    region = 'use1'
                    environmentVariables = [APP: 'my-app']
                }
                """.trimIndent(),
            )

            val result = runner("setEnv").build()

            val envFile = projectDir.resolve(".env")
            assertTrue(envFile.exists())
            assertTrue(envFile.readText().contains("APP=my-app"))
        }
    }

    @Nested
    inner class CliOptions {

        @Test
        fun `--target and --region override DSL values`() {
            settingsFile.writeText("")
            buildFile.writeText(
                """
                plugins {
                    id('io.github.adamarmistead.setenv')
                }

                env {
                    target = 'dev'
                    region = 'use1'
                    environmentVariables = [
                        TARGET: '${'$'}{target}',
                        REGION: '${'$'}{region}',
                    ]
                }
                """.trimIndent(),
            )

            // Override to prd/usw2 via CLI (options are on the setEnv task)
            runner("setEnv", "--target", "prd", "--region", "usw2").build()

            val envFile = projectDir.resolve(".env")
            val content = envFile.readText()
            assertTrue(content.contains("TARGET=prd"), "Should use prd target")
            assertTrue(content.contains("REGION=us-west-2"), "Should use us-west-2 region")
        }
    }

    @Nested
    inner class CiMode {

        @Test
        fun `tasks are disabled when CI environment variable is set`() {
            settingsFile.writeText("")
            buildFile.writeText(
                """
                plugins {
                    id('io.github.adamarmistead.setenv')
                }

                env {
                    target = 'dev'
                    region = 'use1'
                    environmentVariables = [APP: 'my-app']
                }
                """.trimIndent(),
            )

            // Simulate CI by setting the env var for the Gradle process
            val result = GradleRunner.create()
                .forwardOutput()
                .withPluginClasspath()
                .withArguments("setEnv")
                .withProjectDir(projectDir)
                .withEnvironment(mapOf("CI" to "true"))
                .build()

            // Tasks should be skipped (disabled)
            val envFile = projectDir.resolve(".env")
            assertFalse(envFile.exists(), ".env should NOT be created in CI mode")
        }
    }
}
