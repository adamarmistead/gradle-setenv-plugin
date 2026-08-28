package io.github.adamarmistead.setenv.internal

import org.gradle.api.Project

/**
 * Utility helpers that operate on a [Project] instance.
 * Extracted from the plugin class so it stays focused on wiring.
 */
internal object ProjectUtils {

    /**
     * Ensures a file path is within the project directory.
     * Prevents secrets from being written to arbitrary locations.
     * Uses lexical normalization (no filesystem access) so it works for files that don't exist yet.
     */
    fun validatePathWithinProject(file: java.io.File, projectDir: java.io.File, propertyName: String) {
        val resolved = file.absoluteFile.normalize()
        if (!resolved.toPath().startsWith(projectDir.toPath())) {
            throw IllegalStateException(
                "env { $propertyName = '${file.path}' } resolves to '${resolved.path}' which is outside the project directory ($projectDir).\n" +
                    "For security, all file paths must be within the project root.",
            )
        }
    }
}
