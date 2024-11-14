## Spring Kotlin S3 Operations Both Legacy(Rest API) & `software.amazon.awssdk:s3`

### Get Settings
```
GET http://localhost:8080/s3
```

### List All Buckets
```
GET http://localhost:8080/s3/buckets?legacy=true

> {%
    const buckets = response.body.buckets
    if (buckets.length > 0) {
        client.global.set("bucketName", buckets[buckets.length - 1].name)
    }
%}
```

### Create New Bucket
```
PUT http://localhost:8080/s3/buckets/{{$timestamp}}?legacy=true

> {%
client.global.set("bucketName", request.url().split("/")[request.url().split("/").length - 1])
%}
```

### Get Single Bucket
```
GET http://localhost:8080/s3/buckets/{{bucketName}}?legacy=true

> {%
    const contents = response.body.contents
    if (contents.length > 0) {
        client.global.set("key", contents[contents.length - 1].key)
    }
%}
```

### Delete Single Bucket
```
DELETE http://localhost:8080/s3/buckets/{{bucketName}}?legacy=true

> {%
client.global.clear("bucketName")
%}
```

### Put Object Of Bucket
```
< {%
client.global.set("key", $random.alphabetic(10))
%}
PUT http://localhost:8080/s3/buckets/{{bucketName}}/files?legacy=true
Content-Type: multipart/form-data; boundary=WebAppBoundary

--WebAppBoundary
Content-Disposition: form-data; name="file"; filename="data.json"
Content-Type: text/plain

< /Users/tcasenocak/Desktop/github/kotlin-s3-client/src/main/resources/application.yml
```

### Get Content Of Object Of Bucket
```
GET http://localhost:8080/s3/buckets/{{bucketName}}/files/{{key}}?legacy=true
```

### Delete Object Of Bucket
```
DELETE http://localhost:8080/s3/buckets/{{bucketName}}/files/{{key}}?legacy=true

> {%
client.global.clear("key")
%}
```