/*
 * Copyright 2024 Google LLC
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
import com.google.api.core.ApiFunction;
import com.google.api.core.ApiFuture;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.api.gax.rpc.StatusCode.Code;
import com.google.api.gax.rpc.UnaryCallable;
import com.google.api.gax.rpc.UnavailableException;
import com.google.api.gax.tracing.GoldenSignalsMetricsTracerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.truth.Truth;
import com.google.protobuf.Duration;
import com.google.rpc.Status;
import com.google.showcase.v1beta1.BlockRequest;
import com.google.showcase.v1beta1.BlockResponse;
import com.google.showcase.v1beta1.EchoClient;
import com.google.showcase.v1beta1.EchoRequest;
import com.google.showcase.v1beta1.EchoSettings;
import com.google.showcase.v1beta1.it.util.TestClientInitializer;
import com.google.showcase.v1beta1.stub.EchoStub;
import com.google.showcase.v1beta1.stub.EchoStubSettings;
import io.grpc.ManagedChannelBuilder;
import io.grpc.opentelemetry.GrpcOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.Data;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Showcase Test to confirm that metrics are being collected and that the correct metrics are being
 * recorded. Utilizes an in-memory metric reader to collect the data.
 */
class ITOtelMetrics {
  private static final int DEFAULT_OPERATION_COUNT = 1;
  private static final String GCP_CLIENT_REQUEST_DURATION = "gcp.client.request.duration";

  private static final Set<String> GAX_METRICS =
      ImmutableSet.of(GCP_CLIENT_REQUEST_DURATION);

  // We expect 1 golden signals metric
  private static final int NUM_GAX_OTEL_METRICS = 1;
  private static final int NUM_DEFAULT_FLUSH_ATTEMPTS = 10;

  private InMemoryMetricReader inMemoryMetricReader;
  private OpenTelemetrySdk openTelemetrySdk;
  private EchoClient grpcClient;
  private EchoClient httpClient;

  private static class StatusCount {
    private final Code statusCode;
    private final int count;

    public StatusCount(Code statusCode) {
      this(statusCode, 1);
    }

    public StatusCount(Code statusCode, int count) {
      this.statusCode = statusCode;
      this.count = count;
    }

    public Code getStatusCode() {
      return statusCode;
    }

    public int getCount() {
      return count;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    inMemoryMetricReader = InMemoryMetricReader.create();
    SdkMeterProvider sdkMeterProvider =
        SdkMeterProvider.builder().registerMetricReader(inMemoryMetricReader).build();

    openTelemetrySdk =
        OpenTelemetrySdk.builder().setMeterProvider(sdkMeterProvider).build();

    grpcClient =
        TestClientInitializer.createGrpcEchoClientOpentelemetry(
            new GoldenSignalsMetricsTracerFactory(openTelemetrySdk));
    httpClient =
        TestClientInitializer.createHttpJsonEchoClientOpentelemetry(
            new GoldenSignalsMetricsTracerFactory(openTelemetrySdk));
  }

