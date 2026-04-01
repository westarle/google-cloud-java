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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.rpc.StatusCode;
import com.google.api.gax.rpc.UnavailableException;
import com.google.rpc.Status;
import com.google.showcase.v1beta1.EchoClient;
import com.google.showcase.v1beta1.EchoRequest;
import com.google.showcase.v1beta1.EchoSettings;
import com.google.showcase.v1beta1.it.util.TestClientInitializer;
import com.google.showcase.v1beta1.stub.EchoStub;
import com.google.showcase.v1beta1.stub.EchoStubSettings;
import io.grpc.ManagedChannelBuilder;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ITOtelLogging {
  private static final String SHOWCASE_SERVER_ADDRESS = "localhost";
  private static final long SHOWCASE_SERVER_PORT = 7469;
  private static final String SHOWCASE_GRPC_ENDPOINT =
      String.format("%s:%s", SHOWCASE_SERVER_ADDRESS, SHOWCASE_SERVER_PORT);
  private static final String SHOWCASE_HTTPJSON_ENDPOINT =
      String.format("http://%s:%s", SHOWCASE_SERVER_ADDRESS, SHOWCASE_SERVER_PORT);

  private InMemoryLogRecordExporter logExporter;
  private OpenTelemetrySdk openTelemetrySdk;

  @BeforeEach
  void setup() {
    logExporter = InMemoryLogRecordExporter.create();

    SdkLoggerProvider loggerProvider =
        SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter))
            .build();

    openTelemetrySdk =
        OpenTelemetrySdk.builder().setLoggerProvider(loggerProvider).buildAndRegisterGlobal();
  }

  @AfterEach
  void tearDown() {
    if (openTelemetrySdk != null) {
      openTelemetrySdk.close();
    }
    GlobalOpenTelemetry.resetForTest();
  }

  @Test
  void testLogging_disabled_grpc() throws Exception {
    try (EchoClient client = TestClientInitializer.createGrpcEchoClient()) {
      try {
        client.echo(EchoRequest.newBuilder().setContent("logging-test").build());
      } catch (Exception e) {}
      List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
      assertThat(logs).isEmpty(); // F3.1
    }
  }

  @Test
  void testLogging_disabled_httpjson() throws Exception {
    try (EchoClient client = TestClientInitializer.createHttpJsonEchoClient()) {
      try {
        client.echo(EchoRequest.newBuilder().setContent("logging-test").build());
      } catch (Exception e) {}
      List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
      assertThat(logs).isEmpty(); // F3.1
    }
  }

  @Test
  void testLogging_success_noL4Log_grpc() throws Exception {
    EchoSettings grpcEchoSettings = createEchoSettings(false);
    EchoStub stub = createStubWithServiceName(grpcEchoSettings);
    try (EchoClient client = EchoClient.create(stub)) {
      try {
        client.echo(EchoRequest.newBuilder().setContent("logging-test").build());
      } catch (Exception e) {}
      
      List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
      // Ensure no debug log is emitted for successful requests
      // TODO: F3.3 assert no log matches the L4 debug schema
    }
  }

  @Test
  void testLogging_success_noL4Log_httpjson() throws Exception {
    EchoSettings httpJsonEchoSettings = createEchoSettings(true);
    EchoStub stub = createStubWithServiceName(httpJsonEchoSettings);
    try (EchoClient client = EchoClient.create(stub)) {
      try {
        client.echo(EchoRequest.newBuilder().setContent("logging-test").build());
      } catch (Exception e) {}

      List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
      // Ensure no debug log is emitted for successful requests
      // TODO: F3.3 assert no log matches the L4 debug schema
    }
  }

  @Test
  void testLogging_clientError_grpc() throws Exception {
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

    EchoStubSettings echoStubSettings = (EchoStubSettings) grpcEchoSettings.getStubSettings();
    EchoStub stub = new ExtendedEchoStubSettings(echoStubSettings.toBuilder()).createStub();
    EchoClient client = EchoClient.create(stub);

    EchoRequest echoRequest = EchoRequest.newBuilder().setContent("test").build();

    try {
      client.echo(echoRequest);
    } catch (Exception e) {}

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    // F3.4
    // TODO: assert logs isNotEmpty once logging is implemented
    // if (!logs.isEmpty()) {
    //   LogRecordData log = logs.get(0);
    //   assertThat(log.getSeverity().name()).isEqualTo("WARN");
    //   // TODO: add assertion for rpc.system.name, rpc.method, rpc.response.status_code, error.type
    //   // TODO: add assertion for gcp.errors.domain, gcp.errors.metadata.*
    //   // TODO: add assertion for exception.type, exception.message, exception.stacktrace
    // }
  }

  @Test
  void testLogging_retries_grpc() throws Exception {
    RetrySettings retrySettings =
        RetrySettings.newBuilder()
            .setTotalTimeout(org.threeten.bp.Duration.ofMillis(1L))
            .setMaxAttempts(3)
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

    EchoStubSettings echoStubSettings = (EchoStubSettings) grpcEchoSettings.getStubSettings();
    EchoStub stub = new ExtendedEchoStubSettings(echoStubSettings.toBuilder()).createStub();
    EchoClient client = EchoClient.create(stub);

    EchoRequest echoRequest = EchoRequest.newBuilder().setContent("test").build();

    try {
      client.echo(echoRequest);
    } catch (Exception e) {}

    List<LogRecordData> logs = logExporter.getFinishedLogRecordItems();
    // F3.5
    // TODO: assert that each retry failure logs at DEBUG level (L4) with appropriate response.payload
    // TODO: assert that the final terminal failure logs at WARN level (L2/L3)
  }

  private EchoSettings createEchoSettings(boolean isHttpJson) throws Exception {
    if (isHttpJson) {
      return EchoSettings.newHttpJsonBuilder()
          .setCredentialsProvider(NoCredentialsProvider.create())
          .setTransportChannelProvider(
              EchoSettings.defaultHttpJsonTransportProviderBuilder()
                  .setHttpTransport(
                      new NetHttpTransport.Builder().doNotValidateCertificate().build())
                  .build())
          .setEndpoint(SHOWCASE_HTTPJSON_ENDPOINT)
          .build();
    } else {
      return EchoSettings.newBuilder()
          .setCredentialsProvider(NoCredentialsProvider.create())
          .setTransportChannelProvider(
              EchoSettings.defaultGrpcTransportProviderBuilder()
                  .setChannelConfigurator(ManagedChannelBuilder::usePlaintext)
                  .build())
          .setEndpoint(SHOWCASE_GRPC_ENDPOINT)
          .build();
    }
  }

  private EchoStub createStubWithServiceName(EchoSettings settings) throws IOException {
    EchoStubSettings.Builder builder =
        (EchoStubSettings.Builder) settings.getStubSettings().toBuilder();
    return new ExtendedEchoStubSettings(builder).createStub();
  }

  /** Custom wrapper to set a service name for showcase clients, which lack one by default. */
  private static class ExtendedEchoStubSettings extends EchoStubSettings {
    protected ExtendedEchoStubSettings(EchoStubSettings.Builder builder) throws IOException {
      super(builder);
    }

    @Override
    public String getServiceName() {
      return "showcase";
    }
  }
}
