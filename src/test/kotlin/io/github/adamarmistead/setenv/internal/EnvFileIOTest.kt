package io.github.adamarmistead.setenv.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EnvFileIOTest {

    @TempDir
    lateinit var tempDir: File

    @Nested
    inner class ReadAndWrite {

        @Test
        fun `reads and writes env files`() {
            val file = File(tempDir, "test.env")
            EnvFileIO.write(file, linkedMapOf("B" to "2", "A" to "1"))

            assertEquals(listOf("A=1", "B=2"), file.readLines())
            assertEquals(linkedMapOf("A" to "1", "B" to "2"), EnvFileIO.read(file))
        }

        @Test
        fun `read skips blank lines and comments`() {
            val file = File(tempDir, "comments.env")
            file.writeText(
                """
                # This is a comment
                KEY_A=hello

                KEY_B=world
                """.trimIndent(),
            )

            assertEquals(linkedMapOf("KEY_A" to "hello", "KEY_B" to "world"), EnvFileIO.read(file))
        }
    }

    @Nested
    inner class MergeInto {

        @Test
        fun `updates existing keys in place preserving comments and blank lines`() {
            val target = File(tempDir, "target.env")
            target.writeText(
                """
                # App config
                APP_URL=https://old.example.com

                # Database
                DB_HOST=old-host
                MANUAL_KEY=keep-me
                """.trimIndent(),
            )

            val source = File(tempDir, "source.env")
            EnvFileIO.write(source, mapOf("APP_URL" to "https://new.example.com", "DB_HOST" to "new-host"))

            EnvFileIO.mergeInto(target, source)

            val lines = target.readLines()
            assertTrue(lines.contains("# App config"))
            assertTrue(lines.contains("# Database"))
            assertTrue(lines.any { it.isBlank() })
            assertTrue(lines.contains("APP_URL=https://new.example.com"))
            assertTrue(lines.contains("DB_HOST=new-host"))
            assertTrue(lines.contains("MANUAL_KEY=keep-me"))
        }

        @Test
        fun `appends new keys not already in the target`() {
            val target = File(tempDir, "append-target.env")
            EnvFileIO.write(target, mapOf("EXISTING" to "yes"))

            val source = File(tempDir, "append-source.env")
            EnvFileIO.write(source, mapOf("NEW_KEY" to "added"))

            EnvFileIO.mergeInto(target, source)

            val properties = EnvFileIO.read(target)
            assertEquals("yes", properties["EXISTING"])
            assertEquals("added", properties["NEW_KEY"])
        }

        @Test
        fun `creates target when it does not exist`() {
            val target = File(tempDir, "new-target.env")
            assertFalse(target.exists())

            val source = File(tempDir, "new-source.env")
            EnvFileIO.write(source, mapOf("KEY" to "value"))

            EnvFileIO.mergeInto(target, source)

            assertTrue(target.exists())
            assertEquals(mapOf("KEY" to "value"), EnvFileIO.read(target))
        }

        @Test
        fun `overlays values from multiple sources`() {
            val target = File(tempDir, "overlay-target.env")
            val source1 = File(tempDir, "overlay-source1.env")
            val source2 = File(tempDir, "overlay-source2.env")

            EnvFileIO.write(target, mapOf("SHARED" to "from-target", "ONLY_TARGET" to "yes"))
            EnvFileIO.write(source1, mapOf("SHARED" to "from-source1"))
            EnvFileIO.write(source2, mapOf("ONLY_SOURCE2" to "yes"))

            EnvFileIO.mergeInto(target, source1, source2)

            val properties = EnvFileIO.read(target)
            assertEquals("from-source1", properties["SHARED"])
            assertEquals("yes", properties["ONLY_TARGET"])
            assertEquals("yes", properties["ONLY_SOURCE2"])
        }
    }

    @Nested
    inner class MergeProperties {

        @Test
        fun `updates existing keys in place preserving other content`() {
            val target = File(tempDir, "merge-props.env")
            target.writeText(
                """
                # App config
                APP_NAME=my-app
                DB_PASSWORD=secret123
                MANUAL_KEY=keep-me
                """.trimIndent(),
            )

            EnvFileIO.mergeProperties(target, mapOf("APP_NAME" to "renamed-app"))

            val lines = target.readLines()
            assertTrue(lines.contains("# App config"))
            assertTrue(lines.contains("APP_NAME=renamed-app"))
            assertTrue(lines.contains("DB_PASSWORD=secret123"))
            assertTrue(lines.contains("MANUAL_KEY=keep-me"))
        }

        @Test
        fun `appends new keys not already in the target`() {
            val target = File(tempDir, "merge-props-new.env")
            EnvFileIO.write(target, mapOf("EXISTING" to "yes"))

            EnvFileIO.mergeProperties(target, mapOf("NEW_KEY" to "added", "EXISTING" to "updated"))

            val properties = EnvFileIO.read(target)
            assertEquals("updated", properties["EXISTING"])
            assertEquals("added", properties["NEW_KEY"])
        }

        @Test
        fun `creates target when it does not exist`() {
            val target = File(tempDir, "merge-props-new-file.env")
            assertFalse(target.exists())

            EnvFileIO.mergeProperties(target, mapOf("KEY" to "value"))

            assertTrue(target.exists())
            assertEquals(mapOf("KEY" to "value"), EnvFileIO.read(target))
        }

        @Test
        fun `is a no-op when properties map is empty`() {
            val target = File(tempDir, "merge-props-empty.env")
            target.writeText("EXISTING=yes\n")

            EnvFileIO.mergeProperties(target, emptyMap())

            assertEquals(listOf("EXISTING=yes"), target.readLines())
        }

        @Test
        fun `is idempotent when values are unchanged`() {
            val target = File(tempDir, "merge-props-idempotent.env")
            target.writeText("A=1\nB=2\nC=3\n")

            EnvFileIO.mergeProperties(target, mapOf("A" to "1", "B" to "2"))

            // Content should be identical — no reordering, no additions
            assertEquals(listOf("A=1", "B=2", "C=3"), target.readLines())
        }

        @Test
        fun `merges into a permission-restricted file`() {
            val target = File(tempDir, "restricted.env")
            EnvFileIO.write(target, mapOf("EXISTING" to "original", "KEEP" to "me"))

            EnvFileIO.mergeProperties(target, mapOf("EXISTING" to "updated", "NEW_KEY" to "added"))

            val properties = EnvFileIO.read(target)
            assertEquals("updated", properties["EXISTING"])
            assertEquals("me", properties["KEEP"])
            assertEquals("added", properties["NEW_KEY"])
        }

        @Test
        fun `mergeProperties maintains owner-only permissions on posix`() {
            if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return

            val target = File(tempDir, "merge-perms.env")
            EnvFileIO.write(target, mapOf("A" to "1"))

            // Simulate a second run: merge new values into the existing file
            EnvFileIO.mergeProperties(target, mapOf("A" to "2", "B" to "3"))

            val perms = java.nio.file.Files.getPosixFilePermissions(target.toPath())
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ))
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ))
        }

        @Test
        fun `mergeProperties fixes permissions on a user-created 0644 file on posix`() {
            if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return

            val target = File(tempDir, "user-created.env")
            // Simulate a user manually creating .env with default umask (0644)
            target.writeText("EXISTING=old\n")
            java.nio.file.Files.setPosixFilePermissions(
                target.toPath(),
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                ),
            )

            // Plugin merges into it — should end up 0600
            EnvFileIO.mergeProperties(target, mapOf("EXISTING" to "new", "SECRET" to "value"))

            val perms = java.nio.file.Files.getPosixFilePermissions(target.toPath())
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ))
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ))

            // Content is correct
            assertEquals("new", EnvFileIO.read(target)["EXISTING"])
            assertEquals("value", EnvFileIO.read(target)["SECRET"])
        }
    }

    @Nested
    inner class WriteSecretFile {

        @Test
        fun `writes multi-line secret to env-secrets directory`() {
            val pem = "-----BEGIN CERT-----\nMIIB...\n-----END CERT-----"
            val file = EnvFileIO.writeSecretFile(tempDir, "TLS_CERT", pem)

            assertTrue(file.exists())
            assertEquals(pem, file.readText())
            assertEquals(File(tempDir, ".env-secrets/TLS_CERT"), file)
        }

        @Test
        fun `rejects path traversal in key`() {
            assertThrows(IllegalArgumentException::class.java) {
                EnvFileIO.writeSecretFile(tempDir, "../../evil", "secret")
            }
            assertThrows(IllegalArgumentException::class.java) {
                EnvFileIO.writeSecretFile(tempDir, "a/b", "secret")
            }
            assertThrows(IllegalArgumentException::class.java) {
                EnvFileIO.writeSecretFile(tempDir, "a\\b", "secret")
            }
        }

        @Test
        fun `rejects parent-dir reference in key`() {
            assertThrows(IllegalArgumentException::class.java) {
                EnvFileIO.writeSecretFile(tempDir, "..", "secret")
            }
        }

        @Test
        fun `creates env-secrets directory with restricted permissions on posix`() {
            // Only meaningful on POSIX systems; skip gracefully on Windows.
            if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                return
            }

            EnvFileIO.writeSecretFile(tempDir, "MY_SECRET", "value")

            val secretsDir = File(tempDir, ".env-secrets").toPath()
            val perms = java.nio.file.Files.getPosixFilePermissions(secretsDir)
            // Should be 0700: owner rwx, no group/other access
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ))
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ))
        }

        @Test
        fun `secret file has owner-only permissions on posix`() {
            if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                return
            }

            val file = EnvFileIO.writeSecretFile(tempDir, "MY_SECRET", "value")
            val perms = java.nio.file.Files.getPosixFilePermissions(file.toPath())
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ))
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ))
        }
    }

    @Nested
    inner class WritePermissions {

        @Test
        fun `write creates file with owner-only permissions on posix`() {
            if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                return
            }

            val file = File(tempDir, "cache.env")
            EnvFileIO.write(file, mapOf("KEY" to "value"))

            val perms = java.nio.file.Files.getPosixFilePermissions(file.toPath())
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ))
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ))
        }

        @Test
        fun `write overwrites existing file and maintains owner-only permissions on posix`() {
            if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                return
            }

            val file = File(tempDir, "cache2.env")
            EnvFileIO.write(file, mapOf("A" to "1"))
            EnvFileIO.write(file, mapOf("A" to "2", "B" to "3"))

            val perms = java.nio.file.Files.getPosixFilePermissions(file.toPath())
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ))
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ))

            assertEquals(mapOf("A" to "2", "B" to "3"), EnvFileIO.read(file))
        }

        @Test
        fun `write fixes permissions on a pre-existing 0644 file on posix`() {
            if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return

            val file = File(tempDir, "preexisting.env")
            // Simulate a file created by another tool with default umask (0644)
            file.writeText("OLD=stale\n")
            java.nio.file.Files.setPosixFilePermissions(
                file.toPath(),
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                ),
            )

            // Plugin overwrites it — should end up 0600 with new content
            EnvFileIO.write(file, mapOf("FRESH" to "value"))

            val perms = java.nio.file.Files.getPosixFilePermissions(file.toPath())
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ))
            assertTrue(perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ))
            assertFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ))

            assertEquals(mapOf("FRESH" to "value"), EnvFileIO.read(file))
        }
    }

    @Nested
    inner class EnsureGitIgnored {

        @Test
        fun `appends missing env wildcard pattern to gitignore`() {
            val gitIgnore = File(tempDir, ".gitignore").apply {
                writeText("build/\n.gradle/\n")
            }

            EnvFileIO.ensureGitIgnored(tempDir)

            assertTrue(gitIgnore.readLines().contains(".env*"))
        }

        @Test
        fun `creates gitignore if missing`() {
            EnvFileIO.ensureGitIgnored(tempDir)

            val gitIgnore = File(tempDir, ".gitignore")
            assertTrue(gitIgnore.exists())
            assertTrue(gitIgnore.readLines().contains(".env*"))
        }
    }
}