  @AfterEach
  void cleanup() throws InterruptedException, IOException {
    inMemoryMetricReader.close();
    inMemoryMetricReader.shutdown();

    grpcClient.close();
    httpClient.close();

    grpcClient.awaitTermination(TestClientInitializer.AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS);
    httpClient.awaitTermination(TestClientInitializer.AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void testMetrics_disabled_grpc() throws Exception {
    InMemoryMetricReader disabledMetricReader = InMemoryMetricReader.create();
    try (EchoClient client = TestClientInitializer.createGrpcEchoClient()) {
      client.echo(EchoRequest.newBuilder().setContent("metrics-disabled-test").build());
      List<MetricData> metrics = new ArrayList<>(disabledMetricReader.collectAllMetrics());
      Truth.assertThat(metrics).isEmpty();
    }
  }

  @Test
  void testMetrics_disabled_httpjson() throws Exception {
    InMemoryMetricReader disabledMetricReader = InMemoryMetricReader.create();
    try (EchoClient client = TestClientInitializer.createHttpJsonEchoClient()) {
      client.echo(EchoRequest.newBuilder().setContent("metrics-disabled-test").build());
      List<MetricData> metrics = new ArrayList<>(disabledMetricReader.collectAllMetrics());
      Truth.assertThat(metrics).isEmpty();
    }
  }

  private void verifyPointDataSum(List<MetricData> metricDataList, int expectedOperationCount) {
    for (MetricData metricData : metricDataList) {
      Data<?> data = metricData.getData();
      List<PointData> points = new ArrayList<>(data.getPoints());
      if (metricData.getName().equals(GCP_CLIENT_REQUEST_DURATION)) {
        long durationCountSum =
            points.stream().map(x -> ((HistogramPointData) x).getCount()).reduce(0L, Long::sum);
        Truth.assertThat(durationCountSum).isEqualTo(expectedOperationCount);
        Truth.assertThat(metricData.getUnit()).isEqualTo("s");
        if (!points.isEmpty()) {
          HistogramPointData pointData = (HistogramPointData) points.get(0);
          Truth.assertThat(pointData.getBoundaries())
              .containsExactly(0.0, 0.0001, 0.0005, 0.0010, 0.005, 0.010, 0.050, 0.100, 0.5, 1.0, 5.0, 10.0, 60.0, 300.0, 900.0, 3600.0)
              .inOrder();
        }
      }
    }
  }

  private void verifyDefaultMetricsAttributes(
      List<MetricData> metricDataList, Map<String, String> defaultAttributeMapping) {
    Optional<MetricData> metricDataOptional =
        metricDataList.stream().filter(x -> x.getName().equals(GCP_CLIENT_REQUEST_DURATION)).findAny();
    Truth.assertThat(metricDataOptional.isPresent()).isTrue();
    MetricData durationMetricData = metricDataOptional.get();

    List<PointData> pointDataList = new ArrayList<>(durationMetricData.getData().getPoints());
    Truth.assertThat(pointDataList).isNotEmpty();
    Attributes recordedAttributes = pointDataList.get(0).getAttributes();
    Map<AttributeKey<?>, Object> recordedAttributesMap = recordedAttributes.asMap();
    for (Map.Entry<String, String> entrySet : defaultAttributeMapping.entrySet()) {
      String key = entrySet.getKey();
      String value = entrySet.getValue();
      AttributeKey<String> stringAttributeKey = AttributeKey.stringKey(key);
      if (recordedAttributesMap.containsKey(stringAttributeKey)) {
        Truth.assertThat(recordedAttributesMap.get(stringAttributeKey)).isEqualTo(value);
      } else {
        // Missing attribute handled implicitly
      }
    }
  }

  private void verifyStatusAttribute(
      List<MetricData> metricDataList, List<StatusCount> statusCountList) {
    Optional<MetricData> metricDataOptional =
        metricDataList.stream().filter(x -> x.getName().equals(GCP_CLIENT_REQUEST_DURATION)).findAny();
    Truth.assertThat(metricDataOptional.isPresent()).isTrue();
    MetricData durationMetricData = metricDataOptional.get();

    List<PointData> pointDataList = new ArrayList<>(durationMetricData.getData().getPoints());
    Truth.assertThat(pointDataList.size()).isEqualTo(statusCountList.size());

    for (StatusCount statusCount : statusCountList) {
      Code statusCode = statusCount.getStatusCode();
      Predicate<PointData> pointDataPredicate =
          x ->
              x.getAttributes()
                  .get(AttributeKey.stringKey("rpc.response.status_code"))
                  .equals(statusCode.toString());
      Optional<PointData> pointDataOptional =
          pointDataList.stream().filter(pointDataPredicate).findFirst();
      Truth.assertThat(pointDataOptional.isPresent()).isTrue();
      HistogramPointData histogramPointData = (HistogramPointData) pointDataOptional.get();
      Truth.assertThat(histogramPointData.getCount()).isEqualTo(statusCount.getCount());
    }
  }

  private List<MetricData> getMetricDataList() throws InterruptedException {
    return getMetricDataList(inMemoryMetricReader);
  }

  private List<MetricData> getMetricDataList(InMemoryMetricReader metricReader)
      throws InterruptedException {
    for (int i = 0; i < NUM_DEFAULT_FLUSH_ATTEMPTS; i++) {
      Thread.sleep(1000L);
      List<MetricData> metricData = new ArrayList<>(metricReader.collectAllMetrics());
      if (metricData.size() >= NUM_GAX_OTEL_METRICS && areAllGaxMetricsRecorded(metricData)) {
        return metricData;
      }
    }
    Assertions.fail("Unable to collect all the GAX metrics required for the test");
    return new ArrayList<>();
  }

  private boolean areAllGaxMetricsRecorded(List<MetricData> metricData) {
    return metricData.stream().filter(data -> GAX_METRICS.contains(data.getName())).count()
        == NUM_GAX_OTEL_METRICS;
  }

  @Test
  void testGrpc_operationSucceeded_recordsMetrics() throws InterruptedException {
    EchoRequest echoRequest =
        EchoRequest.newBuilder().setContent("test_grpc_operation_succeeded").build();
    grpcClient.echo(echoRequest);

    List<MetricData> actualMetricDataList = getMetricDataList();
    verifyPointDataSum(actualMetricDataList, DEFAULT_OPERATION_COUNT);

    Map<String, String> expectedAttributes =
        ImmutableMap.<String, String>builder()
            .put("rpc.system.name", "grpc")
            .put("rpc.method", "google.showcase.v1beta1.Echo/Echo")
            .put("server.address", "localhost")
            .put("server.port", "7469")
            .put("gcp.client.repo", "googleapis/sdk-platform-java")
            .put("gcp.client.artifact", "com.google.cloud:gapic-showcase")
            .put("gcp.client.version", "0.0.0-SNAPSHOT")
            .put("gcp.client.service", "showcase")
            .put("rpc.response.status_code", "OK")
            .build();
    verifyDefaultMetricsAttributes(actualMetricDataList, expectedAttributes);

    List<StatusCount> statusCountList = ImmutableList.of(new StatusCount(Code.OK));
    verifyStatusAttribute(actualMetricDataList, statusCountList);
  }

  @Test
  void testHttpJson_operationSucceeded_recordsMetrics() throws InterruptedException {
    EchoRequest echoRequest = EchoRequest.newBuilder().setContent("content").build();
    httpClient.echo(echoRequest);

    List<MetricData> actualMetricDataList = getMetricDataList();
    verifyPointDataSum(actualMetricDataList, DEFAULT_OPERATION_COUNT);

    Map<String, String> expectedAttributes =
        ImmutableMap.<String, String>builder()
            .put("rpc.system.name", "http")
            .put("rpc.method", "google.showcase.v1beta1.Echo/Echo")
            .put("server.address", "localhost")
            .put("server.port", "7469")
            .put("gcp.client.repo", "googleapis/sdk-platform-java")
            .put("gcp.client.artifact", "com.google.cloud:gapic-showcase")
            .put("gcp.client.version", "0.0.0-SNAPSHOT")
            .put("gcp.client.service", "showcase")
            .put("rpc.response.status_code", "OK")
            .put("http.response.status_code", "200")
            .build();
    verifyDefaultMetricsAttributes(actualMetricDataList, expectedAttributes);

    List<StatusCount> statusCountList = ImmutableList.of(new StatusCount(Code.OK));
    verifyStatusAttribute(actualMetricDataList, statusCountList);
  }

  @Test
  void testGrpc_operationCancelled_recordsMetrics() throws Exception {
    BlockRequest blockRequest =
        BlockRequest.newBuilder()
            .setResponseDelay(Duration.newBuilder().setSeconds(5))
            .setSuccess(BlockResponse.newBuilder().setContent("grpc_operationCancelled"))
            .build();

    UnaryCallable<BlockRequest, BlockResponse> blockCallable = grpcClient.blockCallable();
    ApiFuture<BlockResponse> blockResponseApiFuture = blockCallable.futureCall(blockRequest);
    // Sleep 1s before cancelling to let the request go through
    Thread.sleep(1000);
    blockResponseApiFuture.cancel(true);

    List<MetricData> actualMetricDataList = getMetricDataList();
    verifyPointDataSum(actualMetricDataList, DEFAULT_OPERATION_COUNT);

    Map<String, String> expectedAttributes =
        ImmutableMap.<String, String>builder()
            .put("rpc.system.name", "grpc")
            .put("rpc.method", "google.showcase.v1beta1.Echo/Block")
            .put("server.address", "localhost")
            .put("server.port", "7469")
            .put("gcp.client.repo", "googleapis/sdk-platform-java")
            .put("gcp.client.artifact", "com.google.cloud:gapic-showcase")
            .put("gcp.client.version", "0.0.0-SNAPSHOT")
            //.put("gcp.client.service", "showcase")
            .put("rpc.response.status_code", "CANCELLED")
            .put("error.type", "CANCELLED")
            .build();
    verifyDefaultMetricsAttributes(actualMetricDataList, expectedAttributes);

    List<StatusCount> statusCountList = ImmutableList.of(new StatusCount(Code.CANCELLED));
    verifyStatusAttribute(actualMetricDataList, statusCountList);
  }

  @Test
  void testHttpJson_operationCancelled_recordsMetrics() throws Exception {
    BlockRequest blockRequest =
        BlockRequest.newBuilder().setResponseDelay(Duration.newBuilder().setSeconds(5)).build();

    UnaryCallable<BlockRequest, BlockResponse> blockCallable = httpClient.blockCallable();
    ApiFuture<BlockResponse> blockResponseApiFuture = blockCallable.futureCall(blockRequest);
    // Sleep 1s before cancelling to let the request go through
    Thread.sleep(1000);
    blockResponseApiFuture.cancel(true);

    List<MetricData> actualMetricDataList = getMetricDataList();
    verifyPointDataSum(actualMetricDataList, DEFAULT_OPERATION_COUNT);

    Map<String, String> expectedAttributes =
        ImmutableMap.<String, String>builder()
            .put("rpc.system.name", "http")
            .put("rpc.method", "google.showcase.v1beta1.Echo/Block")
            .put("server.address", "localhost")
            .put("server.port", "7469")
            .put("gcp.client.repo", "googleapis/sdk-platform-java")
            .put("gcp.client.artifact", "com.google.cloud:gapic-showcase")
            .put("gcp.client.version", "0.0.0-SNAPSHOT")
            //.put("gcp.client.service", "showcase")
            .put("rpc.response.status_code", "CANCELLED")
            .put("error.type", "CANCELLED")
            .build();
    verifyDefaultMetricsAttributes(actualMetricDataList, expectedAttributes);

    List<StatusCount> statusCountList = ImmutableList.of(new StatusCount(Code.CANCELLED));
    verifyStatusAttribute(actualMetricDataList, statusCountList);
  }

  @Test
  void testGrpc_operationFailed_recordsMetrics() throws InterruptedException {
    Code statusCode = Code.INVALID_ARGUMENT;
    BlockRequest blockRequest =
        BlockRequest.newBuilder()
            .setResponseDelay(Duration.newBuilder().setSeconds(2))
            .setError(Status.newBuilder().setCode(statusCode.ordinal()))
            .build();

    UnaryCallable<BlockRequest, BlockResponse> blockCallable = grpcClient.blockCallable();
    ApiFuture<BlockResponse> blockResponseApiFuture = blockCallable.futureCall(blockRequest);
    assertThrows(ExecutionException.class, blockResponseApiFuture::get);

    List<MetricData> actualMetricDataList = getMetricDataList();
    verifyPointDataSum(actualMetricDataList, DEFAULT_OPERATION_COUNT);

    Map<String, String> expectedAttributes =
        ImmutableMap.<String, String>builder()
            .put("rpc.system.name", "grpc")
            .put("rpc.method", "google.showcase.v1beta1.Echo/Block")
            .put("server.address", "localhost")
            .put("server.port", "7469")
            .put("gcp.client.repo", "googleapis/sdk-platform-java")
            .put("gcp.client.artifact", "com.google.cloud:gapic-showcase")
            .put("gcp.client.version", "0.0.0-SNAPSHOT")
            //.put("gcp.client.service", "showcase")
            .put("rpc.response.status_code", "INVALID_ARGUMENT")
            .put("error.type", "INVALID_ARGUMENT")
            .build();
    verifyDefaultMetricsAttributes(actualMetricDataList, expectedAttributes);

    List<StatusCount> statusCountList = ImmutableList.of(new StatusCount(statusCode));
    verifyStatusAttribute(actualMetricDataList, statusCountList);
  }

  @Test
  void testHttpJson_operationFailed_recordsMetrics() throws InterruptedException {
    Code statusCode = Code.INVALID_ARGUMENT;
    BlockRequest blockRequest =
        BlockRequest.newBuilder()
            .setResponseDelay(Duration.newBuilder().setSeconds(2))
            .setError(Status.newBuilder().setCode(statusCode.ordinal()))
            .build();

    UnaryCallable<BlockRequest, BlockResponse> blockCallable = httpClient.blockCallable();
    ApiFuture<BlockResponse> blockResponseApiFuture = blockCallable.futureCall(blockRequest);
    assertThrows(ExecutionException.class, blockResponseApiFuture::get);

    List<MetricData> actualMetricDataList = getMetricDataList();
    verifyPointDataSum(actualMetricDataList, DEFAULT_OPERATION_COUNT);

    Map<String, String> expectedAttributes =
        ImmutableMap.<String, String>builder()
            .put("rpc.system.name", "http")
            .put("rpc.method", "google.showcase.v1beta1.Echo/Block")
            .put("server.address", "localhost")
            .put("server.port", "7469")
            .put("gcp.client.repo", "googleapis/sdk-platform-java")
            .put("gcp.client.artifact", "com.google.cloud:gapic-showcase")
            .put("gcp.client.version", "0.0.0-SNAPSHOT")
            //.put("gcp.client.service", "showcase")
            .put("rpc.response.status_code", "INVALID_ARGUMENT")
            .put("http.response.status_code", "400")
            .put("error.type", "INVALID_ARGUMENT")
            .build();
    verifyDefaultMetricsAttributes(actualMetricDataList, expectedAttributes);

    List<StatusCount> statusCountList = ImmutableList.of(new StatusCount(statusCode));
    verifyStatusAttribute(actualMetricDataList, statusCountList);
  }

  @Test
  void testGrpc_attemptFailedRetriesExhausted_recordsMetrics() throws Exception {
    Code statusCode = Code.UNAVAILABLE;
    // A custom EchoClient is used in this test because retries have jitter, and we cannot
    // predict the number of attempts that are scheduled for an RPC invocation otherwise.
    // The custom retrySettings limit to a set number of attempts before the call gives up.
    RetrySettings retrySettings =
        RetrySettings.newBuilder()
            .setTotalTimeout(org.threeten.bp.Duration.ofMillis(5000L))
            .setMaxAttempts(3)
            .build();

    EchoStubSettings.Builder grpcEchoSettingsBuilder = EchoStubSettings.newBuilder();
    grpcEchoSettingsBuilder
        .echoSettings()
        .setRetrySettings(retrySettings)
        .setRetryableCodes(ImmutableSet.of(statusCode));
    EchoSettings grpcEchoSettings = EchoSettings.create(grpcEchoSettingsBuilder.build());
    grpcEchoSettings =
        grpcEchoSettings.toBuilder()
            .setCredentialsProvider(NoCredentialsProvider.create())
            .setTransportChannelProvider(
                EchoSettings.defaultGrpcTransportProviderBuilder()
                    .setChannelConfigurator(ManagedChannelBuilder::usePlaintext)
                    .build())
            .setEndpoint("localhost:7469")
            .build();

    EchoStubSettings echoStubSettings =
        (EchoStubSettings)
            grpcEchoSettings.getStubSettings().toBuilder()
                .setTracerFactory(
                    new GoldenSignalsMetricsTracerFactory(openTelemetrySdk))
                .build();
    EchoStub stub = echoStubSettings.createStub();
    EchoClient grpcClient = EchoClient.create(stub);

    EchoRequest echoRequest =
        EchoRequest.newBuilder()
            .setError(Status.newBuilder().setCode(statusCode.ordinal()).build())
            .build();

    assertThrows(UnavailableException.class, () -> grpcClient.echo(echoRequest));

    List<MetricData> actualMetricDataList = getMetricDataList();
    verifyPointDataSum(actualMetricDataList, DEFAULT_OPERATION_COUNT);

    Map<String, String> expectedAttributes =
        ImmutableMap.<String, String>builder()
            .put("rpc.system.name", "grpc")
            .put("rpc.method", "google.showcase.v1beta1.Echo/Echo")
            .put("server.address", "localhost")
            .put("server.port", "7469")
            .put("gcp.client.repo", "googleapis/sdk-platform-java")
            .put("gcp.client.artifact", "com.google.cloud:gapic-showcase")
            .put("gcp.client.version", "0.0.0-SNAPSHOT")
            //.put("gcp.client.service", "showcase")
            .put("rpc.response.status_code", "UNAVAILABLE")
            .put("error.type", "UNAVAILABLE")
            .build();
    verifyDefaultMetricsAttributes(actualMetricDataList, expectedAttributes);

    List<StatusCount> statusCountList = ImmutableList.of(new StatusCount(statusCode, 1));
    verifyStatusAttribute(actualMetricDataList, statusCountList);

    grpcClient.close();
    grpcClient.awaitTermination(TestClientInitializer.AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void testHttpJson_attemptFailedRetriesExhausted_recordsMetrics() throws Exception {
    Code statusCode = Code.UNAVAILABLE;
    // A custom EchoClient is used in this test because retries have jitter, and we cannot
    // predict the number of attempts that are scheduled for an RPC invocation otherwise.
    // The custom retrySettings limit to a set number of attempts before the call gives up.
    RetrySettings retrySettings =
        RetrySettings.newBuilder()
            .setTotalTimeout(org.threeten.bp.Duration.ofMillis(5000L))
            .setMaxAttempts(3)
            .build();

    EchoStubSettings.Builder httpJsonEchoSettingsBuilder = EchoStubSettings.newHttpJsonBuilder();
    httpJsonEchoSettingsBuilder
        .echoSettings()
        .setRetrySettings(retrySettings)
        .setRetryableCodes(ImmutableSet.of(statusCode));
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

    EchoStubSettings echoStubSettings =
        (EchoStubSettings)
            httpJsonEchoSettings.getStubSettings().toBuilder()
                .setTracerFactory(
                    new GoldenSignalsMetricsTracerFactory(openTelemetrySdk))
                .build();
    EchoStub stub = echoStubSettings.createStub();
    EchoClient httpClient = EchoClient.create(stub);

    EchoRequest echoRequest =
        EchoRequest.newBuilder()
            .setError(Status.newBuilder().setCode(statusCode.ordinal()).build())
            .build();

    assertThrows(UnavailableException.class, () -> httpClient.echo(echoRequest));

    List<MetricData> actualMetricDataList = getMetricDataList();
    verifyPointDataSum(actualMetricDataList, DEFAULT_OPERATION_COUNT);

    Map<String, String> expectedAttributes =
        ImmutableMap.<String, String>builder()
            .put("rpc.system.name", "http")
            .put("rpc.method", "google.showcase.v1beta1.Echo/Echo")
            .put("server.address", "localhost")
            .put("server.port", "7469")
            .put("gcp.client.repo", "googleapis/sdk-platform-java")
            .put("gcp.client.artifact", "com.google.cloud:gapic-showcase")
            .put("gcp.client.version", "0.0.0-SNAPSHOT")
            //.put("gcp.client.service", "showcase")
            .put("rpc.response.status_code", "UNAVAILABLE")
            .put("http.response.status_code", "503")
            .put("error.type", "UNAVAILABLE")
            .build();
    verifyDefaultMetricsAttributes(actualMetricDataList, expectedAttributes);

    List<StatusCount> statusCountList = ImmutableList.of(new StatusCount(statusCode, 1));
    verifyStatusAttribute(actualMetricDataList, statusCountList);

    httpClient.close();
    httpClient.awaitTermination(TestClientInitializer.AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void testGrpc_multipleFailedAttempts_successfulOperation() throws Exception {
    // Disable Jitter on this test to try and ensure that the there are 3 attempts made
    // for test. The first two calls should result in a DEADLINE_EXCEEDED exception as
    // 0.5s and 1s are too short for the 1s blocking call (1s still requires time for
    // the showcase server to respond back to the client). The 3rd and final call (2s)
    // should result in an OK Status Code.
    RetrySettings retrySettings =
        RetrySettings.newBuilder()
            .setInitialRpcTimeout(org.threeten.bp.Duration.ofMillis(500L))
            .setRpcTimeoutMultiplier(2.0)
            .setMaxRpcTimeout(org.threeten.bp.Duration.ofMillis(2000L))
            .setTotalTimeout(org.threeten.bp.Duration.ofMillis(6000L))
            .setJittered(false)
            .build();

    EchoStubSettings.Builder grpcEchoSettingsBuilder = EchoStubSettings.newBuilder();
    grpcEchoSettingsBuilder
        .blockSettings()
        .setRetrySettings(retrySettings)
        .setRetryableCodes(ImmutableSet.of(Code.DEADLINE_EXCEEDED));
    EchoSettings grpcEchoSettings = EchoSettings.create(grpcEchoSettingsBuilder.build());
    grpcEchoSettings =
        grpcEchoSettings.toBuilder()
            .setCredentialsProvider(NoCredentialsProvider.create())
            .setTransportChannelProvider(
                EchoSettings.defaultGrpcTransportProviderBuilder()
                    .setChannelConfigurator(ManagedChannelBuilder::usePlaintext)
                    .build())
            .setEndpoint("localhost:7469")
            .build();

    EchoStubSettings echoStubSettings =
        (EchoStubSettings)
            grpcEchoSettings.getStubSettings().toBuilder()
                .setTracerFactory(
                    new GoldenSignalsMetricsTracerFactory(openTelemetrySdk))
                .build();
    EchoStub stub = echoStubSettings.createStub();

    EchoClient grpcClient = EchoClient.create(stub);

    BlockRequest blockRequest =
        BlockRequest.newBuilder()
            .setResponseDelay(Duration.newBuilder().setSeconds(1))
            .setSuccess(BlockResponse.newBuilder().setContent("grpcBlockResponse"))
            .build();

    grpcClient.block(blockRequest);

    List<MetricData> actualMetricDataList = getMetricDataList();
    verifyPointDataSum(actualMetricDataList, DEFAULT_OPERATION_COUNT);

    Map<String, String> expectedAttributes =
        ImmutableMap.<String, String>builder()
            .put("rpc.system.name", "grpc")
            .put("rpc.method", "google.showcase.v1beta1.Echo/Block")
            .put("server.address", "localhost")
            .put("server.port", "7469")
            .put("gcp.client.repo", "googleapis/sdk-platform-java")
            .put("gcp.client.artifact", "com.google.cloud:gapic-showcase")
            .put("gcp.client.version", "0.0.0-SNAPSHOT")
            //.put("gcp.client.service", "showcase")
            .put("rpc.response.status_code", "OK")
            .build();
    verifyDefaultMetricsAttributes(actualMetricDataList, expectedAttributes);

    List<StatusCount> statusCountList =
        ImmutableList.of(new StatusCount(Code.OK, 1));
    verifyStatusAttribute(actualMetricDataList, statusCountList);

    grpcClient.close();
    grpcClient.awaitTermination(TestClientInitializer.AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void testHttpJson_multipleFailedAttempts_successfulOperation() throws Exception {
    RetrySettings retrySettings =
        RetrySettings.newBuilder()
            .setInitialRpcTimeout(org.threeten.bp.Duration.ofMillis(500L))
            .setRpcTimeoutMultiplier(2.0)
            .setMaxRpcTimeout(org.threeten.bp.Duration.ofMillis(2000L))
            .setTotalTimeout(org.threeten.bp.Duration.ofMillis(6000L))
            .setJittered(false)
            .build();

    EchoStubSettings.Builder httpJsonEchoSettingsBuilder = EchoStubSettings.newHttpJsonBuilder();
    httpJsonEchoSettingsBuilder
        .blockSettings()
        .setRetrySettings(retrySettings)
        .setRetryableCodes(ImmutableSet.of(Code.DEADLINE_EXCEEDED));
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

    EchoStubSettings echoStubSettings =
        (EchoStubSettings)
            httpJsonEchoSettings.getStubSettings().toBuilder()
                .setTracerFactory(
                    new GoldenSignalsMetricsTracerFactory(openTelemetrySdk))
                .build();
    EchoStub stub = echoStubSettings.createStub();

    EchoClient httpClient = EchoClient.create(stub);

    BlockRequest blockRequest =
        BlockRequest.newBuilder()
            .setResponseDelay(Duration.newBuilder().setSeconds(1))
            .setSuccess(BlockResponse.newBuilder().setContent("httpjsonBlockResponse"))
            .build();

    httpClient.block(blockRequest);

    List<MetricData> actualMetricDataList = getMetricDataList();
    verifyPointDataSum(actualMetricDataList, DEFAULT_OPERATION_COUNT);

    Map<String, String> expectedAttributes =
        ImmutableMap.<String, String>builder()
            .put("rpc.system.name", "http")
            .put("rpc.method", "google.showcase.v1beta1.Echo/Block")
            .put("server.address", "localhost")
            .put("server.port", "7469")
            .put("gcp.client.repo", "googleapis/sdk-platform-java")
            .put("gcp.client.artifact", "com.google.cloud:gapic-showcase")
            .put("gcp.client.version", "0.0.0-SNAPSHOT")
            //.put("gcp.client.service", "showcase")
            .put("rpc.response.status_code", "OK")
            .put("http.response.status_code", "200")
            .build();
    verifyDefaultMetricsAttributes(actualMetricDataList, expectedAttributes);

    List<StatusCount> statusCountList = ImmutableList.of(new StatusCount(Code.OK, 1));
    verifyStatusAttribute(actualMetricDataList, statusCountList);

    httpClient.close();
    httpClient.awaitTermination(TestClientInitializer.AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS);
  }
}