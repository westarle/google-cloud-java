/*
 * Copyright 2026 Google LLC
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google LLC nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.google.showcase.v1beta1.it;

import static org.junit.Assert.assertThrows;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.rpc.StatusCode;
import com.google.api.gax.rpc.UnavailableException;
import com.google.api.gax.tracing.SpanTracerFactory;
import com.google.rpc.Status;
import com.google.showcase.v1beta1.EchoClient;
import com.google.showcase.v1beta1.EchoRequest;
import com.google.showcase.v1beta1.EchoSettings;
import com.google.showcase.v1beta1.GetUserRequest;
import com.google.showcase.v1beta1.IdentityClient;
import com.google.showcase.v1beta1.it.util.TestClientInitializer;
import com.google.showcase.v1beta1.stub.EchoStub;
import com.google.showcase.v1beta1.stub.EchoStubSettings;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class ITOtelTracingIntegration {

  private OpenTelemetrySdk openTelemetrySdk;

  @BeforeEach
  void setup() {
    GlobalOpenTelemetry.resetForTest();
    Map<String, String> properties = new HashMap<>();
    properties.put("otel.exporter.otlp.endpoint", "https://telemetry.googleapis.com");
    properties.put("otel.traces.exporter", "otlp");
    properties.put("otel.metrics.exporter", "none");
    properties.put("otel.logs.exporter", "none");
    properties.put("otel.exporter.otlp.protocol", "grpc");
    
    openTelemetrySdk =
        AutoConfiguredOpenTelemetrySdk.builder()
            .addPropertiesSupplier(() -> properties)
            .build()
            .getOpenTelemetrySdk();
  }

  @AfterEach
  void tearDown() {
    if (openTelemetrySdk != null) {
      openTelemetrySdk.close();
    }
    GlobalOpenTelemetry.resetForTest();
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_CLOUD_PROJECT", matches = ".*")
  void testTracing_cloudIntegration_successfulIdentityGetUser_grpc() throws Exception {
    SpanTracerFactory tracingFactory = new SpanTracerFactory(openTelemetrySdk);

    try (IdentityClient client =
        TestClientInitializer.createGrpcIdentityClientOpentelemetry(tracingFactory)) {
      try {
        client.getUser(GetUserRequest.newBuilder().setName("users/test-user").build());
      } catch (Exception e) {}
      openTelemetrySdk.getSdkTracerProvider().forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_CLOUD_PROJECT", matches = ".*")
  void testTracing_cloudIntegration_successfulIdentityGetUser_httpjson() throws Exception {
    SpanTracerFactory tracingFactory = new SpanTracerFactory(openTelemetrySdk);

    try (IdentityClient client =
        TestClientInitializer.createHttpJsonIdentityClientOpentelemetry(tracingFactory)) {
      try {
        client.getUser(GetUserRequest.newBuilder().setName("users/test-user").build());
      } catch (Exception e) {}
      openTelemetrySdk.getSdkTracerProvider().forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_CLOUD_PROJECT", matches = ".*")
  void testTracing_cloudIntegration_serverError_grpc() throws Exception {
    SpanTracerFactory tracingFactory = new SpanTracerFactory(openTelemetrySdk);
    try (EchoClient client = TestClientInitializer.createGrpcEchoClientOpentelemetry(tracingFactory)) {
      EchoRequest echoRequest = EchoRequest.newBuilder()
          .setError(Status.newBuilder().setCode(StatusCode.Code.INTERNAL.ordinal()).build())
          .build();

      assertThrows(Exception.class, () -> client.echo(echoRequest));
      openTelemetrySdk.getSdkTracerProvider().forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_CLOUD_PROJECT", matches = ".*")
  void testTracing_cloudIntegration_serverError_httpjson() throws Exception {
    SpanTracerFactory tracingFactory = new SpanTracerFactory(openTelemetrySdk);
    try (EchoClient client = TestClientInitializer.createHttpJsonEchoClientOpentelemetry(tracingFactory)) {
      EchoRequest echoRequest = EchoRequest.newBuilder()
          .setError(Status.newBuilder().setCode(StatusCode.Code.INTERNAL.ordinal()).build())
          .build();

      assertThrows(Exception.class, () -> client.echo(echoRequest));
      openTelemetrySdk.getSdkTracerProvider().forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_CLOUD_PROJECT", matches = ".*")
  void testTracing_cloudIntegration_clientError_grpc() throws Exception {
    RetrySettings retrySettings =
        RetrySettings.newBuilder()
            .setTotalTimeout(org.threeten.bp.Duration.ofMillis(1L))
            .setMaxAttempts(1)
            .build();
    
    EchoStubSettings.Builder grpcEchoSettingsBuilder = EchoStubSettings.newBuilder();
    grpcEchoSettingsBuilder
        .echoSettings()
        .setRetrySettings(retrySettings);
    EchoSettings grpcEchoSettings = EchoSettings.create(grpcEchoSettingsBuilder.build());
    grpcEchoSettings =
        grpcEchoSettings.toBuilder()
            .setCredentialsProvider(NoCredentialsProvider.create())
            .setTransportChannelProvider(EchoSettings.defaultGrpcTransportProviderBuilder().build())
            .setEndpoint("localhost:12345")
            .build();

    SpanTracerFactory tracingFactory = new SpanTracerFactory(openTelemetrySdk);
    EchoStubSettings echoStubSettings = (EchoStubSettings) grpcEchoSettings.getStubSettings()
        .toBuilder().setTracerFactory(tracingFactory).build();
    EchoStub stub = echoStubSettings.createStub();
    EchoClient client = EchoClient.create(stub);

    EchoRequest echoRequest = EchoRequest.newBuilder().setContent("test").build();

    assertThrows(Exception.class, () -> client.echo(echoRequest));
    openTelemetrySdk.getSdkTracerProvider().forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_CLOUD_PROJECT", matches = ".*")
  void testTracing_cloudIntegration_clientError_httpjson() throws Exception {
    RetrySettings retrySettings =
        RetrySettings.newBuilder()
            .setTotalTimeout(org.threeten.bp.Duration.ofMillis(1L))
            .setMaxAttempts(1)
            .build();
    
    EchoStubSettings.Builder httpJsonEchoSettingsBuilder = EchoStubSettings.newHttpJsonBuilder();
    httpJsonEchoSettingsBuilder
        .echoSettings()
        .setRetrySettings(retrySettings);
    EchoSettings httpJsonEchoSettings = EchoSettings.create(httpJsonEchoSettingsBuilder.build());
    httpJsonEchoSettings =
        httpJsonEchoSettings.toBuilder()
            .setCredentialsProvider(NoCredentialsProvider.create())
            .setTransportChannelProvider(
                EchoSettings.defaultHttpJsonTransportProviderBuilder()
                    .setHttpTransport(
                        new NetHttpTransport.Builder().doNotValidateCertificate().build())
                    .setEndpoint("http://localhost:12345")
                    .build())
            .build();

    SpanTracerFactory tracingFactory = new SpanTracerFactory(openTelemetrySdk);
    EchoStubSettings echoStubSettings = (EchoStubSettings) httpJsonEchoSettings.getStubSettings()
        .toBuilder().setTracerFactory(tracingFactory).build();
    EchoStub stub = echoStubSettings.createStub();
    EchoClient client = EchoClient.create(stub);

    EchoRequest echoRequest = EchoRequest.newBuilder().setContent("test").build();

    assertThrows(Exception.class, () -> client.echo(echoRequest));
    openTelemetrySdk.getSdkTracerProvider().forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_CLOUD_PROJECT", matches = ".*")
  void testTracing_cloudIntegration_retry_grpc() throws Exception {
    final int attempts = 5;
    final StatusCode.Code statusCode = StatusCode.Code.UNAVAILABLE;
    RetrySettings retrySettings =
        RetrySettings.newBuilder()
            .setTotalTimeout(org.threeten.bp.Duration.ofMillis(5000L))
            .setMaxAttempts(attempts)
            .build();

    EchoStubSettings.Builder grpcEchoSettingsBuilder = EchoStubSettings.newBuilder();
    grpcEchoSettingsBuilder
        .echoSettings()
        .setRetrySettings(retrySettings)
        .setRetryableCodes(statusCode);
    EchoSettings grpcEchoSettings = EchoSettings.create(grpcEchoSettingsBuilder.build());
    grpcEchoSettings =
        grpcEchoSettings.toBuilder()
            .setCredentialsProvider(NoCredentialsProvider.create())
            .setTransportChannelProvider(EchoSettings.defaultGrpcTransportProviderBuilder().build())
            .setEndpoint("localhost:7469")
            .build();

    SpanTracerFactory tracingFactory = new SpanTracerFactory(openTelemetrySdk);

    EchoStubSettings echoStubSettings =
        (EchoStubSettings)
            grpcEchoSettings.getStubSettings().toBuilder().setTracerFactory(tracingFactory).build();
    EchoStub stub = echoStubSettings.createStub();
    EchoClient grpcClient = EchoClient.create(stub);

    EchoRequest echoRequest =
        EchoRequest.newBuilder()
            .setError(Status.newBuilder().setCode(statusCode.ordinal()).build())
            .build();

    assertThrows(UnavailableException.class, () -> grpcClient.echo(echoRequest));
    openTelemetrySdk.getSdkTracerProvider().forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "GOOGLE_CLOUD_PROJECT", matches = ".*")
  void testTracing_cloudIntegration_retry_httpjson() throws Exception {
    final int attempts = 5;
    final StatusCode.Code statusCode = StatusCode.Code.UNAVAILABLE;
    RetrySettings retrySettings =
        RetrySettings.newBuilder()
            .setTotalTimeout(org.threeten.bp.Duration.ofMillis(5000L))
            .setMaxAttempts(attempts)
            .build();

    EchoStubSettings.Builder httpJsonEchoSettingsBuilder = EchoStubSettings.newHttpJsonBuilder();
    httpJsonEchoSettingsBuilder
        .echoSettings()
        .setRetrySettings(retrySettings)
        .setRetryableCodes(statusCode);
    EchoSettings httpJsonEchoSettings = EchoSettings.create(httpJsonEchoSettingsBuilder.build());
    httpJsonEchoSettings =
        httpJsonEchoSettings.toBuilder()
            .setCredentialsProvider(NoCredentialsProvider.create())
            .setTransportChannelProvider(
                EchoSettings.defaultHttpJsonTransportProviderBuilder()
                    .setHttpTransport(
                        new NetHttpTransport.Builder().doNotValidateCertificate().build())
                    .setEndpoint("http://localhost:7469")
                    .build())
            .build();

    SpanTracerFactory tracingFactory = new SpanTracerFactory(openTelemetrySdk);

    EchoStubSettings echoStubSettings =
        (EchoStubSettings)
            httpJsonEchoSettings.getStubSettings().toBuilder()
                .setTracerFactory(tracingFactory)
                .build();
    EchoStub stub = echoStubSettings.createStub();
    EchoClient httpClient = EchoClient.create(stub);

    EchoRequest echoRequest =
        EchoRequest.newBuilder()
            .setError(Status.newBuilder().setCode(statusCode.ordinal()).build())
            .build();

    assertThrows(UnavailableException.class, () -> httpClient.echo(echoRequest));
    openTelemetrySdk.getSdkTracerProvider().forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
  }
}