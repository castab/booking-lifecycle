package io.github.castab.commerce.runtime.serviceauth

import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.errorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.staff.ServiceId
import org.http4k.contract.ContractRoute
import org.http4k.contract.PreFlightExtraction
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.meta
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.NoOp
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.security.BearerAuthSecurity
import org.http4k.security.NoSecurity
import org.http4k.security.Security
import java.util.UUID

/** Contract routes that a host mounts in its own http4k/OpenAPI contract. */
class ServiceAuthenticationHttpCapability internal constructor(
    val contractRoutes: List<ContractRoute>,
)

/**
 * Declares service access tokens in a host's OpenAPI document as the standard HTTP bearer
 * security scheme `serviceAccessToken`, for example `contract { security = serviceAccessTokenOpenApiSecurity }`.
 *
 * Documentation only: its filter does nothing. Authentication and authorization of every
 * protected route remain the job of the route's `AccessControl`, whose mechanisms the host
 * chose. The tokens are JWTs, but clients must treat them as opaque.
 */
val serviceAccessTokenOpenApiSecurity: Security = BearerAuthSecurity(Filter.NoOp, "serviceAccessToken")

/**
 * The public endpoint at which a SERVICE principal exchanges one of its credentials for a
 * short-lived access token: `POST <path>` with `{"serviceId": "<uuid>", "secret": "<secret>"}`.
 *
 * - `200` with `{"accessToken", "tokenType": "Bearer", "expiresAt", "expiresIn"}`;
 * - `401 unauthenticated` for every authentication failure (unknown or disabled service,
 *   unknown, revoked, or foreign credential, wrong secret), with one message;
 * - `400 malformed_request` when the body is not the expected JSON or `serviceId` is not a UUID.
 *
 * Responses are marked `Cache-Control: no-store`. The route is explicitly public
 * ([NoSecurity]) even in a contract that declares a default security scheme.
 *
 * **This is a sensitive endpoint.** Every syntactically valid attempt costs one memory-hard
 * Argon2id verification (about 19 MiB), including attempts for unknown credentials, which
 * are checked against a dummy hash so timing does not reveal which credentials exist. The
 * runtime has no request rate limiter, so a production deployment must protect this route
 * with rate limiting at its edge or reverse proxy, restrict it to a private or internal
 * network that only its service consumers can reach, or both. Per-attempt failures are
 * logged only at DEBUG.
 *
 * Composition fails with [IllegalStateException] when `serviceTokens` is not configured.
 */
fun serviceAuthenticationHttpCapability(
    context: CommerceRuntimeContext,
    path: String,
    tags: Set<Tag> = emptySet(),
): ServiceAuthenticationHttpCapability {
    require(
        path.startsWith('/') &&
            path.length > 1 &&
            !path.endsWith('/') &&
            path.split('/').drop(1).all { it.isNotBlank() } &&
            path.none { it in "?{}#" },
    ) { "Invalid service authentication path" }
    require(tags.none { it.name.isBlank() }) { "OpenAPI tag names cannot be blank" }
    val tokens = context.serviceAccessTokens
    val requestBody = jsonBody(ServiceAccessTokenRequestDto.serializer())
    val tokenBody = jsonBody(ServiceAccessTokenDto.serializer())
    val errorBody = jsonBody(ErrorResponse.serializer())
    val route =
        path meta {
            operationId = "serviceAuthenticationIssueToken"
            summary = "Exchange a service credential for a short-lived access token"
            description =
                "Authenticates a SERVICE principal with one of its credentials. Send the returned token as " +
                "`Authorization: Bearer <accessToken>` until it expires. The token carries identity only; " +
                "the service's current roles decide what it may do."
            this.tags += tags
            security = NoSecurity
            preFlightExtraction = PreFlightExtraction.IgnoreBody
            receiving(requestBody to ServiceAccessTokenRequestDto(UUID(0, 2).toString(), "<credential secret>"))
            returning(
                Status.OK,
                tokenBody to ServiceAccessTokenDto("<access token>", "Bearer", "2026-01-01T00:15:00Z", tokens.lifetime.seconds),
            )
            returning(Status.UNAUTHORIZED, errorBody to ErrorResponse(ErrorCategory.UNAUTHENTICATED.code, FAILURE))
            returning(Status.BAD_REQUEST, errorBody to ErrorResponse(ErrorCategory.MALFORMED_REQUEST.code, "Malformed request"))
        } bindContract Method.POST to { request: Request ->
            val body = requestBody(request)
            val serviceId = runCatching { ServiceId(UUID.fromString(body.serviceId)) }.getOrNull()
            val issued = ServiceCredentialSecret.parse(body.secret)?.let { secret -> serviceId?.let { tokens.issue(it, secret) } }
            when {
                serviceId == null -> errorResponse(ErrorCategory.MALFORMED_REQUEST, "Malformed request: serviceId")
                issued == null -> errorResponse(ErrorCategory.UNAUTHENTICATED, FAILURE)
                else ->
                    Response(Status.OK).with(
                        tokenBody of
                            ServiceAccessTokenDto(
                                issued.token.value,
                                "Bearer",
                                issued.expiresAt.toString(),
                                tokens.lifetime.seconds,
                            ),
                    )
            }.header("Cache-Control", "no-store")
        }
    return ServiceAuthenticationHttpCapability(listOf(route))
}

private const val FAILURE = "Service authentication failed"
