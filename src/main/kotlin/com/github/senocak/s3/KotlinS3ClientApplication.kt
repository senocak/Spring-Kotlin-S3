package com.github.senocak.s3

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
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
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
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneOffset

@SpringBootApplication
@RestController
@RequestMapping("/s3")
class KotlinS3ClientApplication(
    @Value("\${s3.endpoint}") val endpoint: String,
    @Value("\${s3.region}") val region: String,
    @Value("\${s3.accessKey}") val accessKey: String,
    @Value("\${s3.secretKey}") val secretKey: String,
) {
    private val log: Logger = LoggerFactory.getLogger(javaClass.name)
    private val restTemplate = RestTemplate()

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

    @GetMapping("rest")
    fun getSettings(): Map<String, Any?> {
        val s3Request = S3Request(endpoint = URI.create(endpoint), region = region, accessKeyId = accessKey,
            secretAccessKey = secretKey, method = HttpMethod.GET, path = { b -> b })
        return mapOf(
            "s3Request" to s3Request.toString(),
            "headers" to s3Request.headers().toSingleValueMap(),
        )
    }

    @GetMapping("buckets")
    fun listBuckets(@RequestParam legacy: Boolean = false): ResponseEntity<ListBucketsResult> {
        if (!legacy) {
            val listBuckets = s3Client().listBuckets()
            return ResponseEntity.ok(ListBucketsResult(owner = Owner(id = listBuckets.owner().id(), displayName = listBuckets.owner().displayName()),
                buckets = listBuckets.buckets().map { Bucket(name = it.name(), creationDate = it.creationDate().atOffset(ZoneOffset.UTC)) }))
        }
        val s3Request = S3Request(endpoint = URI.create(endpoint), region = region, accessKeyId = accessKey,
            secretAccessKey = secretKey, method = HttpMethod.GET, path = { b -> b })
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
    fun putBucket(@PathVariable bucket: String, @RequestParam legacy: Boolean = false): ResponseEntity<String> {
        if (!legacy) {
            val createBucket = s3Client().createBucket(CreateBucketRequest.builder().bucket(bucket).build())
            return ResponseEntity.ok(createBucket.location())
        }
        val request: RequestEntity<Void> = S3RequestBuilder()
            .endpoint(URI.create(endpoint))
            .region(region)
            .accessKeyId(accessKey)
            .secretAccessKey(secretKey)
            .method(HttpMethod.PUT)
            .path { b -> b.bucket(bucket) }
            .build()
            .toEntityBuilder()
            .build()
        log.info("putBucket: $request")
        val exchange = restTemplate.exchange(request, String::class.java)
        return ResponseEntity.status(exchange.statusCode).body(exchange.body)
    }

    @DeleteMapping("buckets/{bucket}")
    fun deleteBucket(@PathVariable bucket: String, @RequestParam legacy: Boolean = false): ResponseEntity<String> {
        if (!legacy) {
            val deleteBucket = s3Client().deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build())
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
        }
        val request: RequestEntity<Void> = S3RequestBuilder()
            .endpoint(URI.create(endpoint))
            .region(region)
            .accessKeyId(accessKey)
            .secretAccessKey(secretKey)
            .method(HttpMethod.DELETE)
            .path { b -> b.bucket(bucket) }
            .build()
            .toEntityBuilder()
            .build()
        log.info("DeleteBucket: $request")
        val exchange = restTemplate.exchange(request, String::class.java)
        return ResponseEntity.status(exchange.statusCode).body(exchange.body)
    }

    @GetMapping("buckets/{bucket}")
    fun listBucket(@PathVariable bucket: String, @RequestParam legacy: Boolean = false): ResponseEntity<ListBucketResult> {
        if (!legacy) {
            val listObjects = s3Client().listObjects(ListObjectsRequest.builder().bucket(bucket).build())
            return ResponseEntity.ok(ListBucketResult(isTruncated = listObjects.isTruncated, marker = listObjects.marker(),
                name = listObjects.name(), prefix = listObjects.prefix(), maxKeys = listObjects.maxKeys(),
                contents = listObjects.contents().map { c -> Content(key = c.key(), lastModified = c.lastModified().atOffset(ZoneOffset.UTC),
                    etag = c.eTag(), size = c.size(), owner = Owner(id = c.owner().id(), displayName = c.owner().displayName()),
                    storageClass = c.storageClassAsString()) }))
        }
        val request: RequestEntity<Void> = S3RequestBuilder()
            .endpoint(URI.create(endpoint))
            .region(region)
            .accessKeyId(accessKey)
            .secretAccessKey(secretKey)
            .method(HttpMethod.GET)
            .path { b -> b.bucket(bucket) }
            .build()
            .toEntityBuilder()
            .build()
        log.info("listBucket: $request")
        val exchange = restTemplate.exchange(request, ListBucketResult::class.java)
        return ResponseEntity.status(exchange.statusCode).body(exchange.body)
    }

    @PutMapping("buckets/{bucket}/files")
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
            return ResponseEntity.ok(generateFileName)
        }
        val body = file.resource.contentAsByteArray
        val request: RequestEntity<ByteArray> = S3RequestBuilder()
            .endpoint(URI.create(endpoint))
            .region(region)
            .accessKeyId(accessKey)
            .secretAccessKey(secretKey)
            .method(HttpMethod.PUT)
            .path { b -> b.bucket(bucket = bucket).key(key = generateFileName) }
            .content(body.of(mediaType = MediaType.valueOf(file.contentType!!)))
            .build()
            .toEntityBuilder()
            .body(body)
        log.info("putObject: $request")
        val exchange = restTemplate.exchange(request, String::class.java)
        return ResponseEntity.status(exchange.statusCode).body(generateFileName)
    }

    @GetMapping("buckets/{bucket}/files/{key}")
    fun getObject(@PathVariable bucket: String, @PathVariable key: String, @RequestParam legacy: Boolean = false): ResponseEntity<ByteArray> {
        if (!legacy) {
            val getObject: ResponseInputStream<GetObjectResponse> = s3Client().getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())
            return ResponseEntity.status(HttpStatus.OK)
                .contentLength(getObject.response().contentLength())
                .contentType(MediaType.valueOf(getObject.response().contentType()))
                .body(getObject.readAllBytes())
        }
        val request: RequestEntity<Void> = S3RequestBuilder()
            .endpoint(URI.create(endpoint))
            .region(region)
            .accessKeyId(accessKey)
            .secretAccessKey(secretKey)
            .method(HttpMethod.GET)
            .path { b -> b.bucket(bucket).key(key) }
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
    fun deleteObject(@PathVariable bucket: String, @PathVariable key: String, @RequestParam legacy: Boolean = false): ResponseEntity<String> {
        if (!legacy) {
            val deleteObject = s3Client().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
            return ResponseEntity.noContent().build()
        }
        val request: RequestEntity<Void> = S3RequestBuilder()
            .endpoint(URI.create(endpoint))
            .region(region)
            .accessKeyId(accessKey)
            .secretAccessKey(secretKey)
            .method(HttpMethod.DELETE)
            .path { b -> b.bucket(bucket).key(key) }
            .build()
            .toEntityBuilder()
            .build()
        log.info("deleteObject: $request")
        val exchange = restTemplate.exchange(request, String::class.java)
        return ResponseEntity.status(exchange.statusCode).body(exchange.body)
    }
}

private fun String.generateFileName(): String {
    val localTime = LocalDateTime.now()
    return "${localTime.dayOfMonth}${localTime.monthValue}${localTime.year}${localTime.hour}${localTime.minute}${localTime.second}-$this"
}

fun main(args: Array<String>) {
    runApplication<KotlinS3ClientApplication>(*args)
}
