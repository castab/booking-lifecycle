package io.github.castab.commerce.runtime.testing

import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import java.util.Base64

/** Deterministic test signing material: 32 distinct bytes. Never used outside tests. */
val TEST_SERVICE_TOKEN_KEY_BYTES: ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }

/** Service token configuration for tests, signed with [TEST_SERVICE_TOKEN_KEY_BYTES]. */
fun testServiceTokens(
    lifetimeMinutes: Long = 15,
    issuer: String = "commerce-runtime-test",
) = CommerceRuntimeConfiguration.ServiceTokens(Base64.getEncoder().encodeToString(TEST_SERVICE_TOKEN_KEY_BYTES), lifetimeMinutes, issuer)
