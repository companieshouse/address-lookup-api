# Discovery Lambda

Build target and Lambda runtime: Java 21.

## Overview
The *Discover Lambda* is responsible for querying the OS (Ordnance Survey) data package API, to download the Order Summary and ZIP File meta-data.  This is used to create a **Manifest* file. The manifest file is then saved in the S3 Bucket, and `DOWNLOAD` events, including the location of the manifest file, is published to the EventBridge, one for each dataset of interest, to be consumed by the *Download Lambda*.

Once an initial `FULL` download has been performed, a cursor file will be stored in the S3 Bucket at:

```control/address-release/active.json```

This holds the following value:

* `dataPackageId` - The Data Package ID of the last successful run, e.g. `"20780"`
* `lastProcessedVersionId` - The version ID for the last successful run, e.g. `"150035"`
* `lastProcessedDate` - the target date held in the data downloaded for the last successful run, e.g. `"2026-10-05"`
* 
and is updated with the values of the last successful execution of the Lambda.  For the lambda execution with *mode* `COU`, the next dataset URL is determined by getting the data package for the Data Package ID and Version ID specified in the current cursor and using the `nextVersionUrl` from that.

## Input Contract

The initial request placed on the Event Bridge, within the 'details' defines a 'mode' to run the Discovery Lambda in. There are two accepted modes:

* `FULL` - Where the full download data are retrieved, with a specified *data package* and *version id*. Sample request JSON:
```
{
  "detail": {
    "mode": "FULL",
    "versions": {
      "data_package": "24833",
      "version_id": "150450"
    }
  }
}
```
If either of the `data_package` or `version_id` are missing, an error will be returned.
* `COU` - Change-Only Update. This will bring back the download data for the next update.  The request JSON:
```
{
  "detail": {
    "mode": "COU"
  }
}
```

## Output Contract

A manifest file (see below) is generated from the Data Package data and the Order Summary data, currently for the datasets:
* `add_gb_royalmailaddress`
* `add_isl_royalmailaddress`

and is written to the S3 Bucket at:

```acquisitions/{target-date}/{release-id-calculated-from-manifest}.json```

e.g.,

```acquisitions/2026-10-06/12345678-1234-1234-1234-123456789abc.json```

The *Release ID* is immutable and is calculated by:

* Create a copy of the manifest JSON
* Remove the zip.url field from each dataset (keep other ZIP metadata)
* Serialize the modified manifest to UTF-8 JSON bytes (consistent field ordering)
* Calculate using `UUID.nameUUIDFromBytes(json.getBytes(StandardCharsets.UTF_8))` from these bytes to generate the Release ID.

Once the manifest is created successfully, for each dataset, a *command* is sent to the EventBridge `default` bus, of the form:

```
{
  "source": "os.acquisition",
  "detail-type": "DOWNLOAD",
  "detail": {
    "planKey": "acquisitions/<target>/<releaseId>.json",
    "dataset": "<dataset-name>" 
  }

```

The `<dataset-name>` will be one of `add_gb_royalmailaddress` or `add_isl_royalmailaddress`.

### Manifest format

The manifest file that is written to the S3 bucket will take the following form:

```
{
  "releaseId": "12345678-1234-1234-1234-123456789abc",
  "mode": "FULL" or "COU" as appropriate,
  "target": <Target Date, e.g. "2026-05-01">,
  "dataPackageId": "20780",
  "versionId": "130989",
  "add_gb_royalmailaddress": {
    "zip": {
      "fileName": "add_gb_royalmailaddress.zip",
      "url": "https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_gb_royalmailaddress.zip",
      "md5": "229c68b69090208861bc3fdf05009ef6"
    },
    "summary": {
      "featureName": "add-gb-royalmailaddress",
      "crs": "http://www.opengis.net/def/crs/EPSG/0/27700",
      "schemaVersion": "2.0",
      "validFromDate": null,
      "validToDate": "2026-05-01",
      "recordCount": 31324774
    }
  },
  "add_isl_royalmailaddress": {
    "zip": {
      "fileName": "add_isl_royalmailaddress.zip",
      "url": "https://api.os.uk/downloads/v1/dataPackages/20780/versions/130989/downloads?fileName=add_isl_royalmailaddress.zip",
      "md5": "d291d0b180c2d143fe65db4cb1980469"
    },
    "summary": {
      "featureName": "add-isl-royalmailaddress",
      "crs": "http://www.opengis.net/def/crs/EPSG/0/4258",
      "schemaVersion": "2.0",
      "validFromDate": null,
      "validToDate": "2026-05-01",
      "recordCount": 1035955
    }
  }
}
```

## Configuration

The following environment variables can be set for the Discovery Lambda

| Variable          | Proposed Value                       |
|-------------------|--------------------------------------|
| SOURCE_BUCKET     | (tbc)                                |
| SCANNED_BUCKET    | (tbc)                                |
| EVENT_BUS         | default                              |
| OS_API_KEY        | ************************************ |
| ROYAL_MAIL_SCHEMA | 2.0                                  |

## AWS IAM

The initial trust policy used to create the role applicable for the Discovery Lambda is:

```
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "Service": "lambda.amazonaws.com" },
      "Action": "sts:AssumeRole"
    }
  ]
}
```

The role then needs to have the Full Access policy for S3 to be added to the role to allow reading and writing from the S3 bucket:

    arn:aws:iam::aws:policy/AmazonS3FullAccess

A policy needs to be defined, and attached to the role, to allow the Lambda to output events to the event bridge, for the Download Lambda to consume:

```
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "EventBridgeActions",
            "Effect": "Allow",
            "Action": [
                "events:PutEvents"
            ],
            "Resource": "arn:aws:events:<aws_region>:<aws_account>:event-bus/<EVENT_BUS>"
        },
        {
            "Sid": "IAMPassRoleAccessForEventBridge",
            "Effect": "Allow",
            "Action": "iam:PassRole",
            "Resource": "arn:aws:iam::*:role/*",
            "Condition": {
                "StringLike": {
                    "iam:PassedToService": "events.amazonaws.com"
                }
            }
        }
    ]
}
```