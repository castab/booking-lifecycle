package io.github.castab.commerce.runtime.serviceauth

import kotlinx.serialization.Serializable

/** A service's request for an access token: its id and one of its credential secrets. */
@Serializable
data class ServiceAccessTokenRequestDto(
    val serviceId: String,
    val secret: String,
) {
    override fun toString(): String = "ServiceAccessTokenRequestDto(serviceId=$serviceId, secret=****)"
}

/** An issued service access token, to send as `Authorization: Bearer <accessToken>`. */
@Serializable
data class ServiceAccessTokenDto(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: String,
    val expiresIn: Long,
) {
    override fun toString(): String =
        "ServiceAccessTokenDto(accessToken=****, tokenType=$tokenType, expiresAt=$expiresAt, expiresIn=$expiresIn)"
}

/** Non-sensitive metadata of one service credential. It never includes the secret or its hash. */
@Serializable
data class ServiceCredentialDto(
    val credentialId: String,
    val serviceId: String,
    val label: String,
    val createdAt: String,
    val revoked: Boolean,
    val revokedAt: String? = null,
)

@Serializable
data class ServiceCredentialsDto(
    val credentials: List<ServiceCredentialDto>,
)

@Serializable
data class ServiceCredentialWriteDto(
    val label: String,
)

/**
 * A newly created credential with its [secret], returned exactly once. The secret cannot be
 * read again; store it in the consuming service's secret store.
 */
@Serializable
data class IssuedServiceCredentialDto(
    val credentialId: String,
    val serviceId: String,
    val label: String,
    val createdAt: String,
    val secret: String,
) {
    override fun toString(): String =
        "IssuedServiceCredentialDto(credentialId=$credentialId, serviceId=$serviceId, label=$label, createdAt=$createdAt, secret=****)"
}

internal fun ServiceCredential.dto() =
    ServiceCredentialDto(id.value.toString(), serviceId.value.toString(), label, createdAt.toString(), !active, revokedAt?.toString())

internal fun IssuedServiceCredential.dto() =
    IssuedServiceCredentialDto(
        credential.id.value.toString(),
        credential.serviceId.value.toString(),
        credential.label,
        credential.createdAt.toString(),
        secret.value,
    )
