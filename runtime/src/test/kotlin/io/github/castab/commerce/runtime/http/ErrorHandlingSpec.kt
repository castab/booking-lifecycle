package io.github.castab.commerce.runtime.http

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.Serializable
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.lens.Path
import org.http4k.lens.uuid
import org.http4k.routing.bind
import org.http4k.routing.routes

@Serializable
private data class Probe(
    val value: String,
)

private fun Response.error(): ErrorResponse = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

private fun failing(failure: Exception): HttpHandler = CommerceErrorHandling.then { throw failure }

class ErrorHandlingSpec :
    FunSpec({
        context("each operation failure maps to one status and code, keeping its message") {
            withTests(
                nameFn = { (failure, _, _) -> failure::class.simpleName!! },
                Triple(
                    CommerceFailure.ValidationFailed("A financial document must contain at least one line item"),
                    Status.UNPROCESSABLE_ENTITY,
                    "validation_failed",
                ),
                Triple(CommerceFailure.NotFound("Financial document 1 was not found"), Status.NOT_FOUND, "not_found"),
                Triple(CommerceFailure.Conflict("Financial document 1 v2 already exists"), Status.CONFLICT, "conflict"),
                Triple(CommerceFailure.IllegalTransition("A quote cannot be completed"), Status.CONFLICT, "illegal_transition"),
                Triple(
                    CommerceFailure.InvariantViolated("Allocation exceeds the payment"),
                    Status.UNPROCESSABLE_ENTITY,
                    "invariant_violated",
                ),
            ) { (failure, status, code) ->
                val response = failing(failure)(Request(Method.POST, "/anything"))

                response.status shouldBe status
                response.error() shouldBe ErrorResponse(code, failure.message!!)
            }
        }

        test("an unexpected exception is a generic internal failure that leaks nothing") {
            val response =
                failing(IllegalStateException("ERROR: relation \"commerce.secret\" does not exist; SELECT password FROM users"))(
                    Request(Method.GET, "/anything"),
                )

            response.status shouldBe Status.INTERNAL_SERVER_ERROR
            response.error() shouldBe ErrorResponse("internal_failure", "The request could not be completed")
            response.bodyString() shouldNotContain "SELECT"
            response.bodyString() shouldNotContain "commerce.secret"
        }

        context("unreadable input is a malformed request") {
            val probe = jsonBody(Probe.serializer())
            val id = Path.uuid().of("id")
            val app =
                CommerceErrorHandling.then(
                    routes(
                        "/probe" bind Method.POST to { request -> Response(Status.OK).body(probe(request).value) },
                        "/probe/{id}" bind Method.GET to { request -> Response(Status.OK).body(id(request).toString()) },
                    ),
                )

            withTests(
                nameFn = { it.first },
                "invalid JSON" to Request(Method.POST, "/probe").body("{not json"),
                "a missing required field" to Request(Method.POST, "/probe").body("{}"),
                "an empty body" to Request(Method.POST, "/probe"),
                "an unparsable path identifier" to Request(Method.GET, "/probe/not-a-uuid"),
            ) { (_, request) ->
                val response = app(request)

                response.status shouldBe Status.BAD_REQUEST
                response.error().code shouldBe "malformed_request"
            }

            test("a well-formed request with unknown fields is accepted") {
                app(Request(Method.POST, "/probe").body("""{"value":"ok","extra":1}""")).bodyString() shouldBe "ok"
            }
        }

        test("an unmatched route answers with the same not_found shape") {
            val response =
                CommerceErrorHandling.then(routes("/known" bind Method.GET to { Response(Status.OK) }))(Request(Method.GET, "/unknown"))

            response.status shouldBe Status.NOT_FOUND
            response.error() shouldBe ErrorResponse("not_found", "No resource at /unknown")
        }

        test("validating reports a domain require failure as a validation failure with the domain's message") {
            shouldThrow<CommerceFailure.ValidationFailed> {
                validating { require(false) { "Line item quantity must be positive" } }
            }.message shouldBe "Line item quantity must be positive"
        }
    })
