package com.github.senocak.s3

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.Locale
import java.util.TreeMap
import java.util.function.Consumer
import java.util.function.Function
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val AMZDATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
private const val X_AMZ_CONTENT_SHA256 = "X-Amz-Content-Sha256"
private const val X_AMZ_DATE = "X-Amz-Date"
private const val AWS4_HMAC_SHA256: String = "AWS4-HMAC-SHA256"
private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

data class AmzDate(
    val timestamp: Instant,
    private val dateTime: OffsetDateTime = timestamp.atOffset(ZoneOffset.UTC)
) {
    val date: String = AMZDATE_FORMATTER.format(dateTime)
    val yymmdd: String = date.substring(startIndex = 0, endIndex = 8)
}

data class Bucket(
    @JacksonXmlProperty(localName = "Name") val name: String,
    @JacksonXmlProperty(localName = "CreationDate") val creationDate: OffsetDateTime
)

data class Content(
    @JacksonXmlProperty(localName = "Key") val key: String,
    @JacksonXmlProperty(localName = "LastModified") val lastModified: OffsetDateTime,
    @JacksonXmlProperty(localName = "ETag") val etag: String,
    @JacksonXmlProperty(localName = "Size") val size: Long,
    @JacksonXmlProperty(localName = "Owner") val owner: Owner,
    @JacksonXmlProperty(localName = "StorageClass") val storageClass: String
)

data class DeleteMarker(
    @JacksonXmlProperty(localName = "Key") val key: String,
    @JacksonXmlProperty(localName = "LastModified") val lastModified: String,
    @JacksonXmlProperty(localName = "ETag") val eTag: String,
    @JacksonXmlProperty(localName = "Size") val size: Int,
    @JacksonXmlProperty(localName = "Owner") val owner: Owner,
    @JacksonXmlProperty(localName = "StorageClass") val storageClass: String,
    @JacksonXmlProperty(localName = "IsLatest") val isLatest: Boolean,
    @JacksonXmlProperty(localName = "VersionId") val versionId: String
)

@JacksonXmlRootElement(localName = "ListBucketResult")
data class ListBucketResult(
    @JacksonXmlProperty(localName = "IsTruncated") val isTruncated: Boolean?,
    @JacksonXmlProperty(localName = "Marker") val marker: String?,
    @JacksonXmlProperty(localName = "Name") val name: String?,
    @JacksonXmlProperty(localName = "Prefix") val prefix: String?,
    @JacksonXmlProperty(localName = "MaxKeys") val maxKeys: Int?,
    @JacksonXmlElementWrapper(useWrapping = false) @JacksonXmlProperty(localName = "Contents") val contents: List<Content>? = listOf()
)

@JacksonXmlRootElement(localName = "ListAllMyBucketsResult")
data class ListBucketsResult(
    @JacksonXmlProperty(localName = "Owner") val owner: Owner,
    @JacksonXmlProperty(localName = "Buckets") val buckets: List<Bucket>
)


@JacksonXmlRootElement(localName = "ListVersionsResult")
data class ListVersionsResult(
    @JacksonXmlProperty(localName = "Name") val name: String,
    @JacksonXmlProperty(localName = "Prefix") val prefix: String,
    @JacksonXmlProperty(localName = "KeyMarker") val keyMarker: String,
    @JacksonXmlProperty(localName = "NextVersionIdMarker") val nextVersionIdMarker: String,
    @JacksonXmlProperty(localName = "VersionIdMarker") val versionIdMarker: String,
    @JacksonXmlProperty(localName = "MaxKeys") val maxKeys: Int,
    @JacksonXmlProperty(localName = "IsTruncated") val isTruncated: Boolean,
    @JacksonXmlElementWrapper(useWrapping = false) @JacksonXmlProperty(localName = "DeleteMarker") var deleteMarkers: List<DeleteMarker>,
    @JacksonXmlElementWrapper(useWrapping = false) @JacksonXmlProperty(localName = "Version") val versions: List<Version>
)

data class Owner(
    @JacksonXmlProperty(localName = "ID") val id: String,
    @JacksonXmlProperty(localName = "DisplayName") val displayName: String
)

data class Version(
    @JacksonXmlProperty(localName = "Key") val key: String,
    @JacksonXmlProperty(localName = "LastModified") val lastModified: String,
    @JacksonXmlProperty(localName = "ETag") val eTag: String,
    @JacksonXmlProperty(localName = "Size") val size: Int,
    @JacksonXmlProperty(localName = "Owner") val owner: Owner,
    @JacksonXmlProperty(localName = "StorageClass") val storageClass: String,
    @JacksonXmlProperty(localName = "IsLatest") val isLatest: Boolean,
    @JacksonXmlProperty(localName = "VersionId") val versionId: String
)

