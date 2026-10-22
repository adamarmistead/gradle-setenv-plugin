package io.github.adamarmistead.setenv.internal

import java.io.File
import java.io.OutputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

internal object EnvFileIO {

    private val isPosix: Boolean =
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

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
     *
     * The file is created (or truncated) with owner-only permissions **before** any
     * content is written, eliminating any window where secret data is world-readable.
     */
    fun write(file: File, properties: Map<String, String>) {
        file.parentFile?.mkdirs()

        val path = file.toPath()
        openSecureOutputStream(path).use { out ->
            properties.toSortedMap().forEach { (key, value) ->
                out.write("$key=$value\n".toByteArray())
            }
        }

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
     * Merges an in-memory `properties` map into [target], preserving existing content.
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

        openSecureOutputStream(target.toPath()).use { out ->
            updatedLines.forEach { line ->
                out.write("$line\n".toByteArray())
            }
        }

        target.parentFile?.let { ensureGitIgnored(it) }
    }

    /**
     * Writes a multi-line secret value to a protected file in the `.env-secrets/` directory
     * (sibling to the `.env` file). The file is created with `0600` permissions before any
     * content is written, and the directory is restricted to `0700`.
     *
     * @param envDir the directory containing the `.env` file (typically the project root)
     * @param key the environment variable name, used as the filename
     * @param value the raw secret content to write
     * @return the [File] that was written (caller uses its absolute path for the env var)
     * @throws IllegalArgumentException if [key] contains path separators or traversal sequences
     */
    fun writeSecretFile(envDir: File, key: String, value: String): File {
        // Validate key to prevent path traversal (e.g. "../../evil" or "a/b")
        require(!key.contains('/') && !key.contains('\\') && !key.contains("..")) {
            "Secret key '$key' contains invalid characters. " +
                "Keys must be a single identifier with no path separators or '..'."
        }

        val secretsDir = File(envDir, ".env-secrets")
        if (!secretsDir.exists()) {
            envDir.mkdirs()
            if (isPosix) {
                val dirPerms = setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                )
                Files.createDirectory(secretsDir.toPath(), PosixFilePermissions.asFileAttribute(dirPerms))
            } else {
                secretsDir.mkdirs()
                restrictDirPermissions(secretsDir)
            }
        } else {
            // Already exists (previous run) — ensure it's still restricted.
            restrictDirPermissions(secretsDir)
        }

        val file = File(secretsDir, key)
        openSecureOutputStream(file.toPath()).use { out ->
            out.write(value.toByteArray())
        }

        ensureGitIgnored(envDir)
        return file
    }

    /**
     * Restricts a directory's permissions to owner-only (0700 on POSIX).
     * Prevents other local users from listing the directory contents (secret filenames).
     */
    private fun restrictDirPermissions(dir: File) {
        try {
            val path = dir.toPath()
            if (isPosix) {
                val permissions = setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,  // needed to traverse/list
                )
                Files.setPosixFilePermissions(path, permissions)
            } else {
                restrictWindowsAcl(path)
            }
        } catch (e: Exception) {
            System.err.println(
                "[gradle-setenv] WARNING: Could not restrict directory permissions on ${dir.absolutePath}: ${e.message}.",
            )
        }
    }

    /**
     * Opens a [java.io.OutputStream] for writing to [path], ensuring the file has
     * owner-only permissions **before** any content is written.
     *
     * Strategy:
     * - **New POSIX files:** created with `0600` via the creation attribute — zero window.
     * - **Existing POSIX files:** restricted to `0600` before truncate+write (best-effort).
     * - **Windows:** file is created/truncated, then ACL restricted before write (best-effort).
     *
     * At no point does the file contain sensitive content with incorrect permissions.
     * Permission failures are best-effort (logged, not fatal) to avoid breaking builds
     * on filesystems that don't support POSIX/NTFS permission models.
     */
    private fun openSecureOutputStream(path: java.nio.file.Path): OutputStream {
        if (isPosix) {
            val perms = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )
            if (!Files.exists(path)) {
                // Create with restrictive permissions from birth — zero window.
                Files.createFile(path, PosixFilePermissions.asFileAttribute(perms))
            } else {
                // Existing file: ensure restricted before we truncate and write new content.
                try {
                    Files.setPosixFilePermissions(path, perms)
                } catch (e: Exception) {
                    System.err.println(
                        "[gradle-setenv] WARNING: Could not restrict permissions on ${path}: ${e.message}.",
                    )
                }
            }
            return Files.newOutputStream(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        } else {
            val stream = Files.newOutputStream(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                restrictWindowsAcl(path)
            } catch (e: Exception) {
                System.err.println(
                    "[gradle-setenv] WARNING: Could not restrict ACL on ${path}: ${e.message}.",
                )
            }
            return stream
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
