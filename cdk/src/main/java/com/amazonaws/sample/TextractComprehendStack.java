// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.sample;

import com.amazonaws.sample.textract.ClassifyFirstPageFunction;
import com.amazonaws.sample.textract.Constants;
import com.amazonaws.sample.textract.S3UploadListener;
import com.amazonaws.sample.textract.StartTextractFunction;
import com.amazonaws.sample.textract.TextractCompletionListener;
import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.core.RemovalPolicy;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.IManagedPolicy;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.PolicyStatementProps;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.RoleProps;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketProps;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.TopicProps;
import software.amazon.awscdk.services.sns.subscriptions.LambdaSubscription;
import software.amazon.awscdk.services.stepfunctions.Chain;
import software.amazon.awscdk.services.stepfunctions.Choice;
import software.amazon.awscdk.services.stepfunctions.Condition;
import software.amazon.awscdk.services.stepfunctions.IntegrationPattern;
import software.amazon.awscdk.services.stepfunctions.JsonPath;
import software.amazon.awscdk.services.stepfunctions.StateMachine;
import software.amazon.awscdk.services.stepfunctions.StateMachineProps;
import software.amazon.awscdk.services.stepfunctions.Succeed;
import software.amazon.awscdk.services.stepfunctions.TaskInput;
import software.amazon.awscdk.services.stepfunctions.tasks.LambdaInvoke;
import software.amazon.awscdk.services.stepfunctions.tasks.LambdaInvokeProps;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.amazonaws.sample.textract.ClassifyFirstPageFunction.DocumentType.*;
import static com.amazonaws.sample.textract.Constants.*;

