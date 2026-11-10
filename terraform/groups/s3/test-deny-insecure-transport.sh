#!/usr/bin/env bash
# Use the same signed bucket operation over HTTPS and HTTP to isolate the TLS deny.
set -euo pipefail

environment="${1:?usage: $0 <environment>}"
region="${AWS_REGION:-eu-west-2}"
export AWS_PAGER="" AWS_MAX_ATTEMPTS=1
account="$(aws sts get-caller-identity --query Account --output text)"
bucket="address-lookup-${environment}-acquisition-${account}"

# A positive control prevents missing permissions or credentials from appearing to pass.
status="$(aws s3api get-bucket-versioning --region "$region" --bucket "$bucket" \
  --query Status --output text)"
[[ "$status" == "Enabled" ]] || {
  echo "FAIL: HTTPS versioning check returned $status" >&2
  exit 1
}

if response="$(aws s3api get-bucket-versioning --region "$region" --bucket "$bucket" \
  --endpoint-url "http://s3.${region}.amazonaws.com" 2>&1)"; then
  echo "FAIL: $bucket allowed signed HTTP access" >&2
  exit 1
fi

if [[ "$response" != *"(AccessDenied)"* ]]; then
  echo "FAIL: HTTP request failed without an S3 AccessDenied response: $response" >&2
  exit 1
fi
echo "PASS: $bucket allows HTTPS and denies the same signed operation over HTTP"
