package io.github.adamarmistead.setenv.internal

import io.github.adamarmistead.setenv.dsl.EnvExtension
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PropertyMapBuilderTest {

    @Nested
    inner class BuildingPropertyMaps {

        @Test
        fun `builds property map with target and region short codes`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val properties = PropertyMapBuilder.build(project, "dev", "use1")

            assertEquals("dev", properties["target"])
            assertEquals("us-east-1", properties["region"])
            assertEquals("use1", properties["regionShort"])
        }

        @Test
        fun `builds property map with full region name`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            val properties = PropertyMapBuilder.build(project, "prd", "us-west-2")

            assertEquals("prd", properties["target"])
            assertEquals("us-west-2", properties["region"])
            assertEquals("usw2", properties["regionShort"])
        }
    }

    @Nested
    inner class CustomRegionMerge {

        @Test
        fun `custom short code resolves alongside built-in regions`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")
            val extension = project.extensions.getByType(EnvExtension::class.java)
            extension.regions.set(mapOf("myr1" to "my-region-1"))

            // The custom short code resolves...
            val custom = PropertyMapBuilder.build(project, "dev", "myr1")
            assertEquals("my-region-1", custom["region"])
            assertEquals("myr1", custom["regionShort"])

            // ...and a built-in region still resolves (the regression: previously the
            // custom map REPLACED the built-ins, so use1 would throw "Unknown target region").
            val builtin = PropertyMapBuilder.build(project, "dev", "use1")
            assertEquals("us-east-1", builtin["region"])
            assertEquals("use1", builtin["regionShort"])
        }

        @Test
        fun `custom full region name resolves alongside built-in regions`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")
            val extension = project.extensions.getByType(EnvExtension::class.java)
            extension.regionShortCodes.set(mapOf("my-region-1" to "myr1"))

            // Custom full name resolves via the regionShortCodes map...
            val custom = PropertyMapBuilder.build(project, "dev", "my-region-1")
            assertEquals("my-region-1", custom["region"])
            assertEquals("myr1", custom["regionShort"])

            // ...and a built-in full name still resolves.
            val builtin = PropertyMapBuilder.build(project, "dev", "us-west-2")
            assertEquals("us-west-2", builtin["region"])
            assertEquals("usw2", builtin["regionShort"])
        }

        @Test
        fun `user region wins over built-in on key conflict`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")
            val extension = project.extensions.getByType(EnvExtension::class.java)
            // Override a built-in short code to point at a different full name.
            extension.regions.set(mapOf("use1" to "my-override-region"))

            val result = PropertyMapBuilder.build(project, "dev", "use1")
            assertEquals("my-override-region", result["region"])
            assertEquals("use1", result["regionShort"])
        }

        @Test
        fun `no custom regions still resolves all built-ins`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")

            // Sanity: with no user config, the full built-in set is available.
            assertEquals("us-east-1", PropertyMapBuilder.build(project, "dev", "use1")["region"])
            assertEquals("ap-northeast-3", PropertyMapBuilder.build(project, "dev", "apne3")["region"])
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `rejects unknown region`() {
            val project = ProjectBuilder.builder().build()

            assertThrows(IllegalArgumentException::class.java) {
                PropertyMapBuilder.build(project, "dev", "unknown")
            }
        }

        @Test
        fun `rejects unknown region even when custom regions are configured`() {
            val project = ProjectBuilder.builder().build()
            project.plugins.apply("io.github.adamarmistead.setenv")
            val extension = project.extensions.getByType(EnvExtension::class.java)
            extension.regions.set(mapOf("myr1" to "my-region-1"))

            // A genuinely unknown region must still be rejected (merge didn't swallow validation).
            assertThrows(IllegalArgumentException::class.java) {
                PropertyMapBuilder.build(project, "dev", "unknown")
            }
        }
    }
}
