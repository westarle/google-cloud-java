package com.google.showcase.v1beta1.it;

import static com.google.common.truth.Truth.assertThat;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.showcase.v1beta1.EchoClient;
import com.google.showcase.v1beta1.EchoRequest;
import com.google.showcase.v1beta1.EchoSettings;
import com.google.showcase.v1beta1.it.util.TestClientInitializer;
import com.google.showcase.v1beta1.stub.EchoStub;
import com.google.showcase.v1beta1.stub.EchoStubSettings;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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

  private Logger rootLogger;
  private TestLogHandler logHandler;

  static class TestLogHandler extends Handler {
    public final List<LogRecord> records = new ArrayList<>();
    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }
    @Override
    public void flush() {}
    @Override
    public void close() throws SecurityException {}
  }

  @BeforeEach
  void setup() throws Exception {
    // Enable logging via reflection since setLoggingEnabled is package-private
    Class<?> loggingUtilsClass = Class.forName("com.google.api.gax.logging.LoggingUtils");
    Method setLoggingEnabledMethod = loggingUtilsClass.getDeclaredMethod("setLoggingEnabled", boolean.class);
    setLoggingEnabledMethod.setAccessible(true);
    setLoggingEnabledMethod.invoke(null, true);

    rootLogger = Logger.getLogger("");
    logHandler = new TestLogHandler();
    logHandler.setLevel(Level.ALL);
    rootLogger.addHandler(logHandler);
    rootLogger.setLevel(Level.ALL);
  }

  @AfterEach
  void tearDown() throws Exception {
    rootLogger.removeHandler(logHandler);

    // Disable logging
    Class<?> loggingUtilsClass = Class.forName("com.google.api.gax.logging.LoggingUtils");
    Method setLoggingEnabledMethod = loggingUtilsClass.getDeclaredMethod("setLoggingEnabled", boolean.class);
    setLoggingEnabledMethod.setAccessible(true);
    setLoggingEnabledMethod.invoke(null, false);
  }

  @Test
  void testLogging_disabled_grpc() throws Exception {
    Class<?> loggingUtilsClass = Class.forName("com.google.api.gax.logging.LoggingUtils");
    Method setLoggingEnabledMethod = loggingUtilsClass.getDeclaredMethod("setLoggingEnabled", boolean.class);
    setLoggingEnabledMethod.setAccessible(true);
    setLoggingEnabledMethod.invoke(null, false);

    try (EchoClient client = TestClientInitializer.createGrpcEchoClient()) {
      try {
        client.echo(EchoRequest.newBuilder().setContent("logging-test").build());
      } catch (Exception e) {}
      assertThat(logHandler.records.stream().filter(r -> r.getLoggerName().contains("LoggingTracer")).count()).isEqualTo(0);
    }
  }

  @Test
  void testLogging_disabled_httpjson() throws Exception {
    Class<?> loggingUtilsClass = Class.forName("com.google.api.gax.logging.LoggingUtils");
    Method setLoggingEnabledMethod = loggingUtilsClass.getDeclaredMethod("setLoggingEnabled", boolean.class);
    setLoggingEnabledMethod.setAccessible(true);
    setLoggingEnabledMethod.invoke(null, false);

    try (EchoClient client = TestClientInitializer.createHttpJsonEchoClient()) {
      try {
        client.echo(EchoRequest.newBuilder().setContent("logging-test").build());
      } catch (Exception e) {}
      assertThat(logHandler.records.stream().filter(r -> r.getLoggerName().contains("LoggingTracer")).count()).isEqualTo(0);
    }
  }

  @Test
  void testLogging_success_noL4Log_grpc() throws Exception {
    try (EchoClient client = TestClientInitializer.createGrpcEchoClientOpentelemetry(new com.google.api.gax.tracing.LoggingTracerFactory())) {
      try {
        client.echo(EchoRequest.newBuilder().setContent("logging-test").build());
      } catch (Exception e) {}
      
      List<LogRecord> logs = logHandler.records;
      for (LogRecord l : logs) {
        if (l.getLoggerName().contains("LoggingTracer")) {
          System.out.println("GRPC SUCCESS LOG: " + l.getMessage());
        }
      }
      assertThat(logs.stream().filter(l -> l.getLoggerName().contains("LoggingTracer")).count()).isEqualTo(0);
    }
  }

  @Test
  void testLogging_success_noL4Log_httpjson() throws Exception {
    try (EchoClient client = TestClientInitializer.createHttpJsonEchoClientOpentelemetry(new com.google.api.gax.tracing.LoggingTracerFactory())) {
      try {
        client.echo(EchoRequest.newBuilder().setContent("logging-test").build());
      } catch (Exception e) {}

      List<LogRecord> logs = logHandler.records;
      for (LogRecord l : logs) {
        if (l.getLoggerName().contains("LoggingTracer")) {
          System.out.println("HTTP SUCCESS LOG: " + l.getMessage());
        }
      }
      assertThat(logs.stream().filter(l -> l.getLoggerName().contains("LoggingTracer")).count()).isEqualTo(0);
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

    EchoStub stub = createStubWithServiceName(grpcEchoSettings, new com.google.api.gax.tracing.LoggingTracerFactory());
    EchoClient client = EchoClient.create(stub);

    EchoRequest echoRequest = EchoRequest.newBuilder().setContent("test").build();

    try {
      client.echo(echoRequest);
    } catch (Exception e) {}

    long l4DebugLogs = logHandler.records.stream()
        .filter(r -> r.getLoggerName().contains("LoggingTracer") && r.getLevel().equals(Level.FINE))
        .count();
    // System.out.println("DEBUG LOGS: " + l4DebugLogs);
    // logHandler.records.forEach(r -> System.out.println("LOG: " + r.getMessage()));
    
    // Uncomment once we verify logs are emitted
    assertThat(l4DebugLogs).isGreaterThan(0L);
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

    EchoStub stub = createStubWithServiceName(grpcEchoSettings, new com.google.api.gax.tracing.LoggingTracerFactory());
    EchoClient client = EchoClient.create(stub);

    EchoRequest echoRequest = EchoRequest.newBuilder().setContent("test").build();

    try {
      client.echo(echoRequest);
    } catch (Exception e) {}

    long l4DebugLogs = logHandler.records.stream()
        .filter(r -> r.getLoggerName().contains("LoggingTracer") && r.getLevel().equals(Level.FINE))
        .count();
    
    assertThat(l4DebugLogs).isGreaterThan(0L);
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

  private EchoStub createStubWithServiceName(EchoSettings settings, com.google.api.gax.tracing.ApiTracerFactory tracerFactory) throws IOException {
    EchoStubSettings.Builder builder =
        (EchoStubSettings.Builder) settings.getStubSettings().toBuilder();
    builder.setTracerFactory(tracerFactory);
    return new ExtendedEchoStubSettings(builder).createStub();
  }

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
