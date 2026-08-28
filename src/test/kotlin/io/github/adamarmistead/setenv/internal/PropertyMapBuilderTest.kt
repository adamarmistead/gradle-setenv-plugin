package io.github.adamarmistead.setenv.internal

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
    inner class ErrorHandling {

        @Test
        fun `rejects unknown region`() {
            val project = ProjectBuilder.builder().build()

            assertThrows(IllegalArgumentException::class.java) {
                PropertyMapBuilder.build(project, "dev", "unknown")
            }
        }
    }
}