data class S3Content(val body: ByteArray, val mediaType: MediaType)
fun String.of(mediaType: MediaType): S3Content = this.toByteArray(StandardCharsets.UTF_8).of(mediaType = mediaType)
fun ByteArray.of(mediaType: MediaType): S3Content = S3Content(body = this, mediaType = mediaType)

class S3Path(private val bucket: String?, private val key: String?, val encodeKey: Boolean = true) {

    fun toCanonicalUri(): String {
        val builder = StringBuilder()
        if (bucket.isNullOrEmpty()) {
            builder.append("/")
        } else {
            if (!bucket.startsWith(prefix = "/")) {
                builder.append("/")
            }
            builder.append(bucket)
        }
        if (!key.isNullOrEmpty()) {
            if (!key.startsWith(prefix = "/")) {
                builder.append("/")
            }
            if (encodeKey) {
                builder.append(encodeKey(key))
            } else {
                builder.append(key)
            }
        }
        return builder.toString()
    }

    companion object {
        private fun encodeKey(key: String): String? {
            val encodedKey = UriComponentsBuilder.fromPath(key).encode().build().path ?: return null
            return encodedKey
                .replace(oldValue = "!", newValue = "%21")
                .replace(oldValue = "#", newValue = "%23")
                .replace(oldValue = "$", newValue = "%24")
                .replace(oldValue = "&", newValue = "%26")
                .replace(oldValue = "'", newValue = "%27")
                .replace(oldValue = "(", newValue = "%28")
                .replace(oldValue = ")", newValue = "%29")
                .replace(oldValue = "*", newValue = "%2A")
                .replace(oldValue = "+", newValue = "%2B")
                .replace(oldValue = ",", newValue = "%2C")
                .replace(oldValue = ":", newValue = "%3A")
                .replace(oldValue = ";", newValue = "%3B")
                .replace(oldValue = "=", newValue = "%3D")
                .replace(oldValue = "@", newValue = "%40")
                .replace(oldValue = "[", newValue = "%5B")
                .replace(oldValue = "]", newValue = "%5D")
                .replace(oldValue = "{", newValue = "%7B")
                .replace(oldValue = "}", newValue = "%7D")
        }
    }
}

class S3PathBuilder {
    private var bucket: String? = null
    private var key: String? = null
    private var encodeKey: Boolean = true

    fun bucket(bucket: String): S3PathBuilder = this.also { it.bucket = bucket }
    fun key(key: String): S3PathBuilder = this.also { it.key = key }
    fun encodeKey(encodeKey: Boolean): S3PathBuilder = this.also { it.encodeKey = encodeKey }
    fun build(): S3Path = S3Path(bucket = bucket, key = key, encodeKey = encodeKey)
}

class S3RequestBuilder {
    private lateinit var endpoint: URI
    private lateinit var region: String
    private lateinit var accessKeyId: String
    private lateinit var secretAccessKey: String
    private lateinit var method: HttpMethod
    private lateinit var path: Function<S3PathBuilder, S3PathBuilder>
    private var canonicalQueryString: String ? = ""
    private var content: S3Content? = null
    private var clock: Clock? = Clock.systemUTC()

    fun endpoint(endpoint: URI): S3RequestBuilder = this.also { it.endpoint = endpoint }
    fun region(region: String): S3RequestBuilder = this.also { it.region = region }
    fun accessKeyId(accessKeyId: String): S3RequestBuilder = this.also { it.accessKeyId = accessKeyId }
    fun secretAccessKey(secretAccessKey: String): S3RequestBuilder = this.also { it.secretAccessKey = secretAccessKey }
    fun method(method: HttpMethod): S3RequestBuilder = this.also { it.method = method }
    fun path(path: Function<S3PathBuilder, S3PathBuilder>): S3RequestBuilder = this.also { it.path = path }
    fun canonicalQueryString(canonicalQueryString: String): S3RequestBuilder = this.also { it.canonicalQueryString = canonicalQueryString }
    fun content(content: S3Content): S3RequestBuilder = this.also { it.content = content }
    fun clock(clock: Clock): S3RequestBuilder = this.also { it.clock = clock }
    fun build(): S3Request =
        S3Request(
            endpoint = endpoint,
            region = region,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            method = method,
            path = path,
            canonicalQueryString = canonicalQueryString,
            content = content,
            clock = clock!!
        )
}

