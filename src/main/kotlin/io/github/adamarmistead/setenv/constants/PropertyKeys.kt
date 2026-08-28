package io.github.adamarmistead.setenv.constants

/**
 * Named keys for the property map that [io.github.adamarmistead.setenv.internal.PropertyMapBuilder]
 * produces and the tasks consume.
 *
 * Keeping these in one place makes the implicit contract between producer
 * and consumer explicit and safe to rename with a single edit.
 */
object PropertyKeys {
    const val TARGET = "target"
    const val REGION = "region"
    const val REGION_SHORT = "regionShort"
}
