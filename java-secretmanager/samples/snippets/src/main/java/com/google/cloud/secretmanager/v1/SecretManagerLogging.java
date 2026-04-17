/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.secretmanager.v1;

// [START java_observability_logging]
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;

public class SecretManagerLogging {
  public static void main(String[] args) throws Exception {
    // When GOOGLE_SDK_JAVA_LOGGING=true is set, actionable error logs are
    // emitted at the DEBUG level.
    
    // Configure your logging framework (e.g. Logback) to output DEBUG level
    // logs to stdout as JSON.

    // Initialize the client
    SecretManagerServiceSettings settings = SecretManagerServiceSettings.newBuilder()
        .build();

    try (SecretManagerServiceClient client = SecretManagerServiceClient.create(settings)) {
      // Use the client
    }
  }
}
// [END java_observability_logging]