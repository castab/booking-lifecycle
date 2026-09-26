package io.github.castab.commerce.runtime.customer

import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.customer.Customer
import io.github.castab.commerce.customer.CustomerName
import io.github.castab.commerce.customer.EmailAddress
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.MigrationLifecycle
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.persistence.createDataSource
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jdbi.v3.core.Jdbi
import java.util.UUID

class CustomerRepositorySpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var dataSource: HikariDataSource
        lateinit var transactor: Transactor
        val customers = CustomerRepository()

        beforeSpec {
            database = TestDatabase.create()
            dataSource = createDataSource(database.configuration, poolName = "customer-repository-spec")
            MigrationLifecycle(dataSource, applicationLocations = emptyList()).migrate()
            transactor = Transactor(Jdbi.create(dataSource))
        }

        afterSpec {
            dataSource.close()
            database.close()
        }

        fun customer() = Customer(Customer.Id(UUID.randomUUID()), CustomerName("Ada Lovelace"), EmailAddress("ada@example.com"))

        test("an inserted customer reads back as the same domain value") {
            val customer = customer()

            transactor.inTransaction { customers.insert(it, customer) }

            transactor.inTransaction { customers.find(it, customer.id) } shouldBe customer
        }

        test("an unknown id reads as absent") {
            transactor.inTransaction { customers.find(it, Customer.Id(UUID.randomUUID())) }.shouldBeNull()
        }

        test("a second customer with the same id is a conflict that exposes no SQL") {
            val customer = customer()
            transactor.inTransaction { customers.insert(it, customer) }

            val conflict =
                shouldThrow<CommerceFailure.Conflict> {
                    transactor.inTransaction { customers.insert(it, customer) }
                }

            conflict.message shouldBe "Customer ${customer.id.value} already exists"
            conflict.message shouldNotContain "duplicate key"
        }
    })
