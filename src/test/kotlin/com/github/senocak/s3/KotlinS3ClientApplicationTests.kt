package com.github.senocak.s3

import com.github.dockerjava.api.command.CreateContainerCmd
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.core.IsNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Duration
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
@ActiveProfiles(value = ["integration-test"])
@Testcontainers(disabledWithoutDocker = true)
class KotlinS3ClientApplicationTests {
    @Autowired protected lateinit var mockMvc: MockMvc
    @Autowired protected lateinit var s3Client: S3Client

    companion object {
        @Container
        val localStackContainer: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:3.6.0"))
                .withCopyFileToContainer(
                    MountableFile.forClasspathResource("init-s3-bucket.sh", 484),
                    "/etc/localstack/init/ready.d/init-s3-bucket.sh")
                .withServices(LocalStackContainer.Service.S3)
                .withExposedPorts(4566)
                .withCreateContainerCmdModifier { cmd: CreateContainerCmd -> cmd.withName("localstack_${UUID.randomUUID()}") }
                .withStartupTimeout(Duration.ofMinutes(2))
                .withReuse(true)
                .waitingFor(Wait.forLogMessage(".*Executed init-s3-bucket.sh.*", 1))

        init {
            localStackContainer.start()
        }

        @JvmStatic
        @AfterAll
        fun afterAll() {
            localStackContainer.stop()
        }

        @JvmStatic
        @DynamicPropertySource
        fun dynamicPropertySource(registry: DynamicPropertyRegistry) {
            registry.add("s3.endpoint") { "http://${localStackContainer.host}:${localStackContainer.getMappedPort(4566)}" }
            registry.add("s3.access-key") { localStackContainer.accessKey }
            registry.add("s3.secret-key") { localStackContainer.secretKey }
            registry.add("s3.region") { localStackContainer.region }
            registry.add("trace") { "true" }
        }
    }

    @Test
    fun given_whenGetSettings_thenAssertResult() {
        // Given
        val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders.get("/s3/settings")
        // When
        val perform = mockMvc.perform(requestBuilder)
        // Then
        perform
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andExpect(MockMvcResultMatchers.jsonPath("$.s3Request", IsNull.notNullValue()))
            .andExpect(MockMvcResultMatchers.jsonPath("$.headers", IsNull.notNullValue()))
            .andExpect(MockMvcResultMatchers.jsonPath("$.headers.Authorization", IsNull.notNullValue()))
            .andExpect(MockMvcResultMatchers.jsonPath("$.headers.Host", IsNull.notNullValue()))
            .andExpect(MockMvcResultMatchers.jsonPath("$.headers.X-Amz-Content-Sha256", IsNull.notNullValue()))
            .andExpect(MockMvcResultMatchers.jsonPath("$.headers.X-Amz-Date", IsNull.notNullValue()))
    }