class S3Request(
    internal val endpoint: URI,
    private val region: String,
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val method: HttpMethod,
    path: Function<S3PathBuilder, S3PathBuilder>?,
    val canonicalQueryString: String? = "",
    private val content: S3Content? = null,
    private var clock: Clock? = Clock.systemUTC()
) {
    private val canonicalUri = path?.apply(S3PathBuilder())?.build()?.toCanonicalUri() ?: "/"
    private var uri: URI? = null
    private var httpHeaders: HttpHeaders

    init {
        val amzDate = AmzDate(timestamp = clock!!.instant())
        val contentSha256 = content?.body?.sha256Hash()?.encodeHex() ?: UNSIGNED_PAYLOAD
        val headers = TreeMap<String, String>()
        val host = StringBuilder(endpoint.host)
        if (endpoint.port != -1)
            host.append(":").append(endpoint.port)
        headers[HttpHeaders.HOST] = host.toString()
        headers[X_AMZ_CONTENT_SHA256] = contentSha256
        headers[X_AMZ_DATE] = amzDate.date
        if (content?.body != null)
            headers[HttpHeaders.CONTENT_LENGTH] = "${content.body.size}"
        if (content?.mediaType != null)
            headers[HttpHeaders.CONTENT_TYPE] = "${content.mediaType}"
        val authorization = authorization(headers = headers, payloadHash = contentSha256, amzDate = amzDate)
        this.httpHeaders = HttpHeaders()
            .also { it.add(HttpHeaders.AUTHORIZATION, authorization) }
            .also { headers.forEach { (headerName: String, headerValue: String) -> it.add(headerName, headerValue) } }
        this.uri = UriComponentsBuilder.fromUri(this.endpoint)
            .path(canonicalUri)
            .query(canonicalQueryString)
            .build(true)
            .toUri()
    }

    //fun uri(): URI? = uri
    fun headers(): HttpHeaders = httpHeaders
    fun toEntityBuilder(): RequestEntity.BodyBuilder = RequestEntity.method(method, uri!!).headers(httpHeaders)

    private fun authorization(headers: TreeMap<String, String>,  /* must appear in alphabetical order */
                              payloadHash: String, amzDate: AmzDate): String {
        // Step 1: Create a canonical request: https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html#create-canonical-request
        val canonicalHeaders = headers.entries
            .map { e: Map.Entry<String, String> -> "${e.key.lowercase(Locale.getDefault())}:${e.value}" }
            .joinToString(separator = "\n") + "\n"
        val signedHeaders = headers.keys
            .map { obj: String -> obj.lowercase(Locale.getDefault()) }
            .joinToString(separator = ";")
        val canonicalRequest = listOf(method.name(), canonicalUri, canonicalQueryString, canonicalHeaders, signedHeaders, payloadHash)
            .joinToString(separator = "\n")

        // Step 2: Create a hash of the canonical request: https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html#create-canonical-request-hash
        val hashedCanonicalRequest = canonicalRequest.toByteArray(charset = StandardCharsets.UTF_8).sha256Hash().encodeHex()

        // Step 3: Create a string to sign: https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html#create-string-to-sign
        val credentialScope = "${amzDate.yymmdd}/$region/s3/aws4_request"
        val stringToSign = listOf(AWS4_HMAC_SHA256, amzDate.date, "${amzDate.yymmdd}/$region/s3/aws4_request", hashedCanonicalRequest)
            .joinToString("\n")

        // Step 4: Calculate the signature: https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html#calculate-signature
        val signature = sign(stringToSign = stringToSign, amzDate = amzDate)

        // Step 5: Add the signature to the request: https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html#add-signature-to-request
        return "$AWS4_HMAC_SHA256 Credential=$accessKeyId/$credentialScope,SignedHeaders=$signedHeaders,Signature=$signature"
    }

    private fun sign(stringToSign: String, amzDate: AmzDate): String {
        val kSecret = "AWS4$secretAccessKey".toByteArray(StandardCharsets.UTF_8)
        val kDate = hmacSHA256(data = amzDate.yymmdd, key = kSecret)
        val kRegion = hmacSHA256(data = this.region, key = kDate)
        val kService = hmacSHA256(data = "s3", key = kRegion)
        val kSigning = hmacSHA256(data = "aws4_request", key = kService)
        return hmacSHA256(data = stringToSign, key = kSigning).encodeHex()
    }

    override fun toString(): String = "S3Request{endpoint=$endpoint, region=$region, accessKeyId=$accessKeyId, method=$method,canonicalUri=$canonicalUri, canonicalQueryString=$canonicalQueryString}"
}

private fun ByteArray.sha256Hash(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)

private fun hmacSHA256(data: String, key: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256")
        .apply { this.init(SecretKeySpec(key, "HmacSHA256")) }
        .run { this.doFinal(data.toByteArray(StandardCharsets.UTF_8)) }

private fun ByteArray.encodeHex(): String {
    val hex = HexFormat.of()
    return this.joinToString(separator = "", transform = { byte -> hex.toHexDigits(byte) })
}
