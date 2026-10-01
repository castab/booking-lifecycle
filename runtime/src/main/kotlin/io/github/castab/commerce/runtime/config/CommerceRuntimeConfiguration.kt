package io.github.castab.commerce.runtime.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.PropertySource
import java.time.Duration
import java.util.Base64

/**
 * The configuration the commerce runtime requires.
 *
 * commerce-runtime defines this model and the loading machinery ([load]); it ships no
 * configuration file. The concrete application supplies its deployment configuration as
 * the HOCON classpath resource `application.conf`, which [load] reads and then overrides
 * with the environment variables below. Settings the file omits take the defaults declared
 * here, except `database.jdbcUrl`, `database.username`, and `database.password`, which the
 * file must declare (empty placeholders are fine when the environment supplies them).
 * Secrets are supplied only through the environment and are never committed.
 *
 * | Setting | Environment variable |
 * |---|---|
 * | `server.port` | `PORT` |
 * | `database.jdbcUrl` | `DATABASE_JDBC_URL` |
 * | `database.username` | `DATABASE_USERNAME` |
 * | `database.password` | `DATABASE_PASSWORD` |
 * | `database.maximumPoolSize` | `DATABASE_MAXIMUM_POOL_SIZE` |
 * | `database.minimumIdle` | `DATABASE_MINIMUM_IDLE` |
 * | `database.connectionTimeoutMs` | `DATABASE_CONNECTION_TIMEOUT_MS` |
 * | `database.validationTimeoutMs` | `DATABASE_VALIDATION_TIMEOUT_MS` |
 * | `migrations.onStartup` | `MIGRATIONS_ON_STARTUP` (`migrate` or `validate`) |
 * | `sessions.lifetimeMinutes` | `SESSIONS_LIFETIME_MINUTES` |
 * | `serviceTokens.signingKey` | `SERVICE_TOKENS_SIGNING_KEY` |
 * | `serviceTokens.lifetimeMinutes` | `SERVICE_TOKENS_LIFETIME_MINUTES` |
 * | `serviceTokens.issuer` | `SERVICE_TOKENS_ISSUER` |
 *
 * [serviceTokens] is optional: it is configured when the file declares a `serviceTokens`
 * block or the environment supplies `SERVICE_TOKENS_SIGNING_KEY`. Without it, service
 * credentials can still be administered, but no service access token can be issued or
 * accepted, and an application that asks for them fails at composition.
 */