    @Nested
    inner class GetBuckets {

        @Test
        fun givenLegacyIsFalse_whenGetBuckets_thenAssertResult() {
            // Given
            val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders
                .get("/s3/buckets")
                .param("legacy", "false")
            // When
            val perform = mockMvc.perform(requestBuilder)
            // Then
            perform
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.owner.id", IsNull.notNullValue()))
                .andExpect(MockMvcResultMatchers.jsonPath("$.owner.displayName", IsNull.notNullValue()))
                .andExpect(MockMvcResultMatchers.jsonPath("$.buckets", hasSize<Any>(1)))
                .andExpect(MockMvcResultMatchers.jsonPath("$.buckets[0].name", equalTo("reflectoring-bucket")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.buckets[0].creationDate", IsNull.notNullValue()))
        }

        @Test
        fun givenLegacyIsTrue_whenGetBuckets_thenAssertResult() {
            // Given
            val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders
                .get("/s3/buckets")
                .param("legacy", "true")
            // When
            val perform = mockMvc.perform(requestBuilder)
            // Then
            perform
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.owner.id", IsNull.notNullValue()))
                .andExpect(MockMvcResultMatchers.jsonPath("$.owner.displayName", IsNull.notNullValue()))
                .andExpect(MockMvcResultMatchers.jsonPath("$.buckets", hasSize<Any>(1)))
                .andExpect(MockMvcResultMatchers.jsonPath("$.buckets[0].name", equalTo("reflectoring-bucket")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.buckets[0].creationDate", IsNull.notNullValue()))
        }

    }

    @Nested
    inner class CreateBucket {
        private val createdBucket = UUID.randomUUID().toString()

        @AfterEach
        fun afterEach() {
            s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(createdBucket).build())
        }

        @Test
        fun givenLegacyIsFalse_whenCreateBucket_thenAssertResult() {
            // Given
            val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders
                .put("/s3/buckets/$createdBucket")
                .param("legacy", "false")
            // When
            val perform = mockMvc.perform(requestBuilder)
            // Then
            perform
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$", equalTo("/$createdBucket")))
        }

        @Test
        fun givenLegacyIsTrue_whenGetBuckets_thenAssertResult() {
            // Given
            val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders
                .put("/s3/buckets/$createdBucket")
                .param("legacy", "true")
            // When
            val perform = mockMvc.perform(requestBuilder)
            // Then
            perform
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$", equalTo("/$createdBucket")))
        }

    }

    @Nested
    inner class DeleteBucket {
        private val createdBucket = UUID.randomUUID().toString()

        @BeforeEach
        fun beforeEach() {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(createdBucket).build())
        }

        @Test
        fun givenLegacyIsFalse_whenDeleteBucket_thenAssertResult() {
            // Given
            val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders
                .delete("/s3/buckets/$createdBucket")
                .param("legacy", "false")
            // When
            val perform = mockMvc.perform(requestBuilder)
            // Then
            perform
                .andExpect(MockMvcResultMatchers.status().isNoContent())
        }

        @Test
        fun givenLegacyIsTrue_whenGetBuckets_thenAssertResult() {
            // Given
            val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders
                .delete("/s3/buckets/$createdBucket")
                .param("legacy", "true")
            // When
            val perform = mockMvc.perform(requestBuilder)
            // Then
            perform
                .andExpect(MockMvcResultMatchers.status().isNoContent())
        }

    }

    @Nested
    inner class GetBucket {
        private val createdBucket = UUID.randomUUID().toString()

        @BeforeEach
        fun beforeEach() {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(createdBucket).build())
        }

        @AfterEach
        fun afterEach() {
            s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(createdBucket).build())
        }

        @Test
        fun givenLegacyIsFalse_whenGetBucket_thenAssertResult() {
            // Given
            val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders
                .get("/s3/buckets/$createdBucket")
                .param("legacy", "false")
            // When
            val perform = mockMvc.perform(requestBuilder)
            // Then
            perform
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.isTruncated", equalTo(false)))
                .andExpect(MockMvcResultMatchers.jsonPath("$.marker", equalTo("")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.name", equalTo(createdBucket)))
                .andExpect(MockMvcResultMatchers.jsonPath("$.prefix", equalTo("")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.maxKeys", equalTo(1000)))
                .andExpect(MockMvcResultMatchers.jsonPath("$.contents", hasSize<Any>(0)))
        }

        @Test
        fun givenLegacyIsTrue_whenGetBucket_thenAssertResult() {
            // Given
            val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders
                .get("/s3/buckets/$createdBucket")
                .param("legacy", "true")
            // When
            val perform = mockMvc.perform(requestBuilder)
            // Then
            perform
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.isTruncated", equalTo(false)))
                .andExpect(MockMvcResultMatchers.jsonPath("$.marker", equalTo("")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.name", equalTo(createdBucket)))
                .andExpect(MockMvcResultMatchers.jsonPath("$.prefix", equalTo("")))
                .andExpect(MockMvcResultMatchers.jsonPath("$.maxKeys", equalTo(1000)))
                .andExpect(MockMvcResultMatchers.jsonPath("$.contents", hasSize<Any>(0)))
        }
    }

    @Nested
    inner class PutObject {
        private val createdBucket = UUID.randomUUID().toString()
        private var file: MockMultipartFile = MockMultipartFile("file", "dummy.csv",
            "text/plain", "Some dataset...".toByteArray())
        private lateinit var generateFileName: String

        @BeforeEach
        fun beforeEach() {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(createdBucket).build())
        }

        @AfterEach
        fun afterEach() {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(createdBucket).key(generateFileName).build())
            s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(createdBucket).build())
        }

        @Test
        fun givenLegacyIsFalse_whenPutObject_thenAssertResult() {
            // Given
            val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders
                .multipart("/s3/buckets/$createdBucket/files")
                .file(file)
                .with { request ->
                    request.method = "PUT"
                    request
                }
                .param("legacy", "false")
            // When
            val perform = mockMvc.perform(requestBuilder)
            // Then
            perform
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$", IsNull.notNullValue()))
            generateFileName = perform.andReturn().response.contentAsString
        }

        @Test
        fun givenLegacyIsTrue_whenPutObject_thenAssertResult() {
            // Given
            val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders
                .multipart("/s3/buckets/$createdBucket/files")
                .file(file)
                .with { request ->
                    request.method = "PUT"
                    request
                }
                .param("legacy", "true")
            // When
            val perform = mockMvc.perform(requestBuilder)
            // Then
            perform
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$", IsNull.notNullValue()))
            generateFileName = perform.andReturn().response.contentAsString
        }
    }

    @Nested
    inner class GetObject {
        private val createdBucket = UUID.randomUUID().toString()
        private var file: MockMultipartFile = MockMultipartFile("file", "dummy.csv",
            "text/plain", "Some dataset...".toByteArray())
        private lateinit var generateFileName: String

        @BeforeEach
        fun beforeEach() {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(createdBucket).build())
            generateFileName = file.originalFilename.generateFileName()
            val objectRequest = PutObjectRequest.builder()
                .bucket(createdBucket)
                .key(generateFileName)
                .contentType(file.contentType)
                .contentLength(file.size)
                .build()
            s3Client.putObject(objectRequest, RequestBody.fromBytes(file.resource.contentAsByteArray))
        }

        @AfterEach
        fun afterEach() {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(createdBucket).key(generateFileName).build())
            s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(createdBucket).build())
        }

        @Test
        fun givenLegacyIsFalse_whenGetObject_thenAssertResult() {
            // Given
            val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders
                .get("/s3/buckets/$createdBucket/files/$generateFileName")
                .param("legacy", "false")
            // When
            val perform = mockMvc.perform(requestBuilder)
            // Then
            perform
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.header().exists(HttpHeaders.CONTENT_LENGTH))
                .andExpect(MockMvcResultMatchers.header().exists(HttpHeaders.CONTENT_TYPE))
                .andExpect(MockMvcResultMatchers.jsonPath("$", equalTo("Some dataset...")))
        }

