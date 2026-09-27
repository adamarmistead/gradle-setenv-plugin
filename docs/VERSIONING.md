# Versioning Strategy

This project uses **[`com.palantir.git-version`](https://github.com/palantir/git-version-gradle)** to derive the plugin version from git tags. There is no version file to edit — the tag *is* the version.

## How it works

| Git state                    | `project.version`         |
|------------------------------|---------------------------|
| HEAD is on tag `v0.1.0`      | `0.1.0`                   |
| 5 commits after tag `v0.1.0` | `0.1.1-rc1` (pre-release) |
| No tags exist yet            | `0.1.0` (fallback)        |

The plugin runs `git describe --tags --always --first-parent` under the hood and strips the leading `v`.

## Release workflow

```bash
# 1. Make sure everything is committed and tests pass
./gradlew build

# 2. Tag the release (use semantic versioning)
git tag v0.2.0

# 3. Publish to the Gradle Plugin Portal
./gradlew publishPlugins

# 4. Push the tag (optional, for CI / reference)
git push origin v0.2.0
```

## Version bumping rules

| Change type         | Example                                | Bump                  |
|---------------------|----------------------------------------|-----------------------|
| Breaking DSL change | Rename `environmentVariables` → `vars` | `0.x.y` → `1.0.0`     |
| New feature         | Add a new task or property             | `0.x.y` → `0.(x+1).0` |
| Bug fix / refactor  | Fix a typo, improve error message      | `0.x.y` → `0.x.(y+1)` |

> **Pre-1.0 note:** While the plugin is in `0.x.y`, breaking changes are allowed without a major bump. Once you're confident in the API, tag `v1.0.0` and follow strict semver from there.

## Checking the current version

```bash
./gradlew properties | grep ^version
# or
./gradlew -q printVersion   # (if you add a small task)
```

## CI integration

The GitHub Actions workflow (`.github/workflows/build.yml`) automatically picks up the version from git. When you push a tag, the built artifact will carry that version:

```yaml
# The JAR filename reflects the version:
# build/libs/gradle-setenv-0.2.0.jar
```

## Troubleshooting

| Symptom                            | Cause / Fix                                                                |
|------------------------------------|----------------------------------------------------------------------------|
| Version shows `0.1.0` unexpectedly | No tags exist yet — create one: `git tag v0.1.0`                           |
| Version shows `0.1.1-rc1`          | You're between tags — expected for dev builds                              |
| `git-version` plugin not found     | Ensure you have internet access; it resolves from the Gradle Plugin Portal |
| Configuration cache failure        | Shouldn't happen — this plugin is CC-compatible since v3.x                 |