data class CommerceRuntimeConfiguration(
    val server: Server = Server(),
    val database: Database,
    val migrations: Migrations = Migrations(),
    val sessions: Sessions = Sessions(),
    val serviceTokens: ServiceTokens? = null,
) {
    data class Server(
        val port: Int = 8080,
    )

    data class Database(
        val jdbcUrl: String,
        val username: String,
        val password: String,
        val maximumPoolSize: Int = 4,
        val minimumIdle: Int = 1,
        val connectionTimeoutMs: Long = 500,
        val validationTimeoutMs: Long = 1000,
    ) {
        // The password never appears in logs or failure messages.
        override fun toString(): String =
            "Database(jdbcUrl=$jdbcUrl, username=$username, password=****, " +
                "maximumPoolSize=$maximumPoolSize, minimumIdle=$minimumIdle, " +
                "connectionTimeoutMs=$connectionTimeoutMs, validationTimeoutMs=$validationTimeoutMs)"
    }

    /**
     * What composing the runtime does with the runtime and application migrations; see
     * `MigrationLifecycle`.
     *
     * [OnStartup.VALIDATE], the default, suits deployments that migrate in a separate step
     * before instances start: composition applies nothing and fails unless both migration
     * streams are fully applied. [OnStartup.MIGRATE] applies the pending migrations first,
     * the runtime's and then the application's.
     */
    data class Migrations(
        val onStartup: OnStartup = OnStartup.VALIDATE,
    ) {
        enum class OnStartup { MIGRATE, VALIDATE }
    }

    /**
     * Authenticated principal sessions; see `SessionManager`.
     *
     * A session expires [lifetimeMinutes] after it is created, whatever happens in between:
     * expiry is fixed, never sliding. The default is 12 hours. Applications choose the
     * lifetime that suits their clients; it must be between one minute and one year.
     */
    data class Sessions(
        val lifetimeMinutes: Long = 720,
    ) {
        /** [lifetimeMinutes] as a [Duration]. */
        val lifetime: Duration get() = Duration.ofMinutes(lifetimeMinutes)

        companion object {
            /** The longest accepted lifetime: one year. Sessions are not long-lived credentials. */
            const val MAXIMUM_LIFETIME_MINUTES: Long = 525_600
        }
    }

    /**
     * Short-lived service access tokens; see `ServiceAccessTokens`.
     *
     * A token is an HS256-signed JWT that expires [lifetimeMinutes] after it is issued. The
     * default is 15 minutes and the maximum is 60: a token stays valid until it expires even
     * after the credential that obtained it is revoked, so the lifetime bounds that window.
     *
     * [signingKey] is the base64 encoding (standard or URL-safe, padding optional) of at
     * least 32 random bytes, for example the output of `openssl rand -base64 32`. It is a
     * secret supplied through the environment, shared by every instance of one deployment so
     * that each accepts the tokens the others issued, and stable across restarts. There is
     * no default and no generated fallback. It is unrelated to any service credential.
     *
     * [issuer] names this deployment in the token's `iss` and `aud` claims; a token whose
     * claims name another issuer is rejected even if it was signed with the same key.
     */
    data class ServiceTokens(
        val signingKey: String,
        val lifetimeMinutes: Long = 15,
        val issuer: String = "commerce-runtime",
    ) {
        /** [lifetimeMinutes] as a [Duration]. */
        val lifetime: Duration get() = Duration.ofMinutes(lifetimeMinutes)

        /** The decoded signing key, or `null` when [signingKey] is not base64. */
        @JvmSynthetic
        internal fun signingKeyBytes(): ByteArray? =
            signingKey.trim().let { key ->
                runCatching { Base64.getDecoder().decode(key) }.getOrNull()
                    ?: runCatching { Base64.getUrlDecoder().decode(key) }.getOrNull()
            }

        // The signing key never appears in logs or failure messages.
        override fun toString(): String = "ServiceTokens(signingKey=****, lifetimeMinutes=$lifetimeMinutes, issuer=$issuer)"

        companion object {
            /** The shortest accepted signing key: 256 bits, the HS256 minimum. */
            const val MINIMUM_SIGNING_KEY_BYTES: Int = 32

            /** The longest accepted token lifetime: one hour. Access tokens are not long-lived credentials. */
            const val MAXIMUM_LIFETIME_MINUTES: Long = 60
        }
    }

    /** Fails with [IllegalArgumentException], naming the environment variable, when a value is unusable. */
    fun validate() {
        require(server.port in 0..65535) { "PORT must be between 0 and 65535" }
        require(database.jdbcUrl.isNotBlank()) { "DATABASE_JDBC_URL is required" }
        require(database.jdbcUrl.startsWith("jdbc:postgresql:")) { "DATABASE_JDBC_URL must be a PostgreSQL JDBC URL" }
        require(database.username.isNotBlank()) { "DATABASE_USERNAME is required" }
        require(database.password.isNotBlank()) { "DATABASE_PASSWORD is required" }
        require(database.maximumPoolSize > 0) { "DATABASE_MAXIMUM_POOL_SIZE must be positive" }
        require(database.minimumIdle in 0..database.maximumPoolSize) {
            "DATABASE_MINIMUM_IDLE must be between zero and DATABASE_MAXIMUM_POOL_SIZE"
        }
        require(database.connectionTimeoutMs > 0) { "DATABASE_CONNECTION_TIMEOUT_MS must be positive" }
        require(database.validationTimeoutMs > 0) { "DATABASE_VALIDATION_TIMEOUT_MS must be positive" }
        require(sessions.lifetimeMinutes in 1..Sessions.MAXIMUM_LIFETIME_MINUTES) {
            "SESSIONS_LIFETIME_MINUTES must be between 1 and ${Sessions.MAXIMUM_LIFETIME_MINUTES}"
        }
        serviceTokens?.let { tokens ->
            require(tokens.signingKey.isNotBlank()) { "SERVICE_TOKENS_SIGNING_KEY is required" }
            val key = requireNotNull(tokens.signingKeyBytes()) { "SERVICE_TOKENS_SIGNING_KEY must be base64" }
            require(key.size >= ServiceTokens.MINIMUM_SIGNING_KEY_BYTES) {
                "SERVICE_TOKENS_SIGNING_KEY must encode at least ${ServiceTokens.MINIMUM_SIGNING_KEY_BYTES} bytes"
            }
            require(key.any { it != key[0] }) { "SERVICE_TOKENS_SIGNING_KEY must be random, not a repeated byte" }
            require(tokens.lifetimeMinutes in 1..ServiceTokens.MAXIMUM_LIFETIME_MINUTES) {
                "SERVICE_TOKENS_LIFETIME_MINUTES must be between 1 and ${ServiceTokens.MAXIMUM_LIFETIME_MINUTES}"
            }
            require(tokens.issuer.isNotBlank() && tokens.issuer == tokens.issuer.trim()) {
                "SERVICE_TOKENS_ISSUER must not be blank or padded"
            }
        }
    }

    companion object {
        /**
         * Loads the application's [resource] from the classpath, applies [environment]
         * overrides, and validates.
         *
         * The resource belongs to the concrete application; commerce-runtime does not ship
         * one. A missing resource fails with [IllegalArgumentException].
         *
         * Only the environment variables listed on [CommerceRuntimeConfiguration] are read,
         * so unrelated variables can never change the configuration.
         */
        fun load(
            environment: Map<String, String> = System.getenv(),
            resource: String = "/application.conf",
        ): CommerceRuntimeConfiguration {
            requireNotNull(CommerceRuntimeConfiguration::class.java.getResource(resource)) {
                "Configuration resource $resource was not found on the classpath; the concrete application supplies it"
            }
            return ConfigLoaderBuilder
                .empty()
                .addDefaultDecoders()
                .addDefaultParsers()
                .addDefaultParamMappers()
                .addDefaultNodeTransformers()
                .addDefaultResolvers()
                .addSource(PropertySource.resource(resource))
                .build()
                .loadConfigOrThrow<CommerceRuntimeConfiguration>()
                .withEnvironmentOverrides(Environment(environment))
                .also { it.validate() }
        }
    }
}

