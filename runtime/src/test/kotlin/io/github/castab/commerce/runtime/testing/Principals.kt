package io.github.castab.commerce.runtime.testing

import io.github.castab.commerce.runtime.persistence.AuthorizationRepository
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.ServiceIdentity
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId

/** Persist a known principal for session and HTTP tests whose application data is otherwise fake. */
internal fun Transactor.insertTestPrincipal(
    id: PrincipalId,
    status: PrincipalStatus = PrincipalStatus.ACTIVE,
) {
    val repository = AuthorizationRepository()
    inTransaction { transaction ->
        when (id) {
            is UserId -> {
                val username = "test-${id.value}"
                repository.insertUser(
                    transaction,
                    User(id, username, null, null, username, status, emptySet()),
                    username,
                )
            }
            is ServiceId -> repository.insertService(transaction, ServiceIdentity(id, "test-${id.value}", status, emptySet()))
        }
    }
}
