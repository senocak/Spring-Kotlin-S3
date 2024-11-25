#!/bin/bash

# Variables
bucket_name="reflectoring-bucket"
object_key="pyproject.toml"
file_to_upload="/opt/code/localstack/$object_key"
file_to_download="/opt/code/localstack/$object_key-new"

# Create S3 bucket
awslocal s3api create-bucket --bucket $bucket_name
echo "S3 bucket '$bucket_name' created successfully"

# Upload file to S3
awslocal s3 cp $file_to_upload s3://$bucket_name/$object_key
echo "File '$file_to_upload' uploaded to bucket '$bucket_name' as '$object_key'"

awslocal s3api get-object --bucket $bucket_name --key $object_key $file_to_download
echo "File '$object_key' downloaded from bucket '$bucket_name' to '$file_to_download'"

echo "Executed init-s3-bucket.sh"