private fun CommerceRuntimeConfiguration.withEnvironmentOverrides(environment: Environment) =
    copy(
        server = server.copy(port = environment.int("PORT", server.port)),
        database =
            database.copy(
                jdbcUrl = environment.string("DATABASE_JDBC_URL", database.jdbcUrl),
                username = environment.string("DATABASE_USERNAME", database.username),
                password = environment.string("DATABASE_PASSWORD", database.password),
                maximumPoolSize = environment.int("DATABASE_MAXIMUM_POOL_SIZE", database.maximumPoolSize),
                minimumIdle = environment.int("DATABASE_MINIMUM_IDLE", database.minimumIdle),
                connectionTimeoutMs = environment.long("DATABASE_CONNECTION_TIMEOUT_MS", database.connectionTimeoutMs),
                validationTimeoutMs = environment.long("DATABASE_VALIDATION_TIMEOUT_MS", database.validationTimeoutMs),
            ),
        migrations = migrations.copy(onStartup = environment.onStartup("MIGRATIONS_ON_STARTUP", migrations.onStartup)),
        sessions = sessions.copy(lifetimeMinutes = environment.long("SESSIONS_LIFETIME_MINUTES", sessions.lifetimeMinutes)),
        serviceTokens = serviceTokens.withEnvironmentOverrides(environment),
    )

private val serviceTokenVariables = listOf("SERVICE_TOKENS_SIGNING_KEY", "SERVICE_TOKENS_LIFETIME_MINUTES", "SERVICE_TOKENS_ISSUER")

/**
 * The environment completes a declared block, or declares one by supplying the signing key.
 * Any other service token variable without a signing key from either source is rejected,
 * so a lifetime or issuer can never be silently ignored.
 */
private fun CommerceRuntimeConfiguration.ServiceTokens?.withEnvironmentOverrides(
    environment: Environment,
): CommerceRuntimeConfiguration.ServiceTokens? {
    val base =
        this ?: environment.optional("SERVICE_TOKENS_SIGNING_KEY")?.let { CommerceRuntimeConfiguration.ServiceTokens(signingKey = it) }
    if (base == null) {
        val stray = serviceTokenVariables.filter { environment.optional(it) != null }
        require(stray.isEmpty()) { "SERVICE_TOKENS_SIGNING_KEY is required when ${stray.joinToString()} is set" }
        return null
    }
    return base.copy(
        signingKey = environment.string("SERVICE_TOKENS_SIGNING_KEY", base.signingKey),
        lifetimeMinutes = environment.long("SERVICE_TOKENS_LIFETIME_MINUTES", base.lifetimeMinutes),
        issuer = environment.string("SERVICE_TOKENS_ISSUER", base.issuer),
    )
}

private class Environment(
    private val values: Map<String, String>,
) {
    fun string(
        name: String,
        fallback: String,
    ): String = values[name] ?: fallback

    fun optional(name: String): String? = values[name]

    fun int(
        name: String,
        fallback: Int,
    ): Int = values[name]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer") } ?: fallback

    fun long(
        name: String,
        fallback: Long,
    ): Long = values[name]?.let { it.toLongOrNull() ?: throw IllegalArgumentException("$name must be an integer") } ?: fallback

    fun onStartup(
        name: String,
        fallback: CommerceRuntimeConfiguration.Migrations.OnStartup,
    ): CommerceRuntimeConfiguration.Migrations.OnStartup =
        values[name]?.let { value ->
            CommerceRuntimeConfiguration.Migrations.OnStartup.entries
                .firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("$name must be migrate or validate")
        } ?: fallback
}
