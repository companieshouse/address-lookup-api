# acquisition

Deploys the three acquisition Lambdas and their IAM roles (ALS-140). The
schedules, the Supply pipeline permissions and the monthly FULL archive
functions are added by the tickets that need them.

| Function | Network | Concurrency | Memory / timeout | Reads | Writes |
|---|---|---|---|---|---|
| `address-lookup-ngd-download-<env>` | No VPC (egress to `api.os.uk`) | Reserved 1 | 10240 MB / 900 s | Its cursor, landing feeds, OS API key parameter | Its cursor, landing feeds (multipart) |
| `address-lookup-ngd-extraction-<env>` | No VPC | Reserved 1 | 10240 MB, 10240 MB `/tmp` / 900 s | Its cursor, landing feeds and their tags | Its cursor, landing feeds (unpacked CSVs, receipts) |
| `address-lookup-ngd-upload-<env>` | Aurora data subnets | Reserved 1 (`upload_reserved_concurrency`) | 4096 MB / 900 s | Its cursor, `daily_cou` feed and its tags, upload DB credentials | Its cursor, `daily_cou` receipts, PostgreSQL (direct, no RDS Proxy) |

Each function handles both feeds (COU and FULL); the event says which. Async
retries are 0: the next scheduled run resumes from the cursor. Each function
has an `Errors > 0` alarm to `common-services-<env>-cloudwatch-alarms-notify-topic`.

Download and Extraction are plain `aws_lambda_function` resources, as in
`companieshouse/s3-av-scanner`, because `terraform-modules` `aws/lambda` always
attaches a VPC. Upload uses `aws/lambda`, as `terraform/groups/db-migrate` does.

## Contract for the Lambda code

Environment variables:

| Variable | Functions | Value |
|---|---|---|
| `CONTROL_BUCKET`, `LANDING_BUCKET` | all | `address-lookup-<env>-<account id>-landing` (one bucket, ALS-139) |
| `CONTROL_PREFIX` | all | `address-lookup/<env>/control/` |
| `LANDING_PREFIX` | all | `address-lookup/<env>/landing/` |
| `CURSOR_PREFIX` | all | `<CONTROL_PREFIX>cursors/` |
| `OS_API_KEY_PARAMETER` | download | `/address-lookup/<env>/acquisition/os-api-key` (SecureString) |
| `DB_CREDENTIALS_PARAMETER` | upload | `/address-lookup/<env>/db/upload` (SecureString `{"username","password","jdbcUrl"}`) |
| `AWS_ENDPOINT_URL_SSM` | upload | The aurora group's SSM endpoint (private DNS is off) |

IAM limits each function to:

* its own cursor objects, as in the code's `KeyLayout` (`feature/als-137-folder-layout`):
  Download `CURSOR_PREFIX<package>.json`, Extraction `CURSOR_PREFIX<package>.extraction.json`,
  Upload `CURSOR_PREFIX<package>.upload.json`. Download is explicitly denied the other two.
  `reconciliation/` and `metadata/checksums/` in Control are not granted yet.
* the landing feed prefixes `<LANDING_PREFIX><feed>/*`: Download and Extraction
  `daily_cou` and `initial_full` (`download_feeds`, `extraction_feeds`), Upload
  `daily_cou` (`upload_feeds`). Packages, receipts and next-version pointers
  must live under the feed prefix.
* `s3:ListBucket` only with a `prefix` inside `CURSOR_PREFIX` or its feed prefixes.
  Listing the bucket root is denied.
* versioned reads (`s3:GetObjectVersion`, and `s3:GetObjectVersionTagging` for the
  GuardDuty tag check in Extraction and Upload), because the code reads by `versionId`.

The code must:

* prepend `CONTROL_PREFIX` / `LANDING_PREFIX` to its keys. ALS-139 makes one bucket,
  so `CONTROL_BUCKET` and `LANDING_BUCKET` are the same name.
* read the OS API key from the SecureString named by `OS_API_KEY_PARAMETER` (Vault →
  Parameter Store, like db-migrate's credentials), not from an `OS_API_KEY` variable.

## Deviations from the design

* Names are `address-lookup-ngd-<name>-<env>`, not `address-lookup-ngd-<name>-lambda-v1`:
  `aws/lambda` forces `<function_name>-<environment>`, and all three use the same form.
* Upload runs in the Aurora data subnets, not the application subnets. The data
  subnets are proven to reach Aurora and the S3 gateway endpoint (db-migrate).
* The policies in this group have no `s3:*` and no `Resource "*"`. Two AWS-owned
  parts do: the `aws/lambda` base policy on Upload (`logs:*` actions and
  `ssm:GetParameters`), and `AWSLambdaVPCAccessExecutionRole` (ENI `ec2:Describe*`
  only works on `*`).
* Trivy `AVD-AWS-0017` (log group CMK) and `AVD-AWS-0066` (X-Ray) are ignored inline,
  with the reason in `unattached.tf`.

## Before the first apply

1. `terraform/groups/s3` (ALS-139) applied: the bucket lookup fails the plan until then.
2. Vault `applications/<profile>/<env>/common-services-stack/address-lookup-acquisition`
   has `os_api_key`.
3. Vault `.../common-services-stack/address-rds` has `upload_username` and
   `upload_password`, and `terraform/groups/aurora` is applied (it writes
   `/address-lookup/<env>/db/upload` and lets the Upload role read it through
   the SSM endpoint).
4. The database role `upload_username` exists with the grants the Upload code needs.

## Invoke with a sample event (acceptance check)

```shell
for name in download extraction upload; do
  aws lambda invoke --function-name "address-lookup-ngd-${name}-cidev" \
    --cli-binary-format raw-in-base64-out \
    --payload '{"feed":"daily_cou"}' "/tmp/${name}.json" && cat "/tmp/${name}.json"
done
```

The event shape belongs to the Lambda code; change the payload if it differs.
