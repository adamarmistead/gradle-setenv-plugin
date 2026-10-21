package io.github.adamarmistead.setenv.task

import io.github.adamarmistead.setenv.dsl.EnvExtension
import io.github.adamarmistead.setenv.dsl.Secrets
import io.github.adamarmistead.setenv.internal.CommandExecutor
import io.github.adamarmistead.setenv.internal.EnvFileIO
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CreateSecretsTaskTest {

    @TempDir
    lateinit var projectDir: File

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun newProject(): org.gradle.api.Project {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply("io.github.adamarmistead.setenv")
        return project
    }

    private fun wireSecret(
        project: org.gradle.api.Project,
        name: String,
        secretId: String = $$"myapp/${target}/$$name",
        plaintext: Boolean = false,
        plaintextKey: String = "RAW_VALUE",
        renameKeys: Map<String, String>? = null,
        secretKeys: List<String>? = null,
    ): Secrets {
        val extension = project.extensions.getByType(EnvExtension::class.java)
        val secret = extension.secrets.create(name)
        secret.secretId.set(secretId)
        secret.plaintext.set(plaintext)
        if (plaintext) secret.plaintextKey.set(plaintextKey)
        renameKeys?.let { secret.renameKeys.set(it) }
        secretKeys?.let { secret.secretKeys.set(it) }

        // Simulate afterEvaluate: copy from extension container to task container
        val createSecrets = project.tasks.named("createSecrets", CreateSecretsTask::class.java).get()
        createSecrets.secrets.add(secret)
        return secret
    }

    private fun setupTask(
        project: org.gradle.api.Project,
        env: String = "dev",
        region: String = "use1",
        refresh: Boolean = true,
        envFile: File = File(projectDir, ".env"),
        cacheFile: File = File(projectDir, ".env-dev"),
    ): CreateSecretsTask {
        val task = project.tasks.named("createSecrets", CreateSecretsTask::class.java).get()
        task.target.set(env)
        task.region.set(region)
        task.refresh.set(refresh)
        task.envFile.set(envFile)
        task.cacheFile.set(cacheFile)
        return task
    }

    // ─── No DSL / No Secrets ──────────────────────────────────────────────────

    @Nested
    inner class NoSecretsConfigured {

        @Test
        fun `skips gracefully when no secrets blocks are configured`() {
            val project = newProject()
            val task = setupTask(project)

            // Should not throw
            task.createSecretsFile()

            // No .env file should be created
            assertFalse(File(projectDir, ".env").exists())
        }

        @Test
        fun `plugin applies cleanly with no DSL at all`() {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            // Both tasks should exist and be runnable
            val createEnv = project.tasks.named("createEnv", CreateEnvTask::class.java).get()
            val createSecrets = project.tasks.named("createSecrets", CreateSecretsTask::class.java).get()

            createEnv.target.set("dev")
            createEnv.region.set("use1")
            createEnv.envFile.set(File(projectDir, ".env"))
            createEnv.environmentVariables.set(emptyMap())

            createSecrets.target.set("dev")
            createSecrets.region.set("use1")
            createSecrets.refresh.set(true)
            createSecrets.envFile.set(File(projectDir, ".env"))
            createSecrets.cacheFile.set(File(projectDir, ".env-dev"))

            // Neither should throw
            createEnv.createEnvFile()
            createSecrets.createSecretsFile()
        }
    }

    // ─── JSON Secrets ─────────────────────────────────────────────────────────

    @Nested
    inner class JsonSecrets {

        @Test
        fun `fetches and parses JSON secret with key remapping`() {
            val project = newProject()
            wireSecret(
                project, "db",
                renameKeys = mapOf("username" to "DB_USER", "password" to "DB_PASS"),
                secretKeys = listOf("DB_USER", "DB_PASS"),
            )
            val task = setupTask(project)

            val executed = mutableListOf<String>()
            task.commandExecutor = { cmd, _, _ ->
                executed.add(cmd)
                if (cmd.contains("get-secret-value")) {
                    """{"username": "admin", "password": "s3cret", "unused": "x"}"""
                } else ""
            }

            task.createSecretsFile()

            assertTrue(executed.any { it.contains("aws sso login --profile default") })
            assertTrue(executed.any { it.contains("--secret-id myapp/dev/db") })

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("admin", env["DB_USER"])
            assertEquals("s3cret", env["DB_PASS"])
            assertFalse(env.containsKey("unused"))
        }

        @Test
        fun `fetches JSON secret without key filter returns all keys`() {
            val project = newProject()
            wireSecret(project, "all")
            val task = setupTask(project)

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) {
                    """{"host": "db.example.com", "port": "5432", "user": "root"}"""
                } else ""
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("db.example.com", env["host"])
            assertEquals("5432", env["port"])
            assertEquals("root", env["user"])
        }
    }

    // ─── Plaintext (Raw String) Secrets ───────────────────────────────────────

    @Nested
    inner class PlaintextSecrets {

        @Test
        fun `fetches plaintext secret and stores under configured key`() {
            val project = newProject()
            wireSecret(
                project, "token",
                plaintext = true,
                plaintextKey = "API_TOKEN",
            )
            val task = setupTask(project)

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) {
                    "ghp_abc123xyz"
                } else ""
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("ghp_abc123xyz", env["API_TOKEN"])
        }

        @Test
        fun `plaintext secret with custom key name`() {
            val project = newProject()
            wireSecret(
                project, "raw",
                plaintext = true,
                plaintextKey = "MY_RAW_SECRET",
            )
            val task = setupTask(project)

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) {
                    "raw-value-123"
                } else ""
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("raw-value-123", env["MY_RAW_SECRET"])
        }
    }

    // ─── Multiple Secret Blocks ───────────────────────────────────────────────

    @Nested
    inner class MultipleSecrets {

        @Test
        fun `merges multiple secret blocks into single env file`() {
            val project = newProject()
            wireSecret(
                project, "db",
                renameKeys = mapOf("username" to "DB_USER"),
                secretKeys = listOf("DB_USER"),
            )
            wireSecret(
                project, "api",
                secretId = $$"myapp/${target}/api-keys",
            )
            val task = setupTask(project)

            task.commandExecutor = { cmd, _, _ ->
                when {
                    cmd.contains("myapp/dev/db") -> """{"username": "dbadmin", "password": "p1"}"""
                    cmd.contains("myapp/dev/api-keys") -> """{"api_key": "sk-123", "api_secret": "sec-456"}"""
                    else -> ""
                }
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("dbadmin", env["DB_USER"])
            assertEquals("sk-123", env["api_key"])
            assertEquals("sec-456", env["api_secret"])
        }

        @Test
        fun `all secrets land in a single cache file`() {
            val project = newProject()
            wireSecret(project, "a")
            wireSecret(project, "b")
            val task = setupTask(project)

            task.commandExecutor = { cmd, _, _ ->
                when {
                    cmd.contains("myapp/dev/a") -> """{"key_a": "val_a"}"""
                    cmd.contains("myapp/dev/b") -> """{"key_b": "val_b"}"""
                    else -> ""
                }
            }

            task.createSecretsFile()

            val cacheFile = File(projectDir, ".env-dev")
            assertTrue(cacheFile.exists(), "Single cache file should exist")

            val cacheProps = EnvFileIO.read(cacheFile)
            assertEquals("val_a", cacheProps["key_a"])
            assertEquals("val_b", cacheProps["key_b"])
        }
    }

    // ─── Cache File Output ────────────────────────────────────────────────────
    // Note: Gradle's up-to-date mechanism handles "skip if unchanged" at the build level.
    // The task action ALWAYS fetches from AWS when it runs — these tests verify that
    // the cache file is written correctly as a complete snapshot each time.

    @Nested
    inner class CacheFileOutput {

        @Test
        fun `writes complete snapshot to cache file on every run`() {
            val project = newProject()
            wireSecret(project, "db")
            val task = setupTask(project)

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) """{"DB_USER": "admin", "DB_PASS": "s3cret"}""" else ""
            }

            task.createSecretsFile()

            val cache = EnvFileIO.read(File(projectDir, ".env-dev"))
            assertEquals("admin", cache["DB_USER"])
            assertEquals("s3cret", cache["DB_PASS"])
        }

        @Test
        fun `second run overwrites cache with new AWS data (no accumulation)`() {
            val project = newProject()
            wireSecret(project, "db")
            val task = setupTask(project)

            // First run: AWS returns key A
            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) """{"KEY_A": "a1"}""" else ""
            }
            task.createSecretsFile()

            // Second run: AWS now returns different content (key B replaces key A)
            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) """{"KEY_B": "b1"}""" else ""
            }
            task.createSecretsFile()

            // Cache should reflect the latest AWS state (not accumulate stale keys)
            val cache = EnvFileIO.read(File(projectDir, ".env-dev"))
            assertEquals("b1", cache["KEY_B"])
            assertFalse(cache.containsKey("KEY_A"), "Stale key from previous fetch should be gone")
        }

        @Test
        fun `preserves manual env entries when merging fresh secrets`() {
            val project = newProject()
            wireSecret(project, "db")
            val envFile = File(projectDir, ".env")
            val task = setupTask(project, envFile = envFile)

            // Simulate: .env exists with manual entries
            EnvFileIO.write(envFile, mapOf("MANUAL_KEY" to "manual-value"))

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) """{"FROM_AWS": "recovered"}""" else ""
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(envFile)
            assertEquals("recovered", env["FROM_AWS"])
            // Manual key should be preserved by merge
            assertEquals("manual-value", env["MANUAL_KEY"])
        }
    }

    // ─── Error Handling ───────────────────────────────────────────────────────

    @Nested
    inner class ErrorHandling {

        @Test
        fun `throws when command executor fails`() {
            val project = newProject()
            wireSecret(project, "fail")
            val task = setupTask(project)

            task.commandExecutor = { _, _, _ ->
                throw RuntimeException("Simulated AWS failure (exit code 125)")
            }

            val ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) {
                task.createSecretsFile()
            }
            assertTrue(ex.message!!.contains("Simulated AWS failure"))
        }

        @Test
        fun `task runs cleanly with minimal configuration`() {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val extension = project.extensions.getByType(EnvExtension::class.java)
            val secret = extension.secrets.create("basic")
            secret.secretId.set($$"myapp/${target}/basic")

            val task = project.tasks.named("createSecrets", CreateSecretsTask::class.java).get()
            task.secrets.add(secret)
            task.target.set("dev")
            task.region.set("use1")
            task.refresh.set(false)
            task.envFile.set(File(projectDir, ".env"))
            task.cacheFile.set(File(projectDir, ".env-dev"))

            // Simulate AWS response
            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) """{"TOKEN": "abc123"}""" else ""
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("abc123", env["TOKEN"])
        }
    }

    // ─── Conventions (Defaults) ───────────────────────────────────────────────

    @Nested
    inner class Conventions {

        @Test
        fun `secrets container applies conventions to newly created secrets`() {
            val project = newProject()
            val extension = project.extensions.getByType(EnvExtension::class.java)
            val secret = extension.secrets.create("convention-test")

            // These are all set by the configureEach block in SetEnvPlugin
            assertEquals("default", secret.profile.get())
            assertFalse(secret.plaintext.get())
            assertTrue(secret.secretKeys.get().isEmpty())
            assertTrue(secret.renameKeys.get().isEmpty())
            assertFalse(secret.writeToFile.get())
        }

        @Test
        fun `task conventions provide sensible defaults`() {
            val project = newProject()
            val task = project.tasks.named("createSecrets", CreateSecretsTask::class.java).get()

            assertEquals(60, task.commandTimeout.get())
            assertEquals(300, task.loginTimeout.get())
        }

        @Test
        fun `extension conventions provide sensible defaults`() {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.plugins.apply("io.github.adamarmistead.setenv")
            val extension = project.extensions.getByType(EnvExtension::class.java)

            assertEquals("dev", extension.target.get())
            assertEquals("us-east-1", extension.region.get())
            assertEquals(File(projectDir, ".env"), extension.envFile.get().asFile)
            assertTrue(extension.environmentVariables.get().isEmpty())
        }
    }

    // ─── Validation & Error Messages ──────────────────────────────────────────

    @Nested
    inner class Validation {

        @Test
        fun `plaintext without plaintextKey gives clear error`() {
            val project = newProject()
            val secret = wireSecret(project, "nokey")
            secret.plaintext.set(true)
            // plaintextKey was never set — this is the misconfiguration we're testing

            val task = setupTask(project)
            task.commandExecutor = { _, _, _ -> "some-raw-value" }

            val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                task.createSecretsFile()
            }
            assertTrue(ex.message!!.contains("plaintextKey"), "Should mention plaintextKey")
            assertTrue(ex.message!!.contains("plaintext = true"), "Should reference the plaintext setting")
        }

        @Test
        fun `non-JSON secret without plaintext suggests fix`() {
            val project = newProject()
            wireSecret(project, "plain")
            val task = setupTask(project)

            // Simulate AWS returning a plain string (not JSON)
            task.commandExecutor = { _, _, _ -> "my-plain-password-123" }

            val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                task.createSecretsFile()
            }
            assertTrue(ex.message!!.contains("not valid JSON"), "Should say it's not JSON")
            assertTrue(ex.message!!.contains("plaintext = true"), "Should suggest plaintext = true")
            assertTrue(ex.message!!.contains("plaintextKey"), "Should mention plaintextKey")
        }

        @Test
        fun `empty secret response gives clear error`() {
            val project = newProject()
            wireSecret(project, "empty")
            val task = setupTask(project)

            // Simulate AWS returning empty
            task.commandExecutor = { _, _, _ -> "" }

            val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                task.createSecretsFile()
            }
            assertTrue(ex.message!!.contains("empty response"), "Should mention empty response")
            assertTrue(ex.message!!.contains("secret ID"), "Should suggest checking secret ID")
        }

        @Test
        fun `JSON array secret gives clear error`() {
            val project = newProject()
            wireSecret(project, "array")
            val task = setupTask(project)

            // Simulate AWS returning a JSON array (not object)
            task.commandExecutor = { _, _, _ -> """["item1", "item2"]""" }

            val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                task.createSecretsFile()
            }
            assertTrue(ex.message!!.contains("not an object"), "Should say it's not an object")
            assertTrue(ex.message!!.contains("plaintext = true"), "Should suggest plaintext = true")
        }
    }

    // ─── Rename + Filter Interaction ──────────────────────────────────────────

    @Nested
    inner class RenameAndFilter {

        @Test
        fun `renamed keys survive secretKeys filter`() {
            val project = newProject()
            wireSecret(
                project, "rename-filter",
                renameKeys = mapOf("old_name" to "NEW_NAME"),
                secretKeys = listOf("NEW_NAME", "KEEP_ME"),
            )
            val task = setupTask(project)

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) {
                    """{"old_name": "renamed-value", "KEEP_ME": "kept", "DROP_ME": "dropped"}"""
                } else ""
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("renamed-value", env["NEW_NAME"], "Renamed key should be present")
            assertEquals("kept", env["KEEP_ME"], "Explicitly listed key should be present")
            assertFalse(env.containsKey("DROP_ME"), "Unlisted key should be filtered out")
            assertFalse(env.containsKey("old_name"), "Original name should not appear")
        }

        @Test
        fun `renamed key not in filter is still included`() {
            val project = newProject()
            wireSecret(
                project, "rename-nofilter",
                renameKeys = mapOf("aws_key" to "CUSTOM_KEY"),
                secretKeys = listOf("OTHER_KEY"),
            )
            val task = setupTask(project)

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) {
                    """{"aws_key": "AKIA123", "OTHER_KEY": "val", "IGNORED": "x"}"""
                } else ""
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("AKIA123", env["CUSTOM_KEY"], "Renamed key should survive even if not in filter")
            assertEquals("val", env["OTHER_KEY"])
            assertFalse(env.containsKey("IGNORED"))
        }
    }

    // ─── WriteToFile (Multi-line Plaintext) ──────────────────────────────────

    @Nested
    inner class WriteToFile {

        @Test
        fun `multi-line plaintext with writeToFile writes to file and env var gets path`() {
            val project = newProject()
            val secret = wireSecret(
                project, "cert",
                plaintext = true,
                plaintextKey = "MY_CERT",
            )
            secret.writeToFile.set(true)
            val task = setupTask(project)

            val pemValue = """-----BEGIN CERTIFICATE-----
MIIC8zCCAdugAwIBAgIU
abc123def456ghi789
-----END CERTIFICATE-----"""

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) pemValue else ""
            }

            task.createSecretsFile()

            // Env var should contain the absolute file path, not the raw PEM
            val env = EnvFileIO.read(File(projectDir, ".env"))
            val envValue = env["MY_CERT"] ?: fail("MY_CERT should be in .env")
            assertTrue(envValue.endsWith("MY_CERT"), "Env var should be a path ending with MY_CERT, got: $envValue")

            // The file should exist and contain the original PEM content
            val secretFile = File(projectDir, ".env-secrets/MY_CERT")
            assertTrue(secretFile.exists(), "Secret file should exist at .env-secrets/MY_CERT")
            assertEquals(pemValue, secretFile.readText())
        }

        @Test
        fun `multi-line plaintext without writeToFile fails with clear error`() {
            val project = newProject()
            wireSecret(
                project, "multiline",
                plaintext = true,
                plaintextKey = "MULTI_VAL",
            )
            // writeToFile defaults to false
            val task = setupTask(project)

            val multiLineValue = "line1\nline2\nline3"
            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) multiLineValue else ""
            }

            val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                task.createSecretsFile()
            }
            assertTrue(ex.message!!.contains("contains newlines"), "Should mention newlines")
            assertTrue(ex.message!!.contains("writeToFile = true"), "Should suggest writeToFile")

            // No .env file should have been created (failed before writing)
            assertFalse(File(projectDir, ".env").exists())
        }

        @Test
        fun `single-line plaintext is unaffected by writeToFile`() {
            val project = newProject()
            val secret = wireSecret(
                project, "token",
                plaintext = true,
                plaintextKey = "API_TOKEN",
            )
            secret.writeToFile.set(true) // even with writeToFile=true, single-line stays inline
            val task = setupTask(project)

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) "ghp_single_line_token" else ""
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("ghp_single_line_token", env["API_TOKEN"])

            // No secret file should have been created for single-line values
            assertFalse(File(projectDir, ".env-secrets").exists())
        }

        @Test
        fun `writeToFile secret file lands in the env-secrets subdirectory`() {
            val project = newProject()
            val secret = wireSecret(
                project, "key",
                plaintext = true,
                plaintextKey = "PRIVATE_KEY",
            )
            secret.writeToFile.set(true)
            val task = setupTask(project)

            val multiLine = "part1\npart2"
            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) multiLine else ""
            }

            task.createSecretsFile()

            val secretDir = File(projectDir, ".env-secrets")
            assertTrue(secretDir.exists(), ".env-secrets directory should exist")
            assertTrue(secretDir.isDirectory)
        }

        @Test
        fun `writeToFile works alongside JSON secrets in same run`() {
            val project = newProject()
            // JSON secret (normal path)
            wireSecret(project, "db")
            // Plaintext multi-line with writeToFile
            val certSecret = wireSecret(
                project, "cert",
                plaintext = true,
                plaintextKey = "TLS_CERT",
            )
            certSecret.writeToFile.set(true)
            val task = setupTask(project)

            task.commandExecutor = { cmd, _, _ ->
                when {
                    cmd.contains("myapp/dev/db") -> """{"DB_USER": "admin"}"""
                    cmd.contains("myapp/dev/cert") -> "cert-line1\ncert-line2"
                    else -> ""
                }
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("admin", env["DB_USER"], "JSON secret should work normally")
            assertTrue(env["TLS_CERT"]!!.endsWith("TLS_CERT"), "Multi-line secret should be a file path")
        }
    }

    // ─── Required Key Validation ──────────────────────────────────────────────

    @Nested
    inner class RequiredKeyValidation {

        @Test
        fun `fails when a required key is missing from the secret response`() {
            val project = newProject()
            wireSecret(
                project, "db",
                renameKeys = mapOf("username" to "DB_USER", "password" to "DB_PASS"),
                secretKeys = listOf("DB_USER", "DB_PASS"),
            )
            val task = setupTask(project)

            // AWS returns only username, missing password
            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) """{"username": "admin"}""" else ""
            }

            val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                task.createSecretsFile()
            }
            assertTrue(ex.message!!.contains("missing required key"), "Should mention missing required key")
            assertTrue(ex.message!!.contains("DB_PASS"), "Should name the missing key")
        }

        @Test
        fun `succeeds when all required keys are present`() {
            val project = newProject()
            wireSecret(
                project, "db",
                renameKeys = mapOf("username" to "DB_USER", "password" to "DB_PASS"),
                secretKeys = listOf("DB_USER", "DB_PASS"),
            )
            val task = setupTask(project)

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) """{"username": "admin", "password": "s3cret"}""" else ""
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("admin", env["DB_USER"])
            assertEquals("s3cret", env["DB_PASS"])
        }

        @Test
        fun `no validation when secretKeys is empty`() {
            val project = newProject()
            wireSecret(project, "freeform") // no secretKeys filter
            val task = setupTask(project)

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) """{"anything": "goes"}""" else ""
            }

            // Should not throw — no required keys to validate
            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("goes", env["anything"])
        }
    }

    // ─── Base64 Decode ────────────────────────────────────────────────────────

    @Nested
    inner class Base64Decode {

        @Test
        fun `decodes base64 plaintext secret when decode is true`() {
            val project = newProject()
            val secret = wireSecret(
                project, "encoded",
                plaintext = true,
                plaintextKey = "DECODED_VALUE",
            )
            secret.decode.set(true)
            val task = setupTask(project)

            // "hello world" in base64
            val base64Value = java.util.Base64.getEncoder().encodeToString("hello world".toByteArray())
            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) base64Value else ""
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("hello world", env["DECODED_VALUE"])
        }

        @Test
        fun `does not decode when decode is false (default)`() {
            val project = newProject()
            wireSecret(
                project, "raw",
                plaintext = true,
                plaintextKey = "RAW_B64",
            )
            // decode defaults to false
            val task = setupTask(project)

            val base64Value = java.util.Base64.getEncoder().encodeToString("hello world".toByteArray())
            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) base64Value else ""
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals(base64Value, env["RAW_B64"], "Value should remain base64-encoded")
        }

        @Test
        fun `fails with clear error when value is not valid base64 and decode is true`() {
            val project = newProject()
            val secret = wireSecret(
                project, "bad-b64",
                plaintext = true,
                plaintextKey = "BAD_VALUE",
            )
            secret.decode.set(true)
            val task = setupTask(project)

            // Not valid base64 (contains spaces and non-base64 chars)
            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) "not-valid-base64!!!" else ""
            }

            val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                task.createSecretsFile()
            }
            assertTrue(ex.message!!.contains("not valid base64"), "Should mention invalid base64")
        }

        @Test
        fun `decode works with multi-line text value and writeToFile`() {
            val project = newProject()
            val secret = wireSecret(
                project, "cert",
                plaintext = true,
                plaintextKey = "CERT_DATA",
            )
            secret.decode.set(true)
            secret.writeToFile.set(true)
            val task = setupTask(project)

            // A multi-line PEM-like value encoded in base64
            val pemValue = """-----BEGIN CERTIFICATE-----
MIIC8zCCAdugAwIBAgIU
abc123def456ghi789
-----END CERTIFICATE-----"""
            val base64Value = java.util.Base64.getEncoder().encodeToString(pemValue.toByteArray())
            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) base64Value else ""
            }

            task.createSecretsFile()

            // Decoded value is multi-line → writeToFile kicks in → env var gets the file path
            val env = EnvFileIO.read(File(projectDir, ".env"))
            val envValue = env["CERT_DATA"] ?: fail("CERT_DATA should be in .env")
            assertTrue(envValue.endsWith("CERT_DATA"), "Env var should be a file path ending with CERT_DATA, got: $envValue")

            // The secret file should contain the decoded PEM content
            val secretFile = File(projectDir, ".env-secrets/CERT_DATA")
            assertTrue(secretFile.exists(), "Secret file should exist")
            assertEquals(pemValue, secretFile.readText())
        }
    }

    // ─── Dry Run ──────────────────────────────────────────────────────────────

    @Nested
    inner class DryRun {

        @Test
        fun `dry run does not write any files`() {
            val project = newProject()
            wireSecret(project, "db")
            val task = setupTask(project)
            task.dryRun.set(true)

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) """{"DB_USER": "admin", "DB_PASS": "s3cret"}""" else ""
            }

            task.createSecretsFile()

            // No .env file should exist
            assertFalse(File(projectDir, ".env").exists(), ".env should not be created in dry-run")
            // No cache file should exist
            assertFalse(File(projectDir, ".env-dev").exists(), "Cache file should not be created in dry-run")
        }

        @Test
        fun `dry run with JSON secret does not write files`() {
            val project = newProject()
            wireSecret(
                project, "json",
                renameKeys = mapOf("user" to "DB_USER"),
                secretKeys = listOf("DB_USER"),
            )
            val task = setupTask(project)
            task.dryRun.set(true)

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) """{"user": "admin", "pass": "x"}""" else ""
            }

            task.createSecretsFile()

            assertFalse(File(projectDir, ".env").exists())
            assertFalse(File(projectDir, ".env-dev").exists())
        }

        @Test
        fun `dry run still validates required keys`() {
            val project = newProject()
            wireSecret(
                project, "db",
                renameKeys = mapOf("username" to "DB_USER", "password" to "DB_PASS"),
                secretKeys = listOf("DB_USER", "DB_PASS"),
            )
            val task = setupTask(project)
            task.dryRun.set(true)

            // Missing DB_PASS
            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) """{"username": "admin"}""" else ""
            }

            val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                task.createSecretsFile()
            }
            assertTrue(ex.message!!.contains("missing required key"))
        }

        @Test
        fun `dry run still decodes base64 (validates the value)`() {
            val project = newProject()
            val secret = wireSecret(
                project, "b64",
                plaintext = true,
                plaintextKey = "DECODED",
            )
            secret.decode.set(true)
            val task = setupTask(project)
            task.dryRun.set(true)

            // Invalid base64 should still fail in dry-run
            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) "!!!not-base64!!!" else ""
            }

            val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
                task.createSecretsFile()
            }
            assertTrue(ex.message!!.contains("not valid base64"))
        }
    }

    // ─── Command Safety ───────────────────────────────────────────────────────

    @Nested
    inner class CommandSafety {

        @Test
        fun `rejects commands with shell metacharacters`() {
            val ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) {
                CommandExecutor.execute("echo hello; rm -rf /", 5, false)
            }
            assertTrue(ex.message!!.contains("dangerous character"))
        }

        @Test
        fun `rejects commands with backticks`() {
            val ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) {
                CommandExecutor.execute("echo `whoami`", 5, false)
            }
            assertTrue(ex.message!!.contains("dangerous character"))
        }

        @Test
        fun `rejects commands with dollar signs`() {
            val ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) {
                CommandExecutor.execute($$"echo $HOME", 5, false)
            }
            assertTrue(ex.message!!.contains("dangerous character"))
        }

        @Test
        fun `times out and kills hanging command`() {
            // ping localhost 10 times at 1s intervals = ~10s hang (works without a console)
            val hangCommand = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                "ping -n 11 127.0.0.1"
            } else {
                "sleep 10"
            }
            val ex = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException::class.java) {
                CommandExecutor.execute(hangCommand, 1, false)
            }
            assertTrue(ex.message!!.contains("timed out"))
        }
    }
}
