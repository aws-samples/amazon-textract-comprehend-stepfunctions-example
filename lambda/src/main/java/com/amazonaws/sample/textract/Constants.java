// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.sample.textract;

public class Constants {
    public static final String EnvSourceBucket = "EnvSourceBucket";
    public static final String EnvDestinationBucket = "EnvDestinationBucket";
    public static final String EnvTextractCompletionSNS = "EnvTextractCompletionSNS";
    public static final String EnvTextractCompletionRole = "EnvTextractCompletionRole";
    public static final String EnvComprehendClassifier = "EnvComprehendClassifier";
    public static final String OutDetectTextPrefix = "_detectText";
    public static final String OutTaskTokens = "_tasks";

    public static final String ComprehendArnKey = "ComprehendArn";

    private Constants () {}
}
