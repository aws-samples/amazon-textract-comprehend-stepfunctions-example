// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.sample.textract;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.stepfunctions.AWSStepFunctions;
import com.amazonaws.services.stepfunctions.AWSStepFunctionsClientBuilder;
import com.amazonaws.services.stepfunctions.model.StartExecutionRequest;
import com.amazonaws.services.stepfunctions.model.StartExecutionResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gets called via s3 trigger on uploads; used to start the SF execution
 */
public class S3UploadListener implements RequestHandler<Map<String, Object>, GatewayResponse> {
    public static final String UploadStepFunctionArnKey = "UploadStepFunctionArn";
    private static final AtomicInteger requestCount = new AtomicInteger(0);
    private static final Gson Gson = new GsonBuilder().create();

    static {
        System.out.println("S3UploadListener cold booting");
    }

    @Override
    public GatewayResponse handleRequest(Map<String, Object> input, Context context) {
        LambdaLogger logger = context.getLogger();
        String stateMachineArn = System.getenv(UploadStepFunctionArnKey);
        logger.log("S3UploadListener req#" + requestCount.incrementAndGet() + ", starting ARN" + stateMachineArn);
        String inputJson = Gson.toJson(input); // we pass the input direct to the SF
        logger.log(inputJson);

        AWSStepFunctions client = AWSStepFunctionsClientBuilder.defaultClient();
        StartExecutionResult execution = client.startExecution(
            new StartExecutionRequest()
                .withStateMachineArn(stateMachineArn)
                .withName(context.getAwsRequestId()) // Names are unique and used for idempotency, so using the request ID as that is reused on retries
                .withInput(inputJson)
        );

        logger.log("Started execution: " + execution.getExecutionArn());

        GatewayResponse res = new GatewayResponse(200);
        res.body = execution.getExecutionArn();

        return res;
    }
}
