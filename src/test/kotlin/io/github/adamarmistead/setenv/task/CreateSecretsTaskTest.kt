package io.github.adamarmistead.setenv.task

import io.github.adamarmistead.setenv.dsl.EnvExtension
import io.github.adamarmistead.setenv.dsl.Secrets
import io.github.adamarmistead.setenv.internal.CommandExecutor
import io.github.adamarmistead.setenv.internal.EnvFileIO
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
        cacheFile: File = File(projectDir, ".env-dev-$name"),
    ): Secrets {
        val extension = project.extensions.getByType(EnvExtension::class.java)
        val secret = extension.secrets.create(name)
        secret.secretId.set(secretId)
        secret.plaintext.set(plaintext)
        if (plaintext) secret.plaintextKey.set(plaintextKey)
        renameKeys?.let { secret.renameKeys.set(it) }
        secretKeys?.let { secret.secretKeys.set(it) }
        secret.secretsFile.set(cacheFile)

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
    ): CreateSecretsTask {
        val task = project.tasks.named("createSecrets", CreateSecretsTask::class.java).get()
        task.target.set(env)
        task.region.set(region)
        task.refresh.set(refresh)
        task.envFile.set(envFile)
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
            val cacheFile = File(projectDir, ".env-dev-db")
            wireSecret(
                project, "db",
                renameKeys = mapOf("username" to "DB_USER", "password" to "DB_PASS"),
                secretKeys = listOf("DB_USER", "DB_PASS"),
                cacheFile = cacheFile,
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
            val cacheFile = File(projectDir, ".env-dev-all")
            wireSecret(project, "all", cacheFile = cacheFile)
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
            val cacheFile = File(projectDir, ".env-dev-token")
            wireSecret(
                project, "token",
                plaintext = true,
                plaintextKey = "API_TOKEN",
                cacheFile = cacheFile,
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
            val cacheFile = File(projectDir, ".env-dev-raw")
            wireSecret(
                project, "raw",
                plaintext = true,
                plaintextKey = "MY_RAW_SECRET",
                cacheFile = cacheFile,
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
            val cacheDb = File(projectDir, ".env-dev-db")
            val cacheApi = File(projectDir, ".env-dev-api")

            wireSecret(
                project, "db",
                renameKeys = mapOf("username" to "DB_USER"),
                secretKeys = listOf("DB_USER"),
                cacheFile = cacheDb,
            )
            wireSecret(
                project, "api",
                secretId = $$"myapp/${target}/api-keys",
                cacheFile = cacheApi,
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
        fun `each secret block gets its own cache file`() {
            val project = newProject()
            val cacheA = File(projectDir, ".env-dev-a")
            val cacheB = File(projectDir, ".env-dev-b")

            wireSecret(project, "a", cacheFile = cacheA)
            wireSecret(project, "b", cacheFile = cacheB)
            val task = setupTask(project)

            task.commandExecutor = { cmd, _, _ ->
                when {
                    cmd.contains("myapp/dev/a") -> """{"key_a": "val_a"}"""
                    cmd.contains("myapp/dev/b") -> """{"key_b": "val_b"}"""
                    else -> ""
                }
            }

            task.createSecretsFile()

            assertTrue(cacheA.exists(), "cache file for secret 'a' should exist")
            assertTrue(cacheB.exists(), "cache file for secret 'b' should exist")

            val cacheAProps = EnvFileIO.read(cacheA)
            val cacheBProps = EnvFileIO.read(cacheB)
            assertEquals("val_a", cacheAProps["key_a"])
            assertEquals("val_b", cacheBProps["key_b"])
        }

        @Test
        fun `secret with per-secret env override uses different environment`() {
            val project = newProject()
            val cacheFile = File(projectDir, ".env-prd-special")
            val secret = wireSecret(project, "special", cacheFile = cacheFile)
            secret.environment.set("prd")

            val task = setupTask(project, env = "dev")

            val executed = mutableListOf<String>()
            task.commandExecutor = { cmd, _, _ ->
                executed.add(cmd)
                if (cmd.contains("get-secret-value")) {
                    """{"token": "prd-token"}"""
                } else ""
            }

            task.createSecretsFile()

            // Should have logged in via the standard AWS CLI SSO flow with the profile
            assertTrue(executed.any { it.contains("aws sso login --profile default") })
            // Secret ID should resolve to prd
            assertTrue(executed.any { it.contains("myapp/prd/special") })
        }
    }

    // ─── Caching Behavior ─────────────────────────────────────────────────────

    @Nested
    inner class Caching {

        @Test
        fun `uses cached secrets when refresh is false and cache file exists`() {
            val project = newProject()
            val cacheFile = File(projectDir, ".env-dev-cached")
            wireSecret(project, "cached", cacheFile = cacheFile)
            val task = setupTask(project, refresh = false)

            // Pre-populate the cache file
            EnvFileIO.write(cacheFile, mapOf("CACHED_KEY" to "cached-value"))

            val executed = mutableListOf<String>()
            task.commandExecutor = { cmd, _, _ ->
                executed.add(cmd)
                "should-not-be-used"
            }

            task.createSecretsFile()

            // No AWS commands should have been executed
            assertTrue(executed.none { it.contains("aws") }, "No AWS commands should run when using cache")

            // .env should contain the cached value
            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("cached-value", env["CACHED_KEY"])
        }

        @Test
        fun `refreshes secrets when refresh is true even if cache exists`() {
            val project = newProject()
            val cacheFile = File(projectDir, ".env-dev-refresh")
            wireSecret(project, "refresh", cacheFile = cacheFile)
            val task = setupTask(project, refresh = true)

            // Pre-populate with stale value
            EnvFileIO.write(cacheFile, mapOf("STALE_KEY" to "old-value"))

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) {
                    """{"STALE_KEY": "new-value", "FRESH_KEY": "fresh"}"""
                } else ""
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("new-value", env["STALE_KEY"])
            assertEquals("fresh", env["FRESH_KEY"])
        }

        @Test
        fun `fetches secrets when no cache file exists and refresh is false`() {
            val project = newProject()
            val cacheFile = File(projectDir, ".env-dev-nocache")
            wireSecret(project, "nocache", cacheFile = cacheFile)
            val task = setupTask(project, refresh = false)

            // Ensure no cache file exists
            if (cacheFile.exists()) cacheFile.delete()

            val executed = mutableListOf<String>()
            task.commandExecutor = { cmd, _, _ ->
                executed.add(cmd)
                if (cmd.contains("get-secret-value")) {
                    """{"FIRST_RUN": "yes"}"""
                } else ""
            }

            task.createSecretsFile()

            // Should have fetched since no cache existed
            assertTrue(executed.any { it.contains("get-secret-value") })
            assertTrue(cacheFile.exists(), "Cache file should be created after first fetch")

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("yes", env["FIRST_RUN"])
        }

        @Test
        fun `works when cache file is deleted but env file still exists`() {
            val project = newProject()
            val cacheFile = File(projectDir, ".env-dev-deleted")
            val envFile = File(projectDir, ".env")
            wireSecret(project, "deleted", cacheFile = cacheFile)
            val task = setupTask(project, refresh = false, envFile = envFile)

            // Simulate: .env exists with manual entries, cache was deleted
            EnvFileIO.write(envFile, mapOf("MANUAL_KEY" to "manual-value"))
            if (cacheFile.exists()) cacheFile.delete()

            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) {
                    """{"FROM_AWS": "recovered"}"""
                } else ""
            }

            task.createSecretsFile()

            val env = EnvFileIO.read(envFile)
            assertEquals("recovered", env["FROM_AWS"])
            // Manual key should be preserved by merge
            assertEquals("manual-value", env["MANUAL_KEY"])
        }

        @Test
        fun `cache file accumulates keys across refreshes`() {
            val project = newProject()
            val cacheFile = File(projectDir, ".env-dev-accum")
            wireSecret(project, "accum", cacheFile = cacheFile)
            val task = setupTask(project, refresh = true)

            // First run: returns key A
            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) """{"KEY_A": "a1"}""" else ""
            }
            task.createSecretsFile()

            // Second run: returns key B (different secret content)
            task.commandExecutor = { cmd, _, _ ->
                if (cmd.contains("get-secret-value")) """{"KEY_B": "b1"}""" else ""
            }
            task.createSecretsFile()

            // Cache should have both keys (merge behavior)
            val cache = EnvFileIO.read(cacheFile)
            assertEquals("a1", cache["KEY_A"])
            assertEquals("b1", cache["KEY_B"])
        }
    }

    // ─── Error Handling ───────────────────────────────────────────────────────

    @Nested
    inner class ErrorHandling {

        @Test
        fun `throws when command executor fails`() {
            val project = newProject()
            val cacheFile = File(projectDir, ".env-dev-fail")
            wireSecret(project, "fail", cacheFile = cacheFile)
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
        fun `uses cached secrets without any extra configuration`() {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val extension = project.extensions.getByType(EnvExtension::class.java)
            val cacheFile = File(projectDir, ".env-dev-cached")
            val secret = extension.secrets.create("cached")
            secret.secretId.set($$"myapp/${target}/cached")
            secret.secretsFile.set(cacheFile)

            val task = project.tasks.named("createSecrets", CreateSecretsTask::class.java).get()
            task.secrets.add(secret)
            task.target.set("dev")
            task.region.set("use1")
            task.refresh.set(false)
            task.envFile.set(File(projectDir, ".env"))

            // Pre-populate cache — no fetch needed
            EnvFileIO.write(cacheFile, mapOf("CACHED" to "works-from-cache"))

            task.createSecretsFile()

            val env = EnvFileIO.read(File(projectDir, ".env"))
            assertEquals("works-from-cache", env["CACHED"])
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
        }

        @Test
        fun `task conventions provide sensible defaults`() {
            val project = newProject()
            val task = project.tasks.named("createSecrets", CreateSecretsTask::class.java).get()

            assertEquals(60, task.commandTimeout.get())
            assertEquals(300, task.loginTimeout.get())
            // refresh is wired to setEnv.refresh which defaults to false
            // (can't assert here without evaluating setEnv, but the convention is set in the plugin)
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
            val cacheFile = File(projectDir, ".env-dev-nokey")
            // Use plaintext=false in helper (so plaintextKey is NOT set), then enable plaintext
            val secret = wireSecret(project, "nokey", cacheFile = cacheFile)
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
            val cacheFile = File(projectDir, ".env-dev-plain")
            wireSecret(project, "plain", cacheFile = cacheFile)
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
            val cacheFile = File(projectDir, ".env-dev-empty")
            wireSecret(project, "empty", cacheFile = cacheFile)
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
            val cacheFile = File(projectDir, ".env-dev-array")
            wireSecret(project, "array", cacheFile = cacheFile)
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
            val cacheFile = File(projectDir, ".env-dev-rename-filter")
            wireSecret(
                project, "rename-filter",
                renameKeys = mapOf("old_name" to "NEW_NAME"),
                secretKeys = listOf("NEW_NAME", "KEEP_ME"),
                cacheFile = cacheFile,
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
            val cacheFile = File(projectDir, ".env-dev-rename-nofilter")
            wireSecret(
                project, "rename-nofilter",
                renameKeys = mapOf("aws_key" to "CUSTOM_KEY"),
                secretKeys = listOf("OTHER_KEY"),
                cacheFile = cacheFile,
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
    }
}
