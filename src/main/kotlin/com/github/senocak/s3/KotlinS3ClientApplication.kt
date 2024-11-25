package com.github.senocak.s3

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpRequest
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.BufferingClientHttpRequestFactory
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.function.Function
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import io.swagger.v3.oas.annotations.media.Content as ContentSwagger

@SpringBootApplication
@RestController
@RequestMapping("/s3")
class KotlinS3ClientApplication(
    @Value("\${s3.endpoint}") private val endpoint: String,
    @Value("\${s3.region}") private val region: String,
    @Value("\${s3.accessKey}") private val accessKey: String,
    @Value("\${s3.secretKey}") private val secretKey: String,
    @Value("\${s3.trace}") private val trace: Boolean,
) {
    private final val log: Logger = LoggerFactory.getLogger(javaClass.name)
    private final val restTemplate: RestTemplate = RestTemplate(BufferingClientHttpRequestFactory(SimpleClientHttpRequestFactory()))

    init {
        if (trace)
            restTemplate.interceptors = arrayListOf<ClientHttpRequestInterceptor>(LoggingRequestInterceptor(log = log))
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate>? = null
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
        })
        try {
            val sc: SSLContext = SSLContext.getInstance("TLS")
            sc.init(null, trustAllCerts, SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
        } catch (e: Exception) {
            log.error("Exception while setting up, disabling, SSL certificate: ${e.message}")
        }
    }

    @Deprecated(message = "Use S3RequestBuilder", level = DeprecationLevel.WARNING)
    private fun s3Request(method: HttpMethod, path: Function<S3PathBuilder, S3PathBuilder>): S3Request =
        S3Request(endpoint = URI.create(endpoint), region = region, accessKeyId = accessKey,
            secretAccessKey = secretKey, method = method, path = path)

    private fun s3RequestBuilder(method: HttpMethod, path: Function<S3PathBuilder, S3PathBuilder>) =
        S3RequestBuilder()
            .endpoint(endpoint = URI.create(endpoint))
            .region(region = region)
            .accessKeyId(accessKeyId = accessKey)
            .secretAccessKey(secretAccessKey = secretKey)
            .method(method = method)
            .path(path = path)

    @GetMapping("settings")
    @CustomOperation(summary = "App Version", tags = ["S3"])
    @ApiResponse(responseCode = "200", description = "App Version", content = [ContentSwagger(
        mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = Map::class), examples = [
            ExampleObject("""{
                "s3Request": "lorem",
                "headers": "lorem"
            }""")
        ])])
    fun getSettings(): Map<String, Any?> =
        s3Request(method = HttpMethod.GET, path = { b -> b })
            .run {
                mapOf(
                    "s3Request" to this.toString(),
                    "headers" to this.headers().toSingleValueMap(),
                )
            }

    @Bean
    fun s3Client(): S3Client {
        val credentials: AwsCredentials = AwsBasicCredentials.create(accessKey, secretKey)
        return S3Client.builder()
            .overrideConfiguration(ClientOverrideConfiguration.builder().build())
            .endpointOverride(URI(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .serviceConfiguration(S3Configuration.Builder::pathStyleAccessEnabled)
            .region(Region.of(region)) // this is not used, but the AWS SDK requires it
            .forcePathStyle(true)
            .build()
    }

    @GetMapping("buckets")
    @CustomOperation(summary = "Get All Buckets", tags = ["S3"],
        parameters = [
            Parameter(name = "legacy", description = "Get Buckets via Rest or Library", example = "true", `in` = ParameterIn.QUERY,
                required = false, schema = Schema(type = "string", allowableValues = ["true", "false"])),
        ]
    )
    @ApiResponse(responseCode = "200", description = "List of Buckets", content = [ContentSwagger(
        mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ListBucketsResult::class))])
    fun listBuckets(@RequestParam legacy: Boolean = false): ResponseEntity<ListBucketsResult> {
        if (!legacy) {
            val listBuckets = s3Client().listBuckets()
            return ResponseEntity.ok(ListBucketsResult(owner = Owner(id = listBuckets.owner().id(), displayName = listBuckets.owner().displayName()),
                buckets = listBuckets.buckets().map { Bucket(name = it.name(), creationDate = it.creationDate().atOffset(ZoneOffset.UTC)) }))
        }
        val s3Request = s3Request(method = HttpMethod.GET, path = { b -> b })
            .toEntityBuilder()
            .build()
        log.info("listBuckets: $s3Request")
        val request: RequestEntity<Void> = S3RequestBuilder()
            .endpoint(URI.create(endpoint))
            .region(region)
            .accessKeyId(accessKey)
            .secretAccessKey(secretKey)
            .method(HttpMethod.GET)
            .path { b -> b }
            .build()
            .toEntityBuilder()
            .build()
        val exchange = restTemplate.exchange(request, ListBucketsResult::class.java)
        return ResponseEntity.status(exchange.statusCode).body(exchange.body)
    }

    @PutMapping("buckets/{bucket}")
    @CustomOperation(summary = "Create New Bucket", tags = ["S3"],
        parameters = [
            Parameter(name = "bucket", description = "Bucket name", example = "lorem", `in` = ParameterIn.PATH, required = true),
            Parameter(name = "legacy", description = "Get Buckets via Rest or Library", example = "true", `in` = ParameterIn.QUERY,
                required = false, schema = Schema(type = "string", allowableValues = ["true", "false"])),
        ]
    )
    @ApiResponse(responseCode = "200", description = "Bucket name", content = [ContentSwagger(
        mediaType = MediaType.TEXT_PLAIN_VALUE, schema = Schema(implementation = String::class))])
    fun putBucket(@PathVariable bucket: String, @RequestParam legacy: Boolean = false): ResponseEntity<String> {
        if (!legacy) {
            val createBucket = s3Client().createBucket(CreateBucketRequest.builder().bucket(bucket).build())
            return ResponseEntity.ok(createBucket.location())
        }
        val request: RequestEntity<Void> = s3RequestBuilder(method = HttpMethod.PUT, path = { b -> b.bucket(bucket) })
            .build().toEntityBuilder().build()
        log.info("putBucket: $request")
        val exchange = restTemplate.exchange(request, String::class.java)
        return ResponseEntity.status(exchange.statusCode).body("/$bucket")
    }

    @DeleteMapping("buckets/{bucket}")
    @CustomOperation(summary = "Delete Single Buckets", tags = ["S3"],
        parameters = [
            Parameter(name = "bucket", description = "Bucket name", example = "lorem", `in` = ParameterIn.PATH, required = true),
            Parameter(name = "legacy", description = "Get Buckets via Rest or Library", example = "true", `in` = ParameterIn.QUERY,
                required = false, schema = Schema(type = "string", allowableValues = ["true", "false"])),
        ]
    )
    @ApiResponse(responseCode = "204", description = "No Content")
    fun deleteBucket(@PathVariable bucket: String, @RequestParam legacy: Boolean = false): ResponseEntity<String> {
        if (!legacy) {
            val deleteBucket = s3Client().deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build())
            log.info("deleteBucket: $deleteBucket")
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
        }
        val request: RequestEntity<Void> = s3RequestBuilder(method = HttpMethod.DELETE, path = { b -> b.bucket(bucket) })
            .build()
            .toEntityBuilder()
            .build()
        log.info("DeleteBucket: $request")
        val exchange = restTemplate.exchange(request, String::class.java)
        return ResponseEntity.status(exchange.statusCode).body(exchange.body)
    }

    @GetMapping("buckets/{bucket}")
    @CustomOperation(summary = "Get Single Buckets", tags = ["S3"],
        parameters = [
            Parameter(name = "bucket", description = "Bucket name", example = "lorem", `in` = ParameterIn.PATH, required = true),
            Parameter(name = "legacy", description = "Get Buckets via Rest or Library", example = "true", `in` = ParameterIn.QUERY,
                required = false, schema = Schema(type = "string", allowableValues = ["true", "false"])),
        ]
    )
    @ApiResponse(responseCode = "200", description = "Bucket detail", content = [ContentSwagger(
        mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ListBucketResult::class))])
    fun listBucket(@PathVariable bucket: String, @RequestParam legacy: Boolean = false): ResponseEntity<ListBucketResult> {
        if (!legacy) {
            val listObjects = s3Client().listObjects(ListObjectsRequest.builder().bucket(bucket).build())
            return ResponseEntity.ok(ListBucketResult(isTruncated = listObjects.isTruncated, marker = listObjects.marker(),
                name = listObjects.name(), prefix = listObjects.prefix(), maxKeys = listObjects.maxKeys(),
                contents = listObjects.contents().map { c -> Content(key = c.key(), lastModified = c.lastModified().atOffset(ZoneOffset.UTC),
                    etag = c.eTag(), size = c.size(), owner = Owner(id = c.owner().id(), displayName = c.owner().displayName()),
                    storageClass = c.storageClassAsString()) }))
        }
        val request: RequestEntity<Void> = s3RequestBuilder(method = HttpMethod.GET, path = { b -> b.bucket(bucket) })
            .build()
            .toEntityBuilder()
            .build()
        log.info("listBucket: $request")
        val exchange = restTemplate.exchange(request, ListBucketResult::class.java)
        return ResponseEntity.status(exchange.statusCode).body(exchange.body)
    }

    @PutMapping("buckets/{bucket}/files")
    @CustomOperation(summary = "Put Object to Buckets", tags = ["S3"],
        parameters = [
            Parameter(name = "bucket", description = "Bucket name", example = "lorem", `in` = ParameterIn.PATH, required = true),
            Parameter(name = "legacy", description = "Get Buckets via Rest or Library", example = "true", `in` = ParameterIn.QUERY,
                required = false, schema = Schema(type = "string", allowableValues = ["true", "false"])),
            Parameter(name = "file", description = "File", `in` = ParameterIn.QUERY, content = [
                ContentSwagger(schema = Schema(implementation = MultipartFile::class),
                    mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE)]),
        ]
    )
    @ApiResponse(responseCode = "200", description = "Bucket detail", content = [ContentSwagger(
        mediaType = MediaType.TEXT_PLAIN_VALUE, schema = Schema(implementation = String::class))])
    fun putObject(@PathVariable bucket: String, @ModelAttribute(value = "file") file: MultipartFile,
                  @RequestParam legacy: Boolean = false): ResponseEntity<String> {
        val generateFileName = file.originalFilename?.generateFileName() ?: throw Exception("OriginalFilename is null")
        if (!legacy) {
            val objectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(generateFileName)
                .contentType(file.contentType)
                .contentLength(file.size)
                .build()
            val putObject = s3Client().putObject(objectRequest, RequestBody.fromBytes(file.resource.contentAsByteArray))
            log.info("putObject: $putObject")
            return ResponseEntity.ok(generateFileName)
        }
        val body = file.resource.contentAsByteArray
        val request: RequestEntity<ByteArray> = s3RequestBuilder(method = HttpMethod.PUT,
            path = { b -> b.bucket(bucket = bucket).key(key = generateFileName) })
            .content(body.of(mediaType = MediaType.valueOf(file.contentType!!)))
            .build()
            .toEntityBuilder()
            .body(body)
        log.info("putObject: $request")
        val exchange = restTemplate.exchange(request, String::class.java)
        return ResponseEntity.status(exchange.statusCode).body(generateFileName)
    }

    @GetMapping("buckets/{bucket}/files/{key}")
    @CustomOperation(summary = "Get Object", tags = ["S3"],
        parameters = [
            Parameter(name = "bucket", description = "Bucket name", example = "lorem", `in` = ParameterIn.PATH, required = true),
            Parameter(name = "key", description = "Object name", example = "ipsum", `in` = ParameterIn.PATH, required = true),
            Parameter(name = "legacy", description = "Get Buckets via Rest or Library", example = "true", `in` = ParameterIn.QUERY, required = false)
        ]
    )
    @ApiResponse(responseCode = "200", description = "Bucket detail", content = [ContentSwagger(
        mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = Schema(implementation = ByteArray::class))])
    fun getObject(@PathVariable bucket: String, @PathVariable key: String, @RequestParam legacy: Boolean = false): ResponseEntity<ByteArray> {
        if (!legacy) {
            val getObject: ResponseInputStream<GetObjectResponse> = s3Client().getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
            return ResponseEntity.status(HttpStatus.OK)
                .contentLength(getObject.response().contentLength())
                .contentType(MediaType.valueOf(getObject.response().contentType()))
                .body(getObject.readAllBytes())
        }
        val request: RequestEntity<Void> = s3RequestBuilder(method = HttpMethod.GET, path = { b -> b.bucket(bucket).key(key) })
            .build()
            .toEntityBuilder()
            .build()
        log.info("getObject: $request")
        val exchange = restTemplate.exchange(request, Resource::class.java)
        val body: Resource = exchange.body ?: throw Exception("Body is null")
        val headers: HttpHeaders = exchange.headers
        return ResponseEntity.status(exchange.statusCode)
            .headers(headers)
            .contentLength(body.contentLength())
            .contentType(headers.contentType ?: throw Exception("ContentType is null"))
            .body(body.contentAsByteArray)
    }

    @DeleteMapping("buckets/{bucket}/files/{key}")
    @CustomOperation(summary = "Get Object", tags = ["S3"],
        parameters = [
            Parameter(name = "bucket", description = "Bucket name", example = "lorem", `in` = ParameterIn.PATH, required = true),
            Parameter(name = "key", description = "Object name", example = "ipsum", `in` = ParameterIn.PATH, required = true),
            Parameter(name = "legacy", description = "Get Buckets via Rest or Library", example = "true", `in` = ParameterIn.QUERY, required = false)
        ]
    )
    @ApiResponse(responseCode = "204", description = "No Content")
    fun deleteObject(@PathVariable bucket: String, @PathVariable key: String, @RequestParam legacy: Boolean = false): ResponseEntity<String> {
        if (!legacy) {
            val deleteObject = s3Client().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
            log.info("deleteObject: $deleteObject")
            return ResponseEntity.noContent().build()
        }
        val request: RequestEntity<Void> = s3RequestBuilder(method = HttpMethod.DELETE, path = { b -> b.bucket(bucket).key(key) })
            .build()
            .toEntityBuilder()
            .build()
        log.info("deleteObject: $request")
        val exchange = restTemplate.exchange(request, String::class.java)
        return ResponseEntity.status(exchange.statusCode).body(exchange.body)
    }
}

