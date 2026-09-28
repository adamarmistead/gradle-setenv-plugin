package io.github.adamarmistead.setenv.internal

import io.github.adamarmistead.setenv.constants.Defaults
import io.github.adamarmistead.setenv.constants.PropertyKeys
import io.github.adamarmistead.setenv.dsl.EnvExtension
import org.gradle.api.Project
import org.gradle.api.provider.Property

/**
 * Builds the property map used for placeholder substitution in environment variable values.
 *
 * The resulting map contains (in precedence order, later entries override earlier):
 * 1. All OS environment variables
 * 2. All Gradle project properties (`-P` flags, `gradle.properties`)
 * 3. All JVM system properties
 * 4. `target` — the resolved target environment name
 * 5. `region` / `regionShort` — the resolved AWS region and its short code
 *
 * This map is consumed by [org.apache.commons.text.StringSubstitutor] to replace
 * `${...}` placeholders in user-configured values.
 */
internal object PropertyMapBuilder {

    /**
     * Builds the substitution property map for the given target environment and region.
     *
     * @param project the Gradle project (used to read project properties)
     * @param targetEnv the resolved target environment name (e.g. `dev`)
     * @param targetRegion the resolved region — either a full name (`us-east-1`) or short code (`use1`)
     * @return an ordered map of all available substitution keys and their values
     * @throws IllegalArgumentException if [targetRegion] is not recognized in either the
     *   built-in or user-configured region mappings
     */
    fun build(project: Project, targetEnv: String, targetRegion: String): Map<String, String> {
        val extension = project.extensions.findByType(EnvExtension::class.java)
        val regions = Defaults.REGIONS + (extension?.regions?.orNull ?: emptyMap())
        val regionShortCodes = Defaults.REGION_SHORT_CODES + (extension?.regionShortCodes?.orNull ?: emptyMap())

        val propertyMap = linkedMapOf<String, String>()

        System.getenv().forEach { (key, value) -> propertyMap[key] = value }

        // Use project.properties for the documented Gradle property cascade:
        //   -P flags, gradle.properties (user home, project dir, parent, root, gradle home)
        //
        // TODO(Gradle 10): `Project.getProperties()` is deprecated in Gradle 9.x and will be
        //   REMOVED in Gradle 10. The Providers API replacement is NOT a drop-in — see
        //   docs/GRADLE-10-MIGRATION.md for the full analysis and the exact merge to apply.
        @Suppress("DEPRECATION")
        project.properties.forEach { (key, value) ->
            propertyMap[key] = when (value) {
                is Property<*> -> value.orNull?.toString() ?: ""
                else -> value.toString()
            }
        }

        System.getProperties().stringPropertyNames().forEach { key ->
            propertyMap[key] = System.getProperty(key)
        }

        propertyMap[PropertyKeys.TARGET] = targetEnv

        when {
            targetRegion in regionShortCodes -> {
                propertyMap[PropertyKeys.REGION] = targetRegion
                propertyMap[PropertyKeys.REGION_SHORT] = regionShortCodes.getValue(targetRegion)
            }
            targetRegion in regions -> {
                propertyMap[PropertyKeys.REGION] = regions.getValue(targetRegion)
                propertyMap[PropertyKeys.REGION_SHORT] = targetRegion
            }
            else -> throw IllegalArgumentException(
                "Unknown target region: \"$targetRegion\". " +
                    "Add it to the regions or regionShortCodes maps in your env { } block.",
            )
        }

        return propertyMap
    }
}