        @Test
        fun givenLegacyIsTrue_whenGetObject_thenAssertResult() {
            // Given
            val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders
                .get("/s3/buckets/$createdBucket/files/$generateFileName")
                .param("legacy", "true")
            // When
            val perform = mockMvc.perform(requestBuilder)
            // Then
            perform
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.header().exists(HttpHeaders.CONTENT_LENGTH))
                .andExpect(MockMvcResultMatchers.header().exists(HttpHeaders.CONTENT_TYPE))
                .andExpect(MockMvcResultMatchers.jsonPath("$", equalTo("Some dataset...")))
        }
    }

    @Nested
    inner class DeleteObject {
        private val createdBucket = UUID.randomUUID().toString()
        private var file: MockMultipartFile = MockMultipartFile("file", "dummy.csv",
            "text/plain", "Some dataset...".toByteArray())
        private lateinit var generateFileName: String

        @BeforeEach
        fun beforeEach() {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(createdBucket).build())
            generateFileName = file.originalFilename.generateFileName()
            val objectRequest = PutObjectRequest.builder()
                .bucket(createdBucket)
                .key(generateFileName)
                .contentType(file.contentType)
                .contentLength(file.size)
                .build()
            s3Client.putObject(objectRequest, RequestBody.fromBytes(file.resource.contentAsByteArray))
        }

        @AfterEach
        fun afterEach() {
            s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(createdBucket).build())
        }

        @Test
        fun givenLegacyIsFalse_whenDeleteObject_thenAssertResult() {
            // Given
            val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders
                .delete("/s3/buckets/$createdBucket/files/$generateFileName")
                .param("legacy", "false")
            // When
            val perform = mockMvc.perform(requestBuilder)
            // Then
            perform
                .andExpect(MockMvcResultMatchers.status().isNoContent())
        }

        @Test
        fun givenLegacyIsTrue_whenDeleteObject_thenAssertResult() {
            // Given
            val requestBuilder: MockHttpServletRequestBuilder = MockMvcRequestBuilders
                .delete("/s3/buckets/$createdBucket/files/$generateFileName")
                .param("legacy", "true")
            // When
            val perform = mockMvc.perform(requestBuilder)
            // Then
            perform
                .andExpect(MockMvcResultMatchers.status().isNoContent())
        }
    }
}
