// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.sample.textract;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.textract.AmazonTextract;
import com.amazonaws.services.textract.AmazonTextractClientBuilder;
import com.amazonaws.services.textract.model.DocumentLocation;
import com.amazonaws.services.textract.model.NotificationChannel;
import com.amazonaws.services.textract.model.OutputConfig;
import com.amazonaws.services.textract.model.S3Object;
import com.amazonaws.services.textract.model.StartDocumentAnalysisRequest;
import com.amazonaws.services.textract.model.StartDocumentAnalysisResult;
import com.amazonaws.services.textract.model.StartDocumentTextDetectionRequest;
import com.amazonaws.services.textract.model.StartDocumentTextDetectionResult;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Given an s3.bucket.name and s3.object.key as parameters to a source, will initiate a Textract call if it's a PDF, JPEG, or PNG (supported file types).
 */
public class StartTextractFunction implements RequestHandler<Map<String, Object>, GatewayResponse> {
    private static final com.google.gson.Gson Gson = new GsonBuilder().create();
    private final static AtomicInteger requestCount = new AtomicInteger(0);
    private final static Set<String> supportedTypes = Set.of("pdf", "png", "jpg", "jpeg");

    static {
        System.out.println("StartTextractFunction cold booting");
    }

    static String getExtension(String fn) {
        int pos = fn.lastIndexOf('.');
        if (pos > 0 && fn.length() > pos)
            return fn.substring(pos + 1).toLowerCase(Locale.ROOT);

        return null;
    }

    static boolean isSupportedFiletype(String filename) {
        String extension = getExtension(filename);
        return (extension != null && supportedTypes.contains(extension));
    }

    static final String getTaskTokenPath(String key) {
        return Constants.OutTaskTokens + "/" + key + ".taskToken";
    }

    @Override
    public GatewayResponse handleRequest(Map<String, Object> input, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("StartTextractFunction req#" + requestCount.incrementAndGet());
        String inputJson = Gson.toJson(input);
        logger.log(inputJson);

        AmazonTextract textract = AmazonTextractClientBuilder.defaultClient();
        AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
        String jobId = null;

        // the notification event supports multiple records -- in practice only ever seen one, so process that
        Input event = Gson.fromJson(inputJson, Input.class); // blow up here if an unexpected input

        for (S3Record record : event.input.records) {
            String requestId = record.responseElements.requestId;
            String bucket = record.s3.bucket.name;
            String key = record.s3.object.key;
            key = key.replace('+', ' '); // spaces in filename are getting changed to + in the event.
            String version = record.s3.object.versionId;

            if (!isSupportedFiletype(key)) {
                logger.log("Unsupported file type on object, will not process - " + key);
                continue;
            }

            // Save the Step continuation token to s3, so we can resume once processing is complete
            // Unfortunately the Textract JobTag is only 64 chars max, but the token is  ~ 670, otherwise could use the JobTag slot
            String outputBucket = System.getenv(Constants.EnvDestinationBucket);
            s3.putObject(outputBucket, getTaskTokenPath(key), event.token);

            if (event.feature.equals("TEXT")) {
                // plain text detection (no common interface between analysis and detect text)
                // could create an interface and two classes which extend these classes and implement common interface..
                StartDocumentTextDetectionRequest request = new StartDocumentTextDetectionRequest()
                    .withClientRequestToken(requestId) // the request ID from the original upload, used as idempotent job id
                    .withDocumentLocation(new DocumentLocation().withS3Object(new S3Object()
                        .withBucket(bucket)
                        .withName(key)
                        .withVersion(version)
                    ))
                    .withOutputConfig(new OutputConfig()
                        .withS3Bucket(outputBucket)
                        .withS3Prefix(System.getenv(Constants.OutDetectTextPrefix))
                    )
                    .withNotificationChannel(new NotificationChannel()
                        .withSNSTopicArn(System.getenv(Constants.EnvTextractCompletionSNS))
                        .withRoleArn(System.getenv(Constants.EnvTextractCompletionRole))
                    );

                logger.log(request.toString());
                StartDocumentTextDetectionResult detection = textract.startDocumentTextDetection(request);
                logger.log("Started with jobId " + detection.getJobId());
                if (jobId == null) jobId = detection.getJobId();
            } else {
                // FORMS or TABLES
                // plain text detection (no common interface between analysis and detect text)
                StartDocumentAnalysisRequest request = new StartDocumentAnalysisRequest()
                    .withFeatureTypes(event.feature)
                    .withClientRequestToken(requestId) // the request ID from the original upload, used as idempotent job id
                    .withDocumentLocation(new DocumentLocation().withS3Object(new S3Object()
                        .withBucket(bucket)
                        .withName(key)
                        .withVersion(version)
                    ))
                    .withOutputConfig(new OutputConfig()
                        .withS3Bucket(outputBucket)
                        .withS3Prefix(System.getenv(Constants.OutDetectTextPrefix))
                    )
                    .withNotificationChannel(new NotificationChannel()
                        .withSNSTopicArn(System.getenv(Constants.EnvTextractCompletionSNS))
                        .withRoleArn(System.getenv(Constants.EnvTextractCompletionRole))
                    );

                logger.log(request.toString());
                StartDocumentAnalysisResult detection = textract.startDocumentAnalysis(request);
                logger.log("Started with jobId " + detection.getJobId());
                if (jobId == null) jobId = detection.getJobId();
            }
        }

        GatewayResponse res = new GatewayResponse(200);
        res.body = jobId;

        return res;
    }

    // model the input (another approach would be to use json path, might read a bit more simply
    static final class Input {
        String token; // the restart task token
        String feature; // FORMS, TABLES, TEXT
        S3Event input;
    }

    static final class S3Event {
        @SerializedName("Records")
        List<S3Record> records;
    }

    static final class S3Record {
        String eventTime;
        String eventName;
        S3Detail s3;
        S3Response responseElements;

        static final class S3Response {
            @SerializedName("x-amz-request-id")
            String requestId;
        }

        static final class S3Detail {
            S3Bucket bucket;
            EventObject object;
        }

        static final class S3Bucket {
            String name;
        }

        static final class EventObject {
            String key;
            String versionId;
        }
    }



    /*

    This function is called by StepFunctions (triggered by an upload), and the input will be the s3 notification:

    {
  "Records": [
    {
      "eventVersion": "2.1",
      "eventSource": "aws:s3",
      "awsRegion": "ap-southeast-2",
      "eventTime": "2021-01-20T01:21:20.706Z",
      "eventName": "ObjectCreated:Put",
      "userIdentity": {
        "principalId": ""
      },
      "requestParameters": {
        "sourceIPAddress": ""
      },
      "responseElements": {
        "x-amz-request-id": "001C146068AAF482",
        "x-amz-id-2": "Q/IJEOisirXzIXqNsL5k1ngty1eBim87yI5MOIrWbFUQp5XNZcaF5bmdn7Vmtr3jmzJcYQgpBbsZAMiL2xsm93ZFFBAsjSqmF2Quzee0YcE="
      },
      "s3": {
        "s3SchemaVersion": "1.0",
        "configurationId": "ODc4MTY2MmItMTRiYS00MjhmLWFmODktODk4ZTM2MDNhNDE4",
        "bucket": {
          "name": "textract-comprehend-sample-sourceadfc1803-qru1thv7hhp0",
          "ownerIdentity": {
            "principalId": ""
          },
          "arn": "arn:aws:s3:::textract-comprehend-sample-sourceadfc1803-qru1thv7hhp0"
        },
        "object": {
          "key": "the/uploaded/file.txt",
          "size": 112,
          "eTag": "bef165c77ab4c882255b4ddd51f8b6c6",
          "versionId": "nhOaKi2BxpP8zrjFCu0rj5DpArcCraaI",
          "sequencer": "00600785939B85710E"
        }
      }
    }
  ]
}

     */
}