class LoggingRequestInterceptor(val log: Logger): ClientHttpRequestInterceptor {
    override fun intercept(request: HttpRequest, body: ByteArray, execution: ClientHttpRequestExecution): ClientHttpResponse {
        log.info("""
            ===========================request begin================================================
            URI         : ${request.uri}
            Method      : ${request.method}
            Headers     : ${request.headers}
            Request body: ${String(body, Charsets.UTF_8)}"
            ==========================request end================================================
            """)
        val response: ClientHttpResponse = execution.execute(request, body)
        val inputStringBuilder = StringBuilder()
        val bufferedReader = BufferedReader(InputStreamReader(response.body, Charsets.UTF_8))
        var line: String? = bufferedReader.readLine()
        while (line != null) {
            inputStringBuilder.append("$line\n")
            line = bufferedReader.readLine()
        }
        log.info("""
            ============================response begin==========================================
            Status code  : ${response.statusCode}
            Status text  : ${response.statusText}
            Headers      : ${response.headers}
            Response body: $inputStringBuilder
            =======================response end=================================================
           """)
        return response
    }
}

@RestControllerAdvice
class RestExceptionHandler {
    private final val log: Logger = LoggerFactory.getLogger(javaClass.name)

    @ExceptionHandler(RestClientResponseException::class)
    fun handleRestClientResponseException(ex: RestClientResponseException): ResponseEntity<Any> =
        generateResponseEntity(httpStatus = ex.statusCode, errorType = ex.statusText, variables = arrayOf(ex.message))

