package io.github.castab.commerce.runtime.persistence

import com.zaxxer.hikari.HikariDataSource
import io.github.castab.commerce.customer.Customer
import io.github.castab.commerce.customer.CustomerName
import io.github.castab.commerce.customer.EmailAddress
import io.github.castab.commerce.runtime.customer.CustomerRepository
import io.github.castab.commerce.runtime.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jdbi.v3.core.Jdbi
import java.util.UUID

/** Connections and the transaction boundary, against a real PostgreSQL. Migrations: [MigrationLifecycleSpec]. */
class PersistenceSpec :
    FunSpec({
        lateinit var database: TestDatabase
        lateinit var dataSource: HikariDataSource
        lateinit var jdbi: Jdbi

        beforeSpec {
            database = TestDatabase.create()
            dataSource = createDataSource(database.configuration, poolName = "persistence-spec")
            MigrationLifecycle(dataSource, applicationLocations = listOf("classpath:db/testapp")).migrate()
            jdbi = Jdbi.create(dataSource)
        }

        afterSpec {
            dataSource.close()
            database.close()
        }

        fun count(table: String): Int =
            jdbi.withHandle<Int, Exception> {
                it.createQuery("SELECT count(*) FROM $table").mapTo(Int::class.java).one()
            }

        fun customer() = Customer(Customer.Id(UUID.randomUUID()), CustomerName("Grace Hopper"), EmailAddress("grace@example.com"))

        context("connections") {
            test("the database is reachable for readiness checks") {
                dataSource.isReachable() shouldBe true
            }
        }

        context("transactions") {
            val transactor = Transactor(jdbi)
            val customers = CustomerRepository()

            fun insertNote(
                transaction: Transaction,
                customer: Customer,
            ) {
                transaction.handle
                    .createUpdate("INSERT INTO public.test_application_customer_notes (customer_id, note) VALUES (:id, 'vip')")
                    .bind("id", customer.id.value)
                    .execute()
            }

            test("writes to commerce and application tables commit together") {
                val customer = customer()
                val before = count("public.test_application_customer_notes")

                transactor.inTransaction { transaction ->
                    customers.insert(transaction, customer)
                    insertNote(transaction, customer)
                }

                transactor.inTransaction { customers.find(it, customer.id) } shouldBe customer
                count("public.test_application_customer_notes") shouldBe before + 1
            }

            test("a failure rolls back every write in the transaction and rethrows the original exception") {
                val customer = customer()
                val customersBefore = count("commerce.customers")
                val notesBefore = count("public.test_application_customer_notes")

                shouldThrow<IllegalStateException> {
                    transactor.inTransaction { transaction ->
                        customers.insert(transaction, customer)
                        insertNote(transaction, customer)
                        throw IllegalStateException("booking policy rejected the transition")
                    }
                }.message shouldBe "booking policy rejected the transition"

                count("commerce.customers") shouldBe customersBefore
                count("public.test_application_customer_notes") shouldBe notesBefore
            }

            test("the block's result is returned") {
                transactor.inTransaction { 42 } shouldBe 42
            }
        }
    })
