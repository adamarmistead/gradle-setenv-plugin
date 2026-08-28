# Gradle 10 Migration — `Project.getProperties()` removal

**Status:** Deferred. Works fine on the current Gradle wrapper (9.7.x). Action required **only when bumping to Gradle 10**.

## Why this matters

`PropertyMapBuilder.build()` reads the substitution property map via the deprecated
`Project.getProperties()`:

```kotlin
// src/main/kotlin/io/github/adamarmistead/setenv/internal/PropertyMapBuilder.kt
@Suppress("DEPRECATION")
project.properties.forEach { (key, value) -> ... }
```

- **Gradle 9.x:** deprecated — still works (the `@Suppress("DEPRECATION")` keeps it compiling).
- **Gradle 10:** **removed** — this line will no longer compile.

## The replacement is NOT a drop-in

The documented Providers API replacement is:

```kotlin
project.providers.gradlePropertiesPrefixedBy("").get()   // Map<String, String>
```

An empty prefix matches every name (case-sensitive "starts with"), so this returns the
**full build-level Gradle property set**. Verified to compile and run on Gradle 9.7.0.

Per the [Gradle docs](https://docs.gradle.org/current/dsl/org.gradle.api.provider.ProviderFactory.html#org.gradle.api.provider.ProviderFactory:gradlePropertiesPrefixedBy(java.lang.String)),
`gradlePropertiesPrefixedBy` resolves "at the build level from the same sources as
`gradleProperty`", in this priority order:

1. Command-line project properties (`-P` args)
2. System properties with the `org.gradle.project.` prefix
3. Environment variables with the `ORG_GRADLE_PROJECT_` prefix
4. `gradle.properties` in the Gradle User Home dir
5. `gradle.properties` in the build root dir
6. `gradle.properties` in the Gradle installation dir

And it is **explicitly excluded**:

> It does not include properties from `gradle.properties` files in subproject directories,
> nor does it include **extra properties** or other properties set dynamically on individual
> `Project` instances.

### The gap that matters for this plugin

The current `project.properties` is a **superset** — it also includes **extra (`ext`)
properties** and dynamically-set project properties. A naive swap to
`gradlePropertiesPrefixedBy("")` would therefore **silently drop any `${...}` placeholder
that resolves from an `ext` value**, e.g.:

```kotlin
// build.gradle.kts
ext.awsAccount = "1234567890"
env { environmentVariables["AWS_ACCOUNT"] = "${awsAccount}" }   // would break after a naive swap
```

## The correct migration (a merge, not a swap)

When upgrading to Gradle 10, replace the `project.properties` block with:

```kotlin
// 1) build-level gradle props: -P, org.gradle.project.*, ORG_GRADLE_PROJECT_*, gradle.properties
propertyMap.putAll(project.providers.gradlePropertiesPrefixedBy("").get())

// 2) extra / dynamically-set project props (NOT covered by the Providers call above)
project.extensions.extraProperties.forEach { (key, value) ->
    propertyMap[key] = when (value) {
        is Property<*> -> value.orNull?.toString() ?: ""
        else -> value.toString()
    }
}
```

Everything else in the existing cascade (`System.getenv()`, `System.getProperties()`,
`target`, `region`, `regionShort`) is unaffected and stays as-is.

## Before you ship it

Add a regression test that sets an **`ext` property** and asserts it still resolves through
`${...}` substitution — that is the exact case a naive migration would break. (The throwaway
`ProvidersApiExplorationTest.kt` used to confirm the API surface has been removed; write a
proper test as part of the Gradle 10 bump.)

## References

- `ProviderFactory.gradlePropertiesPrefixedBy(String)` — https://docs.gradle.org/current/dsl/org.gradle.api.provider.ProviderFactory.html#org.gradle.api.provider.ProviderFactory:gradlePropertiesPrefixedBy(java.lang.String)
- `ProviderFactory.gradleProperty(String)` (resolution order + exclusions) — same page
