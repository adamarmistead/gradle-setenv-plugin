package io.github.adamarmistead.setenv.constants

/**
 * Well-known AWS CLI binary / sub-command names used by the plugin.
 */
object AwsCli {
    const val SECRETS_MANAGER = "aws secretsmanager get-secret-value"

    /**
     * Used to check whether the CLI is already authenticated.
     */
    const val STS_IDENTITY = "aws sts get-caller-identity"

    /**
     * Default login command using the standard AWS CLI v2 SSO flow.
     * The `{profile}` placeholder is replaced with the secret's configured
     * AWS CLI profile at runtime. Override [io.github.adamarmistead.setenv.task.CreateSecretsTask.loginCommand]
     * to use a different authentication method.
     */
    const val LOGIN = "aws sso login --profile {profile}"
}
