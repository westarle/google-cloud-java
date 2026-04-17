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

// [START java_observability_tracing]
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

public class SecretManagerTracing {
  public static void main(String[] args) throws Exception {
    // Configure the OTLP exporter
    OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder().build();

    // Set up the tracer provider
    SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
        .build();

    // Set up the global propagator
    OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .buildAndRegisterGlobal();

    // Initialize the client
    // Note: To configure this programmatically in java, you would also need to
    // include the `OpenTelemetryTracingFactory` via the client library settings.
    // e.g. .setTracerFactory(new OpenTelemetryTracingFactory(GlobalOpenTelemetry.get()))
    // However, the standard mechanism is to rely on environment variables and the Java Agent.
    SecretManagerServiceSettings settings = SecretManagerServiceSettings.newBuilder()
        .build();

    try (SecretManagerServiceClient client = SecretManagerServiceClient.create(settings)) {
      // Use the client
    } finally {
      tracerProvider.close();
    }
  }
}
// [END java_observability_tracing]