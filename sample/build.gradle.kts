plugins {
    // Resolved from the included build (see settings.gradle.kts). No version needed.
    id("io.github.adamarmistead.setenv")
}

env {
    target = "dev"
    region = "use1"

    // Static / interpolated values written straight into .env (no AWS needed).
    environmentVariables = mapOf(
        "APP_NAME" to project.name,
        "APP_ENV" to "\${target}",
        "AWS_REGION" to "\${region}",
        "SERVER_PORT" to "\${PORT:8080}",
    )

    // ── Remote secrets (requires AWS CLI + an authenticated profile) ──────────
    // Uncomment and point at real secrets to exercise the full flow:
    //
    // secrets {
    //     create("dbCredentials") {
    //         secretId = "myapp/\${target}/db"
    //         renameKeys = mapOf(
    //             "username" to "DB_USER",
    //             "password" to "DB_PASSWORD",
    //         )
    //     }
    //     create("apiKey") {
    //         secretId = "myapp/\${target}/api-key"
    //         plaintext = true
    //         plaintextKey = "THIRD_PARTY_API_KEY"
    //     }
    // }
}

// Convenience task: print the generated .env so you can see what setEnv produced.
tasks.register("printEnv") {
    notCompatibleWithConfigurationCache("reads the generated .env file at execution time")
    val envFile = layout.projectDirectory.file(".env")
    doLast {
        if (envFile.asFile.exists()) {
            println("─── $envFile ───")
            println(envFile.asFile.readText())
        } else {
            println(".env not found — run `./gradlew setEnv` first.")
        }
    }
}
