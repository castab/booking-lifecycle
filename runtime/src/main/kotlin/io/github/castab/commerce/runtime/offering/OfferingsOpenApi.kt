package io.github.castab.commerce.runtime.offering

import io.github.castab.commerce.runtime.http.ValidationErrorResponse
import io.github.castab.commerce.runtime.http.ValidationViolationResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.http4k.contract.jsonschema.JsonSchema
import org.http4k.contract.jsonschema.JsonSchemaCreator
import org.http4k.contract.jsonschema.v3.AutoJsonToJsonSchema
import org.http4k.contract.openapi.ApiRenderer
import org.http4k.contract.openapi.v3.Api
import org.http4k.format.AutoMarshallingJson

/**
 * An http4k OpenAPI renderer for host contracts containing offerings routes. All schemas
 * except [OfferingPriceDto] use http4k's usual auto schema generator.
 */
fun <NODE : Any> offeringsOpenApiRenderer(json: AutoMarshallingJson<NODE>): ApiRenderer<Api<NODE>, NODE> =
    ApiRenderer.Auto<Api<NODE>, NODE>(json, OfferingPriceSchemaCreator(json))

private class OfferingPriceSchemaCreator<NODE : Any>(
    private val json: AutoMarshallingJson<NODE>,
) : JsonSchemaCreator<Any, NODE> {
    private val delegate = AutoJsonToJsonSchema(json)

    override fun toSchema(
        obj: Any,
        overrideDefinitionId: String?,
        refModelNamePrefix: String?,
    ): JsonSchema<NODE> {
        // http4k discovers schemas from example values, so both optional price fields, a
        // null predecessor, and an empty catalog are filled in. This copy is only a
        // schema input; the route's original object remains the HTTP example.
        val schema = delegate.toSchema(obj.withCompletePriceShape(), overrideDefinitionId, refModelNamePrefix)
        if ("OfferingPriceDto" !in schema.definitions) {
            return schema.copy(
                node = schema.node.withoutNullSchemaFormats(),
                definitions = schema.definitions.mapValues { (_, node) -> node.withoutNullSchemaFormats() },
            )
        }
        return schema.copy(
            node = schema.node.withoutNullSchemaFormats(),
            definitions =
                schema.definitions.mapValues { (name, node) ->
                    if (name in priceCarrierNames) {
                        node.withoutSchemaOnlyExamples().withoutNullSchemaFormats()
                    } else {
                        node.withoutNullSchemaFormats()
                    }
                } +
                    mapOf(
                        "OfferingPriceDto" to priceUnion(),
                        "FixedOfferingPrice" to
                            priceVariant("FIXED", null, """{"kind":"FIXED","amount":"120.00","currency":"USD"}"""),
                        "PerQuantityOfferingPrice" to
                            priceVariant(
                                "PER_QUANTITY",
                                "dimension",
                                """{"kind":"PER_QUANTITY","amount":"0.75","currency":"USD","dimension":"guest"}""",
                            ),
                        "PerDurationOfferingPrice" to
                            priceVariant(
                                "PER_DURATION",
                                "interval",
                                """{"kind":"PER_DURATION","amount":"50.00","currency":"USD","interval":"PT1H"}""",
                            ),
                    ),
        )
    }

    private fun priceUnion(): NODE =
        json.parse(
            """{
              "oneOf": [
                {"${'$'}ref":"#/components/schemas/FixedOfferingPrice"},
                {"${'$'}ref":"#/components/schemas/PerQuantityOfferingPrice"},
                {"${'$'}ref":"#/components/schemas/PerDurationOfferingPrice"}
              ],
              "example":{"kind":"PER_QUANTITY","amount":"0.75","currency":"USD","dimension":"guest"},
              "discriminator": {
                "propertyName":"kind",
                "mapping": {
                  "FIXED":"#/components/schemas/FixedOfferingPrice",
                  "PER_QUANTITY":"#/components/schemas/PerQuantityOfferingPrice",
                  "PER_DURATION":"#/components/schemas/PerDurationOfferingPrice"
                }
              }
            }""",
        )

    /**
     * One price branch. Unknown additive fields stay valid, matching `CommerceJson`'s
     * `ignoreUnknownKeys`; only the fields of the other variants are forbidden.
     */
    private fun priceVariant(
        kind: String,
        variantField: String?,
        example: String,
    ): NODE {
        val variantFields = listOfNotNull(variantField)
        val properties = (listOf("amount", "currency") + variantFields).joinToString(",") { "\"$it\":{\"type\":\"string\"}" }
        val required = (listOf("kind", "amount", "currency") + variantFields).joinToString(",") { "\"$it\"" }
        val forbidden = (listOf("dimension", "interval") - variantFields).map { """{"required":["$it"]}""" }
        val not = forbidden.singleOrNull() ?: forbidden.joinToString(",", """{"anyOf":[""", "]}")
        return json.parse(
            """{"type":"object",
              "properties":{"kind":{"type":"string","enum":["$kind"]},$properties},
              "required":[$required],
              "not":$not,
              "example":$example}""",
        )
    }

    private fun NODE.withoutSchemaOnlyExamples(): NODE {
        fun strip(element: JsonElement): JsonElement =
            when (element) {
                is JsonObject -> JsonObject(element.filterKeys { it != "example" }.mapValues { strip(it.value) })
                is JsonArray -> JsonArray(element.map(::strip))
                else -> element
            }
        return json.parse(strip(Json.parseToJsonElement(json.compact(this))).toString())
    }

    /** Only schema positions are traversed; example, default, const, and extension data stay intact. */
    private fun NODE.withoutNullSchemaFormats(): NODE {
        fun cleanSchema(element: JsonElement): JsonElement {
            if (element !is JsonObject) return element

            fun schemaMap(value: JsonElement): JsonElement =
                if (value is JsonObject) JsonObject(value.mapValues { cleanSchema(it.value) }) else value

            fun schemaArray(value: JsonElement): JsonElement = if (value is JsonArray) JsonArray(value.map(::cleanSchema)) else value

            return JsonObject(
                element
                    .filter { (key, value) -> key != "format" || value != JsonNull }
                    .mapValues { (key, value) ->
                        when (key) {
                            "properties", "patternProperties", "definitions", "\$defs", "dependentSchemas" -> schemaMap(value)
                            "allOf", "anyOf", "oneOf", "prefixItems" -> schemaArray(value)
                            "items" -> if (value is JsonArray) schemaArray(value) else cleanSchema(value)
                            "additionalProperties", "unevaluatedProperties", "contains", "not", "if", "then", "else", "propertyNames" ->
                                cleanSchema(value)
                            else -> value
                        }
                    },
            )
        }
        return json.parse(cleanSchema(Json.parseToJsonElement(json.compact(this))).toString())
    }
}

