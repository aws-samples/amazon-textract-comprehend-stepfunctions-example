// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.sample.textract;

import java.util.LinkedHashMap;
import java.util.Map;

public class GatewayResponse {
    final Map<String, String> headers = new LinkedHashMap<>();
    String body;
    int statusCode;

    public GatewayResponse(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getBody() {
        return body;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
