package io.github.castab.commerce.runtime.persistence

import io.github.castab.commerce.runtime.serviceauth.ServiceCredential
import io.github.castab.commerce.runtime.serviceauth.ServiceCredentialHash
import io.github.castab.commerce.runtime.serviceauth.ServiceCredentialId
import io.github.castab.commerce.staff.ServiceId
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Runtime-owned service credential storage in `commerce.service_credentials`, in
 * caller-owned transactions.
 *
 * Internal: applications use
 * [io.github.castab.commerce.runtime.serviceauth.ServiceCredentials], never this repository,
 * so no public API accepts or returns a stored hash. Timestamps always come from the
 * runtime's clock.
 */
internal class ServiceCredentialRepository {
    fun insert(
        transaction: Transaction,
        credential: ServiceCredential,
        hash: ServiceCredentialHash,
    ) {
        transaction.handle
            .createUpdate(
                """INSERT INTO commerce.service_credentials (credential_id, principal_id, label, secret_hash, created_at, revoked_at)
                   VALUES (:credentialId, :serviceId, :label, :secretHash, :createdAt, :revokedAt)""",
            ).bind("credentialId", credential.id.value)
            .bind("serviceId", credential.serviceId.value)
            .bind("label", credential.label)
            .bind("secretHash", hash.encoded())
            .bind("createdAt", credential.createdAt.timestamp())
            .bind("revokedAt", credential.revokedAt?.timestamp())
            .execute()
    }

    /** The credentials of [serviceId], oldest first. */
    fun list(
        transaction: Transaction,
        serviceId: ServiceId,
    ): List<ServiceCredential> =
        transaction.handle
            .createQuery(
                """SELECT credential_id, principal_id, label, created_at, revoked_at FROM commerce.service_credentials
                   WHERE principal_id = :serviceId ORDER BY created_at, credential_id""",
            ).bind("serviceId", serviceId.value)
            .map { rows, _ -> credential(rows) }
            .list()

    fun find(
        transaction: Transaction,
        id: ServiceCredentialId,
    ): ServiceCredential? =
        transaction.handle
            .createQuery(
                """SELECT credential_id, principal_id, label, created_at, revoked_at FROM commerce.service_credentials
                   WHERE credential_id = :id""",
            ).bind("id", id.value)
            .map { rows, _ -> credential(rows) }
            .findOne()
            .orElse(null)

    /** The credential and its stored hash, for authentication only. */
    fun findWithHash(
        transaction: Transaction,
        id: ServiceCredentialId,
    ): Pair<ServiceCredential, ServiceCredentialHash>? =
        transaction.handle
            .createQuery(
                """SELECT credential_id, principal_id, label, secret_hash, created_at, revoked_at FROM commerce.service_credentials
                   WHERE credential_id = :id""",
            ).bind("id", id.value)
            .map { rows, _ -> credential(rows) to ServiceCredentialHash.restore(rows.getString("secret_hash")) }
            .findOne()
            .orElse(null)

    /** Revokes [id] at [at] when it belongs to [serviceId] and is still active. True when it was revoked now. */
    fun revoke(
        transaction: Transaction,
        serviceId: ServiceId,
        id: ServiceCredentialId,
        at: Instant,
    ): Boolean =
        transaction.handle
            .createUpdate(
                """UPDATE commerce.service_credentials SET revoked_at = :at
                   WHERE credential_id = :id AND principal_id = :serviceId AND revoked_at IS NULL""",
            ).bind("id", id.value)
            .bind("serviceId", serviceId.value)
            .bind("at", at.timestamp())
            .execute() > 0

    private fun credential(rows: ResultSet): ServiceCredential =
        ServiceCredential(
            ServiceCredentialId(rows.getObject("credential_id", UUID::class.java)),
            ServiceId(rows.getObject("principal_id", UUID::class.java)),
            rows.getString("label"),
            rows.instant("created_at")!!,
            rows.instant("revoked_at"),
        )

    private fun Instant.timestamp(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
}
