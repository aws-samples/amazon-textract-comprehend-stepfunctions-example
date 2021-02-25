// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.sample;

import software.amazon.awscdk.core.App;

public class TextractComprehendApp {
    public static void main(final String[] args) {
        System.out.println("Building App");

        App app = new App();
        new TextractComprehendStack(app, "textract-comprehend-sample");
        app.synth();
    }
}
