// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.sample.textract;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.stepfunctions.AWSStepFunctions;
import com.amazonaws.services.stepfunctions.AWSStepFunctionsClientBuilder;
import com.amazonaws.services.stepfunctions.model.SendTaskFailureRequest;
import com.amazonaws.services.stepfunctions.model.SendTaskSuccessRequest;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * This will be triggered when Textract notifies to the SNS topic
 */
public class TextractCompletionListener implements RequestHandler<Map<String, Object>, GatewayResponse> {
    private static final com.google.gson.Gson Gson = new GsonBuilder().create();
    private static final AtomicInteger requestCount = new AtomicInteger(0);
    private static final AWSStepFunctions step = AWSStepFunctionsClientBuilder.defaultClient();
    private static final AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();

    static {
        System.out.println("TextractCompletionListener cold booting");
    }

    static String readObject(String bucket, String key, LambdaLogger logger) {
        logger.log("S3 Reading " + bucket + "/" + key);
        S3Object object = s3.getObject(bucket, key);
        InputStream input = object.getObjectContent();

        try (input) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            return result.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Error reading object " + bucket + "/" + key, e); // blow up the function
        }
    }

    @Override
    public GatewayResponse handleRequest(Map<String, Object> input, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("Req#" + requestCount.incrementAndGet());
        String inputJson = Gson.toJson(input); // vs parsing directly, allows logging for debugging of struct
        logger.log(inputJson);
        GatewayResponse res = new GatewayResponse(200);

        Input snsEvent = Gson.fromJson(inputJson, Input.class);

        for (Input.Event record : snsEvent.Records) {// theoretically could have multiple events here
            TextractEvent event = Gson.fromJson(record.Sns.Message, TextractEvent.class);
            String bucket = event.DocumentLocation.S3Bucket; // original bucket and key
            String key = event.DocumentLocation.S3ObjectName;
            logger.log("Completing for " + bucket + "/" + key);

            // we saved the step continuation token in the output bucket:
            String taskToken = readObject(System.getenv(Constants.EnvDestinationBucket), StartTextractFunction.getTaskTokenPath(key), logger);
            logger.log("Task token: " + taskToken);

            if (event.Status.equals("SUCCEEDED")) {
                logger.log("Success, resuming task");

                step.sendTaskSuccess(new SendTaskSuccessRequest()
                    .withTaskToken(taskToken)
                    .withOutput(record.Sns.Message));
            } else {
                logger.log("Failed with " + event.Status + ", failing task");
                step.sendTaskFailure(new SendTaskFailureRequest()
                    .withTaskToken(taskToken)
                    .withError(event.Status)
                    .withCause(record.Sns.Message));
            }
        }
        res.body = inputJson;
        return res;
    }

    // Structs to parse input into
    static final class Input {
        List<Event> Records;

        static final class Event {
            String EventSource;
            SnsJson Sns;
        }

        static final class SnsJson {
            String Message; // this will be json text of what Textract posted
        }
    }

    static final class TextractEvent {
        String JobId;
        String Status;
        String API;
        String JobTag;
        JsonDocLoc DocumentLocation;

        static final class JsonDocLoc {
            String S3ObjectName;
            String S3Bucket;
        }
    }
}
