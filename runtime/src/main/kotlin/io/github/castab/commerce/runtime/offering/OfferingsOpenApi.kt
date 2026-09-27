package io.github.castab.commerce.runtime.offering

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
        // http4k needs values in both optional fields to discover them. This copy is
        // only a schema input; the route's original object remains the HTTP example.
        val schema = delegate.toSchema(obj.withCompletePriceShape(), overrideDefinitionId, refModelNamePrefix)
        if ("OfferingPriceDto" !in schema.definitions) return schema
        return schema.copy(
            definitions =
                schema.definitions.mapValues { (name, node) ->
                    if (name in priceCarrierNames) {
                        node.withoutSchemaOnlyExamples()
                    } else {
                        node
                    }
                } +
                    mapOf(
                        "OfferingPriceDto" to priceUnion(),
                        "FixedOfferingPrice" to priceVariant("FIXED"),
                        "PerQuantityOfferingPrice" to priceVariant("PER_QUANTITY", "dimension"),
                        "PerDurationOfferingPrice" to priceVariant("PER_DURATION", "interval"),
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

    private fun priceVariant(
        kind: String,
        variantField: String? = null,
    ): NODE {
        val field = variantField?.let { ",\"$it\":{\"type\":\"string\"}" }.orEmpty()
        val required = variantField?.let { ",\"$it\"" }.orEmpty()
        val amount =
            if (kind == "FIXED") {
                "120.00"
            } else if (kind == "PER_QUANTITY") {
                "0.75"
            } else {
                "50.00"
            }
        val exampleField =
            when (variantField) {
                "dimension" -> ",\"dimension\":\"guest\""
                "interval" -> ",\"interval\":\"PT1H\""
                else -> ""
            }
        return json.parse(
            """{"type":"object","additionalProperties":false,
              "properties":{"kind":{"type":"string","enum":["$kind"]},
                "amount":{"type":"string"},"currency":{"type":"string"}$field},
              "required":["kind","amount","currency"$required],
              "example":{"kind":"$kind","amount":"$amount","currency":"USD"$exampleField}}""",
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
}

private val priceCarrierNames =
    setOf("OfferingDto", "OfferingResultDto", "OfferingsDto", "CategoryOfferingsDto", "CatalogCategoryDto", "OfferingsCatalogDto")

private fun Any.withCompletePriceShape(): Any {
    fun OfferingPriceDto.complete() = copy(dimension = "guest", interval = "PT1H")

    fun OfferingDto.complete() = copy(price = price?.complete())
    return when (this) {
        is OfferingPriceDto -> complete()
        is OfferingDto -> complete()
        is OfferingResultDto -> copy(offering = offering.complete())
        is OfferingsDto -> copy(offerings = offerings.map { it.complete() })
        is CategoryOfferingsDto -> copy(offerings = offerings.map { it.complete() })
        is OfferingsCatalogDto ->
            copy(categories = categories.map { category -> category.copy(offerings = category.offerings.map { it.complete() }) })
        else -> this
    }
}
