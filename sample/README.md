# Sample App

A minimal consumer of the **gradle-setenv** plugin, wired up as a standalone
composite build so you can try the real plugin without publishing it.

The plugin is pulled in from the parent directory via `includeBuild("..")`
(see [`settings.gradle.kts`](settings.gradle.kts)), so any change you make to
the plugin source is picked up automatically on the next run.

## Run it

```bash
cd sample

# Generate .env (static vars only — no AWS required)
./gradlew setEnv

# Inspect what was written
./gradlew printEnv
```

Expected `.env` output:

```
APP_NAME=setenv-sample
APP_ENV=dev
AWS_REGION=us-east-1
SERVER_PORT=8080
```

## Try the full flow (AWS)

1. Install [AWS CLI v2](https://aws.amazon.com/cli/) and configure a profile.
2. Uncomment the `secrets { }` block in [`build.gradle.kts`](build.gradle.kts)
   and point `secretId` at real secrets.
3. Run:

```bash
./gradlew setEnv --refresh   # force re-fetch, bypassing the local cache
```

## CLI options

| Flag | Description |
| --- | --- |
| `--target=stg` | Override the target environment |
| `--region=usw2` | Override the region (full name or short code) |
| `--refresh` | Force re-fetch of secrets from AWS |
