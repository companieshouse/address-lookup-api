#!/usr/bin/env bash
# Checks that every bucket's policy denies non-TLS access, using the IAM policy simulator
# against the deployed bucket policy. Usage: test-deny-insecure-transport.sh <environment>
set -euo pipefail

environment="${1:?usage: $0 <environment>}"
principal="$(aws sts get-caller-identity --query Arn --output text)"
status=0

for purpose in control landing quarantine; do
  bucket="address-lookup-${environment}-${purpose}"
  policy="$(aws s3api get-bucket-policy --bucket "$bucket" --query Policy --output text)"

  for secure in false true; do
    decision="$(aws iam simulate-custom-policy \
      --policy-input-list "$policy" \
      --action-names s3:GetObject \
      --resource-arns "arn:aws:s3:::${bucket}/test" \
      --caller-arn "$principal" \
      --context-entries "ContextKeyName=aws:SecureTransport,ContextKeyValues=${secure},ContextKeyType=boolean" \
      --query 'EvaluationResults[0].EvalDecision' --output text)"

    if [[ "$secure" == "false" && "$decision" != "explicitDeny" ]]; then
      echo "FAIL: $bucket allows non-TLS access ($decision)"
      status=1
    elif [[ "$secure" == "false" ]]; then
      echo "PASS: $bucket denies non-TLS access"
    fi
  done
done

exit "$status"