@SuppressWarnings("ConstantConditions")
public class TextractComprehendStack extends Stack {
    public TextractComprehendStack(final Construct parent, final String name) {
        super(parent, name);

        // Set up a source and an output bucket
        Bucket srcBucket = new Bucket(this, "source", BucketProps.builder()
            .versioned(true)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build());
        Bucket outBucket = new Bucket(this, "output", BucketProps.builder()
            .removalPolicy(RemovalPolicy.DESTROY)
            .build());

        Map<String, String> lambdaEnv = new HashMap<>();
        lambdaEnv.put(EnvSourceBucket, srcBucket.getBucketName());
        lambdaEnv.put(EnvDestinationBucket, outBucket.getBucketName());

        IManagedPolicy policyTextract = ManagedPolicy.fromAwsManagedPolicyName("AmazonTextractFullAccess");
        IManagedPolicy policyComprehend = ManagedPolicy.fromAwsManagedPolicyName("ComprehendReadOnly");

        // Get the Comprehend Endpoint ARN (no CDK resource, so must be set via local environment)
        String comprehendArn = (String) getNode().tryGetContext(ComprehendArnKey);
        if (comprehendArn == null || comprehendArn.equals("undefined"))
            throw new IllegalArgumentException("Must set cdk --context " + ComprehendArnKey + "= to the trained Comprehend endpoint");

        // A function to do the first pass on an upload - take first page from PDF, Textract it, classify it,
        // and return the result for Step Task Choice
        Function firstPageFunction = new Function(this, "FirstPageFunction",
            defaultFunction(lambdaEnv, ClassifyFirstPageFunction.class)
                .reservedConcurrentExecutions(10) // concurrency limit, set around the Textract DetectText default and room for Comprehend also. Configure as required.
                .build());
        firstPageFunction.addEnvironment(EnvComprehendClassifier, comprehendArn);
        firstPageFunction.getRole().addManagedPolicy(policyTextract);
        firstPageFunction.getRole().addManagedPolicy(policyComprehend);
        srcBucket.grantRead(firstPageFunction);

        // A function to start the full, async Textract detect text.
        Function textractFunction = new Function(this, "StartTextractFunction",
            defaultFunction(lambdaEnv, StartTextractFunction.class)
                .reservedConcurrentExecutions(10) // Limit concurrency to around the Start* concurrency level, so in the case of a document surge, events will buffer ahead. https://docs.aws.amazon.com/general/latest/gr/textract.html - configure as required
                .build());
        textractFunction.getRole().addManagedPolicy(policyTextract);
        srcBucket.grantRead(textractFunction);
        outBucket.grantReadWrite(textractFunction);

        // SNS topic to write to when that job is complete:
        Topic snsTopic = new Topic(this, "CompletionTopic", TopicProps.builder().build());
        textractFunction.addEnvironment(Constants.EnvTextractCompletionSNS, snsTopic.getTopicArn()); // Tell Textract where to post completion to

        // permissions for Textract to SNS - https://docs.aws.amazon.com/textract/latest/dg/api-async-roles.html#api-async-roles-all-topics
        Role textractSNSRole = new Role(this, "TextractSNSRole", RoleProps.builder()
            .assumedBy(new ServicePrincipal("textract.amazonaws.com"))
            .build());
        textractSNSRole.addToPolicy(new PolicyStatement(PolicyStatementProps.builder()
            .effect(Effect.ALLOW)
            .resources(List.of("*"))
            .actions(List.of("sts:AssumeRole"))
            .build()));
        textractSNSRole.addToPolicy(new PolicyStatement(PolicyStatementProps.builder()
            .effect(Effect.ALLOW)
            .resources(List.of(snsTopic.getTopicArn()))
            .actions(List.of("sns:Publish"))
            .build()));
        textractFunction.addEnvironment(Constants.EnvTextractCompletionRole, textractSNSRole.getRoleArn()); // Let Textract know the role to use

        // A listener on that topic for completions, will call to resume the task:
        Function textractCompletionListener = new Function(this, "TextractCompletion",
            defaultFunction(lambdaEnv, TextractCompletionListener.class).build());
        snsTopic.addSubscription(new LambdaSubscription(textractCompletionListener));
        outBucket.grantReadWrite(textractCompletionListener); // read task token from s3

        // Define the Step Function flow for when new uploads arrive:
        LambdaInvoke taskHandleNewUpload = new LambdaInvoke(this, "ClassifyFirstPage",
            LambdaInvokeProps.builder()
                .lambdaFunction(firstPageFunction)
                .payload(TaskInput.fromObject(Map.of(
                    "input", JsonPath.getEntirePayload()
                )))
                .resultPath("$.classification") // adds the result to the original input, and pass all to next stage
                .build());

        // Step Function Flow - Full Textract pass (multipage)
        Succeed complete = new Succeed(this, "Completed");
        Chain taskTextractForms = createTextractTask("TextractForms", "FORMS", textractFunction).next(complete);
        Chain taskTextractTables = createTextractTask("TextractTables", "TABLES", textractFunction).next(complete);
        Chain taskTextractText = createTextractTask("TextractText", "TEXT", textractFunction).next(complete);

        // Choice of next step - which Textract feature, or complete execution
        String classificationPath = "$.classification.Payload.documentClassification"; // loc in output path
        Choice doctypeChoice = Choice.Builder.create(this, "DocttypeChoice").build();
        doctypeChoice.when(
            Condition.stringEquals(classificationPath, APPLICATION.name()),
            taskTextractForms);
        doctypeChoice.when(
            Condition.stringEquals(classificationPath, BANK.name()),
            taskTextractTables);
        doctypeChoice.when(
            Condition.stringEquals(classificationPath, PAYSLIP.name()),
            taskTextractTables);
        doctypeChoice.when(
            Condition.stringEquals(classificationPath, UNKNOWN.name()),
            taskTextractText);
        doctypeChoice.otherwise(new Succeed(this, "Skipped"));

        // Flow definition
        Chain processingChain = taskHandleNewUpload // classify
            .next(doctypeChoice); // textract based on classification

        StateMachine processingState = new StateMachine(this, "UploadFlow", StateMachineProps.builder()
            .definition(processingChain)
            .tracingEnabled(true)
            .build());

        // Create a Lambda that will hear about new source uploads, and start the above upload-flow function
        lambdaEnv.put(S3UploadListener.UploadStepFunctionArnKey, processingState.getStateMachineArn());
        Function newSourceFunction = new Function(this, S3UploadListener.class.getSimpleName(),
            defaultFunction(lambdaEnv, S3UploadListener.class).build());
        srcBucket.addEventNotification(EventType.OBJECT_CREATED, new LambdaDestination(newSourceFunction));

        processingState.grantStartExecution(newSourceFunction);
        processingState.grantTaskResponse(textractCompletionListener);
    }

    private LambdaInvoke createTextractTask(String taskName, String feature, Function textractFunction) {
        return new LambdaInvoke(this, taskName,
            LambdaInvokeProps.builder()
                .lambdaFunction(textractFunction)
                .integrationPattern(IntegrationPattern.WAIT_FOR_TASK_TOKEN)
                .heartbeat(Duration.days(1)) // abort if linger too long
                .payload(TaskInput.fromObject(Map.of(
                    "feature", feature,
                    "token", JsonPath.getTaskToken(), // the token to use later to restart
                    "input", JsonPath.getEntirePayload()
                )))
                .build());
    }

    private FunctionProps.Builder defaultFunction(Map<String, String> lambdaEnvMap, Class handler) {
        return FunctionProps.builder()
            .code(Code.fromAsset("./lambda/target/lambda-1.0.0-jar-with-dependencies.jar"))
            .handler(handler.getName())
            .runtime(Runtime.JAVA_11)
            .environment(lambdaEnvMap)
            .timeout(Duration.seconds(60))
            //.logRetention(RetentionDays.ONE_MONTH) // errors on custom handler from CDK
            .memorySize(512);
    }
}
