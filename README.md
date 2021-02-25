# AWS Sample: Textract and Comprehend Integration via Step Functions and the CDK

This repository is a sample guide to building a serverless document processing application that can make intelligent flow-control decisions after classifying the input document type.

A video presentation of the architecture and a demo is available in my [AWS Innovate AI/ML Edition 2021](https://aws.amazon.com/events/aws-innovate/machine-learning/) talk.

## Getting Started

These prequisites should be installed first:

1. Install and configure [**Apache Maven**](https://maven.apache.org/)
2. Install and configure the [**AWS Cloud Development Kit**](https://docs.aws.amazon.com/cdk/latest/guide/getting_started.html)
3. Install and configure [**git**](https://git-scm.com/downloads)
4. Install the [**JDK**](https://openjdk.java.net/)

Once those are installed, clone this repository:

`git clone https://github.com/aws-samples/amazon-textract-comprehend-stepfunctions-example`

Then import the project into your IDE, and review the sample code. There are two models, *cdk* and *lambda*. The *cdk* module contains the application and infrastructure CDK code. It is responsible for building and deploying the serverless Lambda functions. It defines the Step Functions flow.

The *lambda* module contains the application runtime code as Lambda functions. These functions show examples of calling extracting a single page from a PDF and calling Textract synchronously, classifying it's content using a Comprehend custom classifier, and an asynchronous Textract call with an AWS SNS ping on completion. The initial flow is triggered by an upload to S3 which starts a Step Functions execution.

## Train a sample classifier

Before deploying the sample, you will need to train a Comprehend classifier. A very simple reference example is located in `/samples/trainer.csv`. (It contains the plain text of three sample documents, one per row; in practice you will train with many more samples each.) To train the classifier:

1. Visit the [Comprehend console](https://console.aws.amazon.com/comprehend/v2/home#classification) (switch to the region you wish to use)
2. Click *Train classifier*
3. Give it a name and check the other details (the defaults are fine to start - use a Multi-class classifier)
4. Specify the S3 location of the training file (upload it first)
5. Click *Train classifier*

Once it's trained (it will take a few minutes), start an endpoint by clicking *Create endpoint* from the classifier's console page. (You can also use batch mode classification which does not require a running endpoint, but is not available for real-time processing.) Note the ARN of the classifier.

## Build the project and build the CDK

From the project root directory, run the command: `mvn install` to compile the project and create the deployable Lambda artefacts. This will download all of the project dependencies first, so the initial run may take a few minutes. You should re-run this command each time you make an application change.

Once that is successful, you will need to [bootstrap](https://docs.aws.amazon.com/cdk/latest/guide/bootstrapping.html) the CDK environment:

```cdk bootstrap --profile YOUR_AWS_CLI_PROFILE_HERE```

Make sure to update the command with the relevant Comprehend ARN and [AWS CLI profile](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-profiles.html).

## Permissions

The CDK application needs some permissions to be able to create the stack. Visit the [IAM console](https://console.aws.amazon.com/iam/home) to set these up. You can run the *CDK deploy* step iteratively and correct any missing permissions. I defined a Group with these policies and set that to a permission boundary restricted User on an account (to allow it only to self-created resources). You may restrict these further as appropriate.

1. AmazonComprehendServicePolicy-InnovateClassifier
2. AWSLambdaFullAccess
3. IAMFullAccess
4. AmazonS3FullAccess
5. AmazonTextractFullAccess
6. AmazonSNSFullAccess
7. AWSStepFunctionsFullAccess
8. AWSCloudFormationFullAccess

Note that these are only build-time permissions, required when running the CDK. The run-time permissions of the application are configured (and more restricted) in the CDK stack.

## Deploy the application

Once that's done, run the command:

``` cdk --context ComprehendArn=YOUR_COMPREHEND_ARN_HERE --profile YOUR_AWS_CLI_PROFILE_HERE deploy```

Once completed, you'll see a resource stack like:

```
textract-comprehend-sample: deploying...                                                                                                  
[0%] start: Publishing 12b0d822082799daf4b7651c9ab809985e721f2575d572943da86f53a055f29b:current                                           
[100%] success: Published 12b0d822082799daf4b7651c9ab809985e721f2575d572943da86f53a055f29b:current                                        
textract-comprehend-sample: creating CloudFormation changeset...                                                                          
  0/11 | 12:53:56 pm | UPDATE_IN_PROGRESS   | AWS::CloudFormation::Stack       | textract-comprehend-sample User Initiated                
 0/11 Currently in progress: textract-comprehend-sample                                                                                   
  2/11 | 12:54:35 pm | UPDATE_IN_PROGRESS   | AWS::Lambda::Function            | FirstPageFunction (FirstPageFunctionBEE9EA11)            
  2/11 | 12:54:35 pm | UPDATE_IN_PROGRESS   | AWS::Lambda::Function            | StartTextractFunction (StartTextractFunctionCD0185FF)    
  2/11 | 12:54:36 pm | UPDATE_COMPLETE      | AWS::Lambda::Function            | FirstPageFunction (FirstPageFunctionBEE9EA11)            
  2/11 | 12:54:36 pm | UPDATE_COMPLETE      | AWS::Lambda::Function            | StartTextractFunction (StartTextractFunctionCD0185FF)    
  3/11 | 12:54:39 pm | UPDATE_IN_PROGRESS   | AWS::StepFunctions::StateMachine | UploadFlow (UploadFlow6C932FD7)                          
  3/11 | 12:54:41 pm | UPDATE_COMPLETE      | AWS::StepFunctions::StateMachine | UploadFlow (UploadFlow6C932FD7)                          
  5/11 | 12:54:45 pm | UPDATE_IN_PROGRESS   | AWS::Lambda::Function            | S3UploadListener (S3UploadListener4E242122)              
  5/11 | 12:54:45 pm | UPDATE_IN_PROGRESS   | AWS::Lambda::Function            | TextractCompletion (TextractCompletion5AE7AEDD)          
  5/11 | 12:54:46 pm | UPDATE_COMPLETE      | AWS::Lambda::Function            | S3UploadListener (S3UploadListener4E242122)              
  5/11 | 12:54:47 pm | UPDATE_COMPLETE      | AWS::Lambda::Function            | TextractCompletion (TextractCompletion5AE7AEDD)          
  6/11 | 12:54:49 pm | UPDATE_COMPLETE_CLEA | AWS::CloudFormation::Stack       | textract-comprehend-sample                               
  6/11 | 12:54:50 pm | UPDATE_COMPLETE      | AWS::CloudFormation::Stack       | textract-comprehend-sample                               
                                                                                                                                          
 ✅  textract-comprehend-sample                                                                                                            
```

## Running the samples

After deployment, visit the deployed stack in the [CloudFormation console](https://console.aws.amazon.com/cloudformation/home). Check the different tabs for details on the stack including the different resources created. 

When you upload a PDF (example are in the *samples* directory) to the *source* S3 bucket that was created, if everything was set up correctly, a [Step Functions](https://console.aws.amazon.com/states/home) flow will be started. View that to see the flow of the document and the Lambda logs.

Congratulations! You have successfully created a scalable, serverless application stack using the CDK to intelligenly process documents on demand.

## Resources and pricing

Note that for as long as you have the stack deployed, charges may apply to your account. You should delete the resources (using `cdk destroy`) when you are done with the sample. You will need to empty the buckets prior to deletion, and also you will need to terminate the Comprehend endpoint.