    @ExceptionHandler(Exception::class)
    fun handleGeneralException(ex: Exception): ResponseEntity<Any> =
        generateResponseEntity(httpStatus = HttpStatus.INTERNAL_SERVER_ERROR, variables = arrayOf("server_error", ex.message),
            errorType = "GENERIC_SERVICE_ERROR")

    /**
     * @param httpStatus -- returned code
     * @return -- returned body
     */
    private fun generateResponseEntity(httpStatus: HttpStatusCode = HttpStatus.INTERNAL_SERVER_ERROR,
                                       errorType: String, variables: Array<String?>): ResponseEntity<Any> =
        log.error("Exception is handled. HttpStatus: $httpStatus, OmaErrorMessageType: $errorType, variables: ${variables.toList()}")
            .run { ExceptionDto() }
            .apply {
                this.statusCode = httpStatus.value()
                this.error = errorType
                this.variables = variables
            }
            .run { ResponseEntity.status(httpStatus).body(this) }
}

fun String.generateFileName(): String {
    val localTime = LocalDateTime.now()
    return "${localTime.dayOfMonth}${localTime.monthValue}${localTime.year}${localTime.hour}${localTime.minute}${localTime.second}-$this"
}

fun main(args: Array<String>) {
    runApplication<KotlinS3ClientApplication>(*args)
}
