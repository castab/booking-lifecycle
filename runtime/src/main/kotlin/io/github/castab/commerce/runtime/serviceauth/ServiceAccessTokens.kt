package io.github.castab.commerce.runtime.serviceauth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.http.RequestAuthenticator
import io.github.castab.commerce.runtime.http.authentication
import io.github.castab.commerce.runtime.http.bearerCredential
import io.github.castab.commerce.runtime.persistence.AuthorizationRepository
import io.github.castab.commerce.runtime.persistence.PrincipalIdColumns
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.ServiceId
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.core.Filter
import org.http4k.core.Request
import java.security.MessageDigest
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * A short-lived bearer token that authenticates one SERVICE principal.
 *
 * It is a compact JWS (an HS256-signed JWT) issued by [ServiceAccessTokens.issue]. It proves
 * recent authentication with a service credential and carries identity only: the service's
 * id (`sub`), its principal kind, issuer and audience, issue and expiry times, and a unique
 * token id. It never carries the credential secret, roles, or permissions.
 *
 * Instances exist only for text in the compact JWS shape, at most [MAXIMUM_LENGTH]
 * characters. Whether it is valid is decided only by [ServiceAccessTokens.resolve]. [value]
 * is the secret bearer credential; [toString] never reveals it.
 */
class ServiceAccessToken private constructor(
    /** The raw bearer token. Send it only in `Authorization: Bearer`; never persist or log it. */
    val value: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ServiceAccessToken &&
            MessageDigest.isEqual(value.toByteArray(Charsets.US_ASCII), other.value.toByteArray(Charsets.US_ASCII))

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "ServiceAccessToken(****)"

    companion object {
        /** Longer text is rejected before any parsing. */
        const val MAXIMUM_LENGTH: Int = 4096

        private val compactJws = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

        /** The token [value] represents, or `null` when it is not shaped like a compact JWS. */
        @JvmStatic
        fun parse(value: String): ServiceAccessToken? =
            value.takeIf { it.length <= MAXIMUM_LENGTH && compactJws.matches(it) }?.let(::ServiceAccessToken)

        @JvmSynthetic
        internal fun issued(value: String): ServiceAccessToken = ServiceAccessToken(value)
    }
}

/**
 * A newly issued [token] for [serviceId], valid from [issuedAt] until [expiresAt]
 * (exclusive). [toString] redacts the token.
 */
data class IssuedServiceAccessToken(
    val token: ServiceAccessToken,
    val serviceId: ServiceId,
    val issuedAt: Instant,
    val expiresAt: Instant,
)

/**
 * Short-lived access tokens for SERVICE principals, available to applications as
 * `CommerceRuntimeContext.serviceAccessTokens` when `serviceTokens` is configured.
 *
 * The flow is: a service presents its id and one of its credential secrets to [issue] (the
 * runtime's token endpoint does this), receives a token that expires after
 * `serviceTokens.lifetimeMinutes`, and sends it as `Authorization: Bearer <token>`.
 * [ServiceAccessTokenAuthenticator] then resolves it to the service's `ServiceId`, and the
 * service is authorized through its current roles like any other principal.
 *
 * Revocation and suspension semantics:
 * - A token is not stored and cannot be revoked individually. It stays valid until it
 *   expires, even after the credential that obtained it is revoked; the configured lifetime
 *   (at most one hour) bounds that window.
 * - Every [resolve] checks that the service still exists and is ACTIVE. Disabling a service
 *   **suspends** its tokens: they stop authenticating on the next request. It does not
 *   revoke them: if the service is activated again, every token it was issued that has not
 *   yet expired authenticates again. (A service's sessions, by contrast, are revoked when it
 *   is disabled.)
 * - Tokens carry no permissions. Removing a role or a grant affects the very next request.
 *
 * After a suspected credential compromise: disable the service, revoke the compromised
 * credentials (and create replacements), and keep the service disabled until the token
 * lifetime has elapsed since the last token could have been issued to the attacker. Only
 * then activate it again.
 *
 * A token can be replayed by anyone who obtains it until it expires: it is a bearer
 * credential. Send it only over TLS and never log it.
 */
interface ServiceAccessTokens {
    /** How long an issued token stays valid. */
    val lifetime: Duration

    /**
     * Authenticates [serviceId] with [secret] and issues a token, or answers `null` when the
     * service is unknown or not ACTIVE, the credential is unknown, revoked, or belongs to
     * another service, or the secret does not match. The reason is never returned.
     */
    fun issue(
        serviceId: ServiceId,
        secret: ServiceCredentialSecret,
    ): IssuedServiceAccessToken?

