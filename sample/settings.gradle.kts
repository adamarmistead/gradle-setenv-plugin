rootProject.name = "setenv-sample"

// Pull in the plugin under development from the parent directory.
// This is a composite build, so the plugin is resolved by ID (no version needed)
// straight from the root project's `java-gradle-plugin` output — no publishing required.
includeBuild("..")
