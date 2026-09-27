package io.github.castab.commerce.runtime

import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration.Migrations.OnStartup
import io.github.castab.commerce.runtime.http.CommerceErrorHandling
import io.github.castab.commerce.runtime.http.healthRoutes
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.OfferingsSnapshotRepository
import io.github.castab.commerce.runtime.persistence.PostgresOfferingsSnapshotRepository
import io.github.castab.commerce.runtime.persistence.PostgresPrincipalSessionRepository
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.persistence.createDataSource
import io.github.castab.commerce.runtime.persistence.isReachable
import io.github.castab.commerce.runtime.session.PersistentSessionManager
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.core.HttpHandler
import org.http4k.core.then
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.JettyLoom
import org.http4k.server.asServer
import org.jdbi.v3.core.Jdbi

private val logger = KotlinLogging.logger {}

/**
 * The shared runtime pieces an application may build on: configuration, the transaction
 * boundary, the commerce-owned [offeringsSnapshotRepository], and authenticated principal
 * [sessions]. Application repositories use the same [transactor] and
 * [io.github.castab.commerce.runtime.persistence.Transaction] the runtime uses.
 *
 * [sessions] is how an application that has verified its own credentials starts a session
 * for the resulting `PrincipalId`, and how its routes authenticate later requests (see
 * `sessionAuthentication`). The runtime never sees the credentials themselves.
 *
 * The repository participates in caller-owned transactions, including transactions that
 * also write application data. The runtime has no customer or other application
 * data model, so relationships between application entities and commerce facts stay in
 * application repositories.
 *
 * Part of the provisional application-extension seam; see [ApplicationContributions].
 */
class CommerceRuntimeContext internal constructor(
    val configuration: CommerceRuntimeConfiguration,
    val transactor: Transactor,
    val offeringsSnapshotRepository: OfferingsSnapshotRepository,
    val sessions: SessionManager,
)

/**
 * What a concrete application adds to the commerce runtime.
 *
 * This is deliberately small and not booking-specific. It is the seam through which
 * application-owned capabilities (including a future, strongly typed booking extension)
 * plug into the shared runtime without forking it. [commerceRuntime] requires it
 * explicitly: an application with nothing to add still states so, by passing
 * `ApplicationContributions()`.
 *
 * Together with [CommerceRuntimeContext], this is the provisional application-extension
 * seam, not a settled contract: it is expected to change once the booking extension and
 * further capabilities are designed from real consumer requirements, and it grows only
 * when a concrete consumer needs it.
 *
 * @property migrationLocations Flyway locations of the application's own migrations, and
 *   only those. commerce-runtime discovers its own migrations itself and always applies them
 *   first; the application's are a separate stream with its own schema history and version
 *   space. See [MigrationLifecycle].
 * @property routes The application's own routes, built from the shared
 *   [CommerceRuntimeContext]. They are served behind the same error handling as the
 *   commerce routes.
 */
class ApplicationContributions(
    val migrationLocations: List<String> = emptyList(),
    val routes: (CommerceRuntimeContext) -> List<RoutingHttpHandler> = { emptyList() },
)

/**
 * The commerce runtime of one concrete application: its HTTP handler, the Jetty server
 * serving it, and the connection pool.
 *
 * The runtime manages these resources; it does not own the process. [start] starts Jetty
 * and returns immediately, and [close] stops the server and closes the pool. Blocking,
 * shutdown hooks, and the rest of the process lifecycle belong to the application's own
 * `main`.
 */
class CommerceRuntime internal constructor(
    /** The complete HTTP handler, usable without a server, for example in tests. */
    val http: HttpHandler,
    private val server: Http4kServer,
    private val dataSource: HikariDataSource,
) : AutoCloseable {
    fun start(): CommerceRuntime {
        server.start()
        logger.info { "event=server_started port=${server.port()}" }
        return this
    }

    /** The port being served; the actual port after [start] when the configured port is 0. */
    fun port(): Int = server.port()

    override fun close() {
        logger.info { "event=runtime_stopping" }
        server.stop()
        dataSource.close()
        logger.info { "event=runtime_stopped" }
    }
}

/**
 * Composes the commerce runtime for a concrete application.
 *
 * Composition begins with the migration phase ([MigrationLifecycle]), before anything else
 * is built. With `migrations.onStartup = MIGRATE`, the runtime migrations and then the
 * application migrations are applied. With `VALIDATE`, the default, they are expected to
 * have been applied by a separate migration step, and the phase only validates that both
 * streams are fully applied. Either way, a failure throws Flyway's exception and closes the
 * pool: no runtime is returned, so there is no server to start against a database that is
 * not compatible.
 *
 * Every other dependency is then constructed here, in order, with ordinary Kotlin: Jdbi,
 * the transaction boundary, the session manager, the runtime's infrastructure routes
 * (`/health`, `/ready`, explicitly public) and the [application] routes, the http4k
 * handler, and Jetty. There is no dependency injection container, annotation scanning, or
 * reflection.
 *
 * [application] has no default: the runtime is not an application by itself, and the
 * caller decides what its application contributes. The server is created but not
 * started; call [CommerceRuntime.start].
 */
fun commerceRuntime(
    configuration: CommerceRuntimeConfiguration,
    application: ApplicationContributions,
): CommerceRuntime {
    val dataSource = createDataSource(configuration.database)
    try {
        val migrations = MigrationLifecycle(dataSource, application.migrationLocations)
        when (configuration.migrations.onStartup) {
            OnStartup.MIGRATE -> migrations.migrate()
            OnStartup.VALIDATE -> migrations.validate()
        }

        val jdbi = Jdbi.create(dataSource)
        val transactor = Transactor(jdbi)
        val sessions = PersistentSessionManager(transactor, PostgresPrincipalSessionRepository(), configuration.sessions.lifetime)
        val context = CommerceRuntimeContext(configuration, transactor, PostgresOfferingsSnapshotRepository(), sessions)

        val runtimeRoutes = listOf(healthRoutes(ready = { dataSource.isReachable() }))
        val http = CommerceErrorHandling.then(routes(*(runtimeRoutes + application.routes(context)).toTypedArray()))
        val server = http.asServer(JettyLoom(configuration.server.port))
        return CommerceRuntime(http, server, dataSource)
    } catch (e: Exception) {
        dataSource.close()
        throw e
    }
}
