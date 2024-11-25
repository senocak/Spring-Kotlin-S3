package com.github.senocak.s3

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.web.client.RestTemplate
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.MediaType

@Bean
fun customOpenAPI(
    @Value("\${spring.application.name}") title: String,
    @Value("\${server.port}") port: String,
    @Value("\${springdoc.version}") appVersion: String
): OpenAPI {
    val securitySchemesItem: SecurityScheme = SecurityScheme()
        .name("bearerAuth")
        .description("JWT auth description")
        .type(SecurityScheme.Type.HTTP)
        .scheme("bearer")
        .`in`(SecurityScheme.In.HEADER)
        .bearerFormat("JWT")
    val license: Info = Info().title(title).version(appVersion)
        .description(title)
        .termsOfService("https://github.com/senocak")
        .license(License().name("Apache 2.0").url("https://springdoc.org"))
    val server1: Server = Server().url("http://localhost:$port/").description("Local Server")
    return OpenAPI()
        .components(Components().addSecuritySchemes("bearerAuth", securitySchemesItem))
        .info(license)
        .servers(listOf(server1))
}

@Bean
fun s3Api(): GroupedOpenApi =
    GroupedOpenApi.builder().displayName("S3 operations").group("s3").pathsToMatch("/s3/**").build()

@Operation(
    summary = "Default Summary",
    description = "Default Description",
    tags = ["Default"],
    //security = [SecurityRequirement(name = "bearerAuth")],
    responses = [
        ApiResponse(responseCode = "400", description = "Bad request", content = [
            Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ExceptionDto::class))
        ]),
        ApiResponse(responseCode = "403", description = "Forbidden", content = [
            Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ExceptionDto::class))
        ]),
        ApiResponse(responseCode = "500", description = "internal server error occurred", content = [
            Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ExceptionDto::class))
        ])
    ]
)
annotation class CustomOperation(
    val summary: String = "",
    val description: String = "",
    val tags: Array<String> = [],
    //val security: Array<SecurityRequirement> = [SecurityRequirement(name = "bearerAuth")],
    val requestBody: RequestBody = RequestBody(),
    val parameters: Array<Parameter> = [
        Parameter(name = "token", description = "Token", `in` = ParameterIn.PATH, required = true, example = "ey..."),
    ]
)


@Bean
fun restTemplate(): RestTemplate = RestTemplate()