    /**
     * The service [token] authenticates, or `null` when its signature, type, issuer,
     * audience, or claims are invalid, it has expired, or the service is missing or disabled.
     */
    fun resolve(token: ServiceAccessToken): ServiceId?
}

/**
 * The runtime's [ServiceAccessTokens]: HS256 JWS through Nimbus JOSE+JWT, signed with the
 * configured key. Only HS256 tokens with this runtime's JOSE type are accepted, so unsigned
 * (`alg: none`) tokens, other algorithms, and other kinds of JWT signed with the same key are
 * rejected. Expiry is judged by [clock], with no tolerance, and times are whole seconds as
 * JWT requires.
 */
internal class SignedServiceAccessTokens(
    configuration: CommerceRuntimeConfiguration.ServiceTokens,
    private val credentials: PersistentServiceCredentials,
    private val transactor: Transactor,
    private val principals: AuthorizationRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ServiceAccessTokens {
    override val lifetime: Duration = configuration.lifetime
    private val issuer = configuration.issuer
    private val signer: MACSigner
    private val processor: DefaultJWTProcessor<SecurityContext>

    init {
        // The same policy as CommerceRuntimeConfiguration.validate(): this type never signs or
        // verifies with a configuration that validation would reject.
        val key = configuration.validatedSigningKey()
        signer = MACSigner(key)
        processor =
            DefaultJWTProcessor<SecurityContext>().apply {
                jwsTypeVerifier = DefaultJOSEObjectTypeVerifier(TYPE)
                jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.HS256, ImmutableSecret(key))
                jwtClaimsSetVerifier =
                    object : DefaultJWTClaimsVerifier<SecurityContext>(
                        setOf(issuer),
                        JWTClaimsSet
                            .Builder()
                            .issuer(issuer)
                            .claim(PRINCIPAL_KIND, PrincipalIdColumns.SERVICE)
                            .build(),
                        setOf("sub", "iat", "exp", "jti"),
                        null,
                    ) {
                        override fun currentTime(): Date = Date.from(clock.instant())
                    }.apply { maxClockSkew = 0 }
            }
    }

    override fun issue(
        serviceId: ServiceId,
        secret: ServiceCredentialSecret,
    ): IssuedServiceAccessToken? {
        val credential = credentials.authenticate(serviceId, secret) ?: return null
        val issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val expiresAt = issuedAt.plus(lifetime)
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .audience(issuer)
                .subject(serviceId.value.toString())
                .claim(PRINCIPAL_KIND, PrincipalIdColumns.SERVICE)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).type(TYPE).build(), claims)
        jwt.sign(signer)
        logger.info {
            "event=service_access_token_issued service=${serviceId.value} credential=${credential.value} " +
                "token_id=${claims.jwtid} expires_at=$expiresAt"
        }
        return IssuedServiceAccessToken(ServiceAccessToken.issued(jwt.serialize()), serviceId, issuedAt, expiresAt)
    }

    override fun resolve(token: ServiceAccessToken): ServiceId? {
        val claims =
            try {
                processor.process(token.value, null)
            } catch (e: BadJOSEException) {
                logger.debug { "event=service_access_token_rejected reason=${e.javaClass.simpleName}" }
                return null
            } catch (_: JOSEException) {
                return null
            } catch (_: ParseException) {
                return null
            }
        val serviceId = runCatching { ServiceId(UUID.fromString(claims.subject)) }.getOrNull() ?: return null
        val status = transactor.inTransaction { principals.principalStatus(it, serviceId) }
        return serviceId.takeIf { status == PrincipalStatus.ACTIVE }
    }

    companion object {
        /** The JOSE `typ` of every service access token; nothing else is accepted. */
        val TYPE = JOSEObjectType("commerce-service-access+jwt")

        /** The claim naming the authenticated principal's kind. Only `SERVICE` is issued or accepted. */
        const val PRINCIPAL_KIND = "principal_kind"
    }
}

/**
 * The service access token authentication mechanism: reads `Authorization: Bearer <token>`
 * and resolves it through [tokens] to the service's `ServiceId`, or `null`.
 *
 * A bearer credential in another format, such as a session token, is not a service access
 * token and yields `null`, so both can be accepted by one [authentication] filter.
 */
class ServiceAccessTokenAuthenticator(
    private val tokens: ServiceAccessTokens,
) : RequestAuthenticator {
    override fun authenticate(request: Request): PrincipalId? =
        request.bearerCredential()?.let(ServiceAccessToken::parse)?.let(tokens::resolve)
}

/** Authenticates each request through a service access token, or answers `401`: [authentication] with one [ServiceAccessTokenAuthenticator]. */
fun serviceAccessTokenAuthentication(tokens: ServiceAccessTokens): Filter = authentication(ServiceAccessTokenAuthenticator(tokens))