private val priceCarrierNames =
    setOf(
        "OfferingDto",
        "OfferingResultDto",
        "OfferingsDto",
        "CategoryOfferingsDto",
        "CatalogCategoryDto",
        "OfferingsCatalogDto",
        "OfferingMutationDto",
        "RetiredOfferingDto",
        "RetiredOfferingsDto",
    )

private fun Any.withCompletePriceShape(): Any {
    fun OfferingPriceDto.complete() = copy(dimension = "guest", interval = "PT1H")

    fun OfferingDto.complete() = copy(price = price?.complete())
    return when (this) {
        is ValidationErrorResponse -> copy(violations = violations ?: listOf(ValidationViolationResponse("VALIDATION_ERROR")))
        is OfferingPriceDto -> complete()
        is OfferingDto -> complete()
        is OfferingMutationDto -> copy(price = price?.complete())
        is RetiredOfferingsDto -> copy(offerings = offerings.map { it.copy(offering = it.offering.complete()) })
        is OfferingResultDto -> copy(offering = offering.complete())
        is OfferingsDto -> copy(offerings = offerings.map { it.complete() })
        is CategoryOfferingsDto -> copy(offerings = offerings.map { it.complete() })
        is OfferingsCatalogDto ->
            copy(
                previousRevision = previousRevision ?: revision,
                categories =
                    categories.ifEmpty { listOf(schemaOnlyCategory) }.map { category ->
                        category.copy(offerings = category.offerings.map { it.complete() })
                    },
            )
        else -> this
    }
}

/** Discovers the catalog's nested shape when a route's example is an empty catalog. */
private val schemaOnlyCategory =
    CatalogCategoryDto(
        "category",
        "Category",
        "Description",
        0,
        1,
        listOf(OfferingDto("offering", "category", "Offering", "Description", OfferingPriceDto("FIXED", "1.00", "USD"))),
    )
