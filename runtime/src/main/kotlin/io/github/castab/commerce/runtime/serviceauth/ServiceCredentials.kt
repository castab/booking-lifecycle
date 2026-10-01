package io.github.castab.commerce.runtime.serviceauth

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.runtime.persistence.AuthorizationRepository
import io.github.castab.commerce.runtime.persistence.ServiceCredentialRepository
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.ServiceId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.SecureRandom
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Administration of the long-lived credentials of SERVICE principals, available to
 * applications as `CommerceRuntimeContext.serviceCredentials`.
 *
 * Credentials exist only for service identities in the runtime directory; a human user can
 * never hold one (the API takes a `ServiceId`, and the schema references
 * `commerce.service_identities`). A service may hold several active credentials at once:
 *
 * 1. create credential B and deliver its secret to the consumer;
 * 2. deploy the consumer with credential B and verify it;
 * 3. revoke credential A.
 *
 * A credential is used only to obtain a short-lived service access token
 * (`ServiceAccessTokens.issue`). Revoking a credential stops it from obtaining new tokens
 * immediately; a token it already obtained stays valid until that token expires. Disabling
 * the service stops every token and credential of it immediately, and leaves the credentials
 * in place for when the service is activated again.
 *
 * Methods without a [Transaction] run in their own transaction. The overloads that take one
 * join the caller's transaction, so a credential change commits or rolls back together with
 * the application's own writes.
 */
interface ServiceCredentials {
    /**
     * Creates an active credential for [serviceId], which must exist (it may be disabled).
     * The returned secret is the only copy; only its Argon2id hash is stored.
     */
    fun create(
        serviceId: ServiceId,
        label: String,
    ): IssuedServiceCredential

    /** [create] inside the caller's [transaction]. */
    fun create(
        transaction: Transaction,
        serviceId: ServiceId,
        label: String,
    ): IssuedServiceCredential

    /** Every credential of [serviceId], active and revoked, oldest first. Never includes secrets or hashes. */
    fun list(serviceId: ServiceId): List<ServiceCredential>

    /** [list] inside the caller's [transaction]. */
    fun list(
        transaction: Transaction,
        serviceId: ServiceId,
    ): List<ServiceCredential>

    /**
     * Revokes credential [credentialId] of [serviceId] permanently and returns it. Idempotent:
     * revoking a revoked credential returns it unchanged. A credential of another service is
     * not found.
     */
    fun revoke(
        serviceId: ServiceId,
        credentialId: ServiceCredentialId,
    ): ServiceCredential

    /** [revoke] inside the caller's [transaction]. */
    fun revoke(
        transaction: Transaction,
        serviceId: ServiceId,
        credentialId: ServiceCredentialId,
    ): ServiceCredential
}

/**
 * The runtime's [ServiceCredentials] over PostgreSQL. [clock] supplies creation and
 * revocation times, truncated to microseconds, the precision PostgreSQL stores.
 */
internal class PersistentServiceCredentials(
    private val transactor: Transactor,
    private val repository: ServiceCredentialRepository,
    private val principals: AuthorizationRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) : ServiceCredentials {
    /**
     * Checked when the presented credential does not exist, so that authentication performs
     * the same Argon2id work whatever the outcome.
     */
    private val absentCredentialHash =
        ServiceCredentialHash.of(ServiceCredentialSecret.generate(ServiceCredentialId(UUID(0, 0)), random), random)

    override fun create(
        serviceId: ServiceId,
        label: String,
    ): IssuedServiceCredential = transactor.inTransaction { create(it, serviceId, label) }

    override fun create(
        transaction: Transaction,
        serviceId: ServiceId,
        label: String,
    ): IssuedServiceCredential {
        val id = ServiceCredentialId(UUID.randomUUID())
        val credential = validating { ServiceCredential(id, serviceId, label.trim(), now(), revokedAt = null) }
        principals.service(transaction, serviceId) ?: throw CommerceFailure.NotFound("Service does not exist")
        val secret = ServiceCredentialSecret.generate(id, random)
        repository.insert(transaction, credential, ServiceCredentialHash.of(secret, random))
        logger.info { "event=service_credential_created service=${serviceId.value} credential=${id.value}" }
        return IssuedServiceCredential(credential, secret)
    }

    override fun list(serviceId: ServiceId): List<ServiceCredential> = transactor.inTransaction { list(it, serviceId) }

    override fun list(
        transaction: Transaction,
        serviceId: ServiceId,
    ): List<ServiceCredential> {
        principals.service(transaction, serviceId) ?: throw CommerceFailure.NotFound("Service does not exist")
        return repository.list(transaction, serviceId)
    }

    override fun revoke(
        serviceId: ServiceId,
        credentialId: ServiceCredentialId,
    ): ServiceCredential = transactor.inTransaction { revoke(it, serviceId, credentialId) }

    override fun revoke(
        transaction: Transaction,
        serviceId: ServiceId,
        credentialId: ServiceCredentialId,
    ): ServiceCredential {
        val existing = repository.find(transaction, credentialId)
        if (existing == null || existing.serviceId != serviceId) throw CommerceFailure.NotFound("Service credential does not exist")
        if (repository.revoke(transaction, serviceId, credentialId, now())) {
            logger.info { "event=service_credential_revoked service=${serviceId.value} credential=${credentialId.value}" }
        }
        return repository.find(transaction, credentialId)!!
    }

    /**
     * The credential that authenticates [serviceId] with [secret], or `null`.
     *
     * Succeeds only when the credential exists, belongs to [serviceId], is not revoked, its
     * hash matches, and the service exists and is ACTIVE. Exactly one Argon2id verification
     * runs for every attempt, outside the database transaction. Callers receive no reason;
     * the log records it, never the secret.
     */
    fun authenticate(
        serviceId: ServiceId,
        secret: ServiceCredentialSecret,
    ): ServiceCredentialId? {
        val (stored, status) =
            transactor.inTransaction { transaction ->
                repository.findWithHash(transaction, secret.credentialId) to principals.principalStatus(transaction, serviceId)
            }
        val matches = (stored?.second ?: absentCredentialHash).matches(secret)
        val credential = stored?.first
        val failure =
            when {
                credential == null -> "unknown_credential"
                credential.serviceId != serviceId -> "credential_of_another_service"
                !matches -> "secret_mismatch"
                !credential.active -> "credential_revoked"
                status == null -> "unknown_service"
                status != PrincipalStatus.ACTIVE -> "service_disabled"
                else -> null
            }
        if (failure != null) {
            logger.info {
                "event=service_authentication_failed service=${serviceId.value} credential=${secret.credentialId.value} reason=$failure"
            }
            return null
        }
        return credential!!.id
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)
}
