package io.github.adamarmistead.setenv.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
