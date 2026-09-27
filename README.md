# Gradle SetEnv Plugin

[![GitHub repository](https://img.shields.io/badge/GitHub-adamarmistead/gradle-setenv-plugin-blue?logo=github)](https://github.com/adamarmistead/gradle-setenv-plugin)

A Gradle plugin that automates local environment setup (`.env`) and cloud secrets manager sync for developers.

No more passing secret files over Slack, maintaining out-of-date onboarding guides, or manual configuration setup for new hires. Running `./gradlew setEnv` provisions a ready-to-run `.env` file for your local environment in seconds.

---

## Prerequisites

The plugin itself only requires **JDK 17+** and a compatible Gradle version. However, fetching secrets from a provider requires the corresponding CLI tool to be installed and authenticated on your machine:

| Provider              | Required CLI                                                         | Notes                                                                                                                                 |
|-----------------------|----------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| **AWS** (default)     | [AWS CLI v2](https://aws.amazon.com/cli/)                            | Uses the standard `aws sso login` flow before fetching from Secrets Manager. Ensure your AWS profile is configured (`aws configure`). |
| **GCP** *(planned)*   | [gcloud CLI](https://cloud.google.com/sdk/gcloud)                    | Will be supported via `provider = "gcp"` in the secrets block.                                                                        |
| **Azure** *(planned)* | [Azure CLI](https://learn.microsoft.com/cli/azure/install-azure-cli) | Future support.                                                                                                                       |

> **Tip:** If you only need static `environmentVariables` (no remote secrets), no CLI tools are required — the plugin will simply write your configured values to `.env`.

---

## Quick Start

### 1. Apply Plugin & Configure DSL

```kotlin
plugins {
    id("io.github.adamarmistead.setenv") version "1.0.0"
}

env {
    environmentVariables = mapOf(
        "APP_NAME" to project.name,
        "APP_ENV" to "\${target}",
        "AWS_REGION" to "\${region}",
        "SERVER_PORT" to "\${PORT:8080}",
    )

    secrets {
        create("dbCredentials") {
            secretId = "${project.name}/\${target}/db"
            renameKeys = mapOf(
                "username" to "DB_USER",
                "password" to "DB_PASSWORD",
            )
        }
        create("apiKey") {
            secretId = "${project.name}/\${target}/api-key"
            plaintext = true
            plaintextKey = "THIRD_PARTY_API_KEY"
        }
    }
}
```

### 2. Run

```bash
# Default environment (dev)
./gradlew setEnv

# Specific target and region
./gradlew setEnv --target=stg --region=us-west-2

# Force re-fetch from AWS (bypasses local cache)
./gradlew setEnv --refresh
```

---

## Features

- **AWS Secrets Manager sync** — fetches JSON or plaintext secrets and maps them to `.env` keys
- **Offline caching** — secrets cached in `.env-<target>`; re-runs skip AWS unless `--refresh`
- **Smart interpolation** — `${target}`, `${region}`, `${regionShort}`, Gradle properties, OS env vars, with `${KEY:default}` fallbacks
- **Non-destructive merge** — preserves your comments, blank lines, and manual overrides in `.env`
- **CI safety** — tasks auto-disable when `CI=true` is set
- **Security hardening** — generated files restricted to owner-only (`0600`), `.gitignore` auto-managed, path traversal blocked, shell metacharacters rejected in commands
- **Pluggable auth** — customize the login command (e.g. for non-SSO flows) via `loginCommand`

---

## Configuration Reference

### `env { }` extension

| Property               | Description                             | Default                |
|------------------------|-----------------------------------------|------------------------|
| `target`               | Target environment name                 | `"dev"`                |
| `region`               | AWS region (full name or short code)    | `"us-east-1"`          |
| `envFile`              | Output file path                        | `.env` in root project |
| `environmentVariables` | Static / interpolated env vars to write | `{}`                   |
| `regions`              | Custom short-code → full region mapping | all 25 AWS commercial regions |
| `regionShortCodes`     | Custom full-region → short code mapping | derived from `regions`   |

### `secrets { }` block

| Property       | Description                                                                             | Default                                             |
|----------------|-----------------------------------------------------------------------------------------|-----------------------------------------------------|
| `secretId`     | AWS Secret ID (supports placeholders)                                                   | *required*                                          |
| `profile`      | AWS CLI profile (used for both SSO login and fetching)                                  | `"default"`                                         |
| `secretKeys`   | Filter which JSON keys to extract (renamed keys always survive)                         | all keys                                            |
| `renameKeys`   | Map original key → new ENV name                                                         | `{}`                                                |
| `plaintext`    | Treat value as plain text, not JSON                                                     | `false`                                             |
| `plaintextKey` | ENV key name when `plaintext = true`                                                    | *required if plaintext*                             |
| `secretsFile`  | Cache file for this environment's fetched values                                        | `.env-<target>` in root project (shared)            |

### Task properties (`createSecrets`)

| Property         | Description                                             | Default                               |
|------------------|---------------------------------------------------------|---------------------------------------|
| `commandTimeout` | Timeout (seconds) for AWS CLI commands                  | `60`                                  |
| `loginTimeout`   | Timeout (seconds) for the interactive login step        | `300`                                 |
| `loginCommand`   | Auth command template. Supports `{profile}` placeholder | `"aws sso login --profile {profile}"` |

> **Custom auth example:** If you use a non-SSO flow (e.g. a custom script), set `loginCommand` on the `createSecrets` task:
>
> ```kotlin
> tasks.named("createSecrets") {
>     loginCommand = "my-custom-login --profile {profile}"
> }
> ```

---

## String Interpolation

Two layers of substitution are available:

**Build-time (Gradle):** Standard Kotlin/Groovy string interpolation in your build script.

```kotlin
"APP_NAME" to project.name
```

**Task-runtime (plugin):** Placeholders resolved when `setEnv` runs, via Apache `StringSubstitutor`. Supports `${KEY:default}` fallbacks.

| Placeholder          | Resolves to                                  |
|----------------------|----------------------------------------------|
| `${target}`          | Active environment (`dev`, `stg`, …)         |
| `${region}`          | Full region name (`us-east-1`)               |
| `${regionShort}`     | Short code (`use1`)                          |
| `${ANY_VAR:default}` | OS env var or Gradle property, with fallback |

---

## Security

The plugin applies several safeguards to keep secrets out of version control and off shared machines:

| Mechanism                   | Detail                                                                                                                                                      |
|-----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **File permissions**        | Generated `.env` and cache files are restricted to `rw-------` (owner read/write only) on POSIX systems.                                                    |
| **`.gitignore` management** | The plugin ensures a `.env*` wildcard pattern exists in the project's `.gitignore`, creating or appending as needed.                                        |
| **Path validation**         | All configured file paths (`envFile`, `secretsFile`) must resolve within the project directory. Path traversal attempts are rejected at configuration time. |
| **Command injection guard** | The internal command executor rejects any command containing shell metacharacters (`;`, `&`, `\|`, backticks, `$`, redirects, quotes, newlines).            |
| **CI auto-disable**         | When the `CI` environment variable is set, all plugin tasks are disabled to prevent accidental secret writes in CI pipelines.                               |

---

## How It Works

```
┌─────────────────────────────────────────────────────────────────────┐
│  ./gradlew setEnv --target=dev --region=use1                        │
└──────────────────────────────────┬──────────────────────────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │       createEnv task         │
                    │  • Resolves placeholders     │
                    │  • Merges into .env (0600)   │
                    │  • Ensures .gitignore entry  │
                    └──────────────┬──────────────┘
                                   │
                    ┌──────────────▼──────────────┐
                    │     createSecrets task       │
                    │  1. Check shared cache file  │
                    │     (.env-<target>)          │
                    │  2. If exists & !refresh:    │
                    │     → merge into .env, done  │
                    │  3. Otherwise, for each      │
                    │     secrets block:           │
                    │     a. STS identity check    │
                    │     b. Login if needed       │
                    │     c. Fetch from AWS        │
                    │     d. Parse JSON/plaintext  │
                    │     e. Rename + filter keys  │
                    │  4. Write all → cache file   │
                    │  5. Merge cache → .env       │
                    └─────────────────────────────┘
```

**Caching:** All secrets for a given environment share a single cache file (`.env-<target>`). On subsequent runs, if the cache exists and `--refresh` is not set, the AWS CLI is never invoked — all values are served from the local cache. This makes local development fast and works offline.

**Merge strategy:** The final `.env` is updated in-place — existing comments, blank lines, and manually added keys are preserved. Only values supplied by the plugin are overwritten or appended.

---

## Sample Project

A working example lives in [`sample/`](sample) as a composite build that pulls the plugin from source (no publishing needed):

```bash
cd sample
./gradlew setEnv       # generates .env (static vars only — no AWS required)
./gradlew printEnv     # prints the generated .env
```

See [`sample/README.md`](sample/README.md) for details.
