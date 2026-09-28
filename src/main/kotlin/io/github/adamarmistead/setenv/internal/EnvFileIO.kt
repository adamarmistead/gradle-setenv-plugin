package io.github.adamarmistead.setenv.internal

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission

internal object EnvFileIO {

    /**
     * Reads a .env file into a [LinkedHashMap], preserving insertion order.
     * Blank lines and lines starting with '#' are silently skipped.
     */
    fun read(file: File): LinkedHashMap<String, String> {
        if (!file.exists()) {
            return linkedMapOf()
        }

        val properties = linkedMapOf<String, String>()
        file.forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) {
                return@forEachLine
            }
            val parts = line.split("=", limit = 2)
            val key = parts[0].trim()
            val value = if (parts.size > 1) parts[1] else ""
            properties[key] = value
        }
        return properties
    }

    /**
     * Writes [properties] to [file], sorted alphabetically, one KEY=value per line.
     * Used for cache files (.env-dev, etc.) that are fully managed by the plugin.
     */
    fun write(file: File, properties: Map<String, String>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { writer ->
            properties.toSortedMap().forEach { (key, value) ->
                writer.write("$key=$value")
                writer.newLine()
            }
        }
        restrictFilePermissions(file)
        file.parentFile?.let { ensureGitIgnored(it) }
    }

    /**
     * Merges key-value pairs from [sources] into [target].
     *
     * Unlike a full rewrite, this preserves the structure of [target] exactly:
     *   - Comments (`# ...`) and blank lines are kept where they are.
     *   - Manually added entries not in any source are kept untouched.
     *   - Existing keys whose values are supplied by a source are updated in-place.
     *   - New keys from sources that do not yet exist in the target are appended
     *     at the end, separated by a blank line for readability.
     *
     * This makes the .env file safe to annotate or manually extend without those
     * edits being destroyed on the next `setEnv` run.
     */
    fun mergeInto(target: File, vararg sources: File) {
        val incoming = linkedMapOf<String, String>()
        sources.forEach { source -> incoming.putAll(read(source)) }
        mergeProperties(target, incoming)
    }

    /**
     * Merges an in-memory `properties` map into `target`, preserving existing content.
     *
     * Semantics are identical to [mergeInto] but accept a map directly instead of
     * reading from source files. Used by CreateEnvTask to update static variables
     * without destroying secrets that CreateSecretsTask has already merged in.
     *
     * If `target` does not yet exist, it is created with the given properties.
     * If `properties` is empty, this is a no-op.
     */
    fun mergeProperties(target: File, properties: Map<String, String>) {
        if (properties.isEmpty()) return

        // Target doesn't exist yet — just write the incoming values directly.
        if (!target.exists()) {
            write(target, properties)
            return
        }

        // Ensure target is writable (a previous write may have restricted permissions)
        target.setWritable(true)

        val rawLines = target.readLines().toMutableList()
        val handled = mutableSetOf<String>()

        // Walk existing lines, updating KEY=value lines where we have a new value.
        val updatedLines = rawLines.map { line ->
            when {
                line.isBlank() || line.startsWith("#") -> line
                else -> {
                    val key = line.split("=", limit = 2)[0].trim()
                    if (properties.containsKey(key)) {
                        handled.add(key)
                        "$key=${properties.getValue(key)}"
                    } else {
                        line
                    }
                }
            }
        }.toMutableList()

        // Append any keys that were not already in the file.
        val newKeys = properties.keys - handled
        if (newKeys.isNotEmpty()) {
            if (updatedLines.lastOrNull()?.isNotBlank() == true) {
                updatedLines.add("")  // blank separator before appended block
            }
            newKeys.forEach { key -> updatedLines.add("$key=${properties.getValue(key)}") }
        }

        target.bufferedWriter().use { writer ->
            updatedLines.forEach { line ->
                writer.write(line)
                writer.newLine()
            }
        }

        restrictFilePermissions(target)
        target.parentFile?.let { ensureGitIgnored(it) }
    }

    /**
     * Restricts file permissions to owner read/write only.
     * Prevents other local users on shared machines from reading sensitive environment or cache files.
     *
     * - **POSIX:** sets `rw-------` (`0600`) via the Posix file attribute view.
     * - **Windows (NTFS):** rewrites the ACL to a single ALLOW entry for the current user
     *   with read/write data access, using the NIO [AclFileAttributeView].
     *
     * Best-effort: any failure is swallowed so permission hardening never breaks the build.
     */
    fun restrictFilePermissions(file: File) {
        try {
            val path = file.toPath()
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                val permissions = setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                )
                Files.setPosixFilePermissions(path, permissions)
            } else {
                restrictWindowsAcl(path)
            }
        } catch (_: Exception) {
            // Permission restriction is best-effort; don't fail the build if unsupported.
        }
    }

    /**
     * Restricts a Windows file's ACL to owner-only access by rewriting it to a single
     * ALLOW entry for the current user with full control.
     *
     * The security goal is to prevent **other** local users from reading the file, not to
     * restrict the owner. So we grant the current user all permissions (full control) and
     * remove every other ACL entry. This keeps the file fully functional for its owner
     * while blocking all other accounts on the machine.
     *
     * Uses [java.nio.file.attribute.UserPrincipalLookupService] to resolve the current user's
     * principal (handles domain-qualified names like `DOMAIN\user` correctly).
     */
    private fun restrictWindowsAcl(path: java.nio.file.Path) {
        val aclView = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
        val lookupService = FileSystems.getDefault().userPrincipalLookupService ?: return
        val currentUser = try {
            lookupService.lookupPrincipalByName(System.getProperty("user.name"))
        } catch (_: Exception) {
            return
        }

        // Grant the owner full control — the point is to block OTHER users, not the owner.
        // Uses all file-relevant permissions available in Java's NIO AclEntryPermission enum.
        val ownerEntry = AclEntry.newBuilder()
            .setPrincipal(currentUser)
            .setType(AclEntryType.ALLOW)
            .setPermissions(
                AclEntryPermission.READ_DATA,
                AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA,
                AclEntryPermission.READ_NAMED_ATTRS,
                AclEntryPermission.WRITE_NAMED_ATTRS,
                AclEntryPermission.EXECUTE,
                AclEntryPermission.DELETE_CHILD,
                AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.WRITE_ATTRIBUTES,
                AclEntryPermission.DELETE,
                AclEntryPermission.READ_ACL,
                AclEntryPermission.WRITE_ACL,
                AclEntryPermission.WRITE_OWNER,
                AclEntryPermission.SYNCHRONIZE,
            )
            .build()

        // Replace the entire ACL with just our owner entry — removes all other users/groups.
        aclView.setAcl(listOf(ownerEntry))
    }

    /**
     * Ensures `.env*` wildcard pattern exists in `.gitignore`.
     * Creates `.gitignore` with `.env*` if missing, or appends `.env*` if absent.
     */
    fun ensureGitIgnored(directory: File) {
        val gitIgnore = File(directory, ".gitignore")
        if (!gitIgnore.exists()) {
            gitIgnore.writeText("# Automatically added by gradle-setenv-plugin\n.env*\n")
            return
        }

        val hasPattern = gitIgnore.readLines().any { it.trim() == ".env*" }
        if (!hasPattern) {
            gitIgnore.appendText("\n# Automatically added by gradle-setenv-plugin\n.env*\n")
        }
    }
}
