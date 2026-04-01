package com.google.showcase.v1beta1.it;

import static com.google.common.truth.Truth.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ITOtelLogging {
  private static final String SHOWCASE_SERVER_ADDRESS = "localhost";
  private static final long SHOWCASE_SERVER_PORT = 7469;
  private static final String SHOWCASE_GRPC_ENDPOINT =
      String.format("%s:%s", SHOWCASE_SERVER_ADDRESS, SHOWCASE_SERVER_PORT);
  private static final String SHOWCASE_HTTPJSON_ENDPOINT =
      String.format("http://%s:%s", SHOWCASE_SERVER_ADDRESS, SHOWCASE_SERVER_PORT);

  private ListAppender<ILoggingEvent> listAppender;
  private Logger rootLogger;

  @BeforeEach
  void setup() throws Exception {
    // Enable logging via reflection since setLoggingEnabled is package-private
    Class<?> loggingUtilsClass = Class.forName("com.google.api.gax.logging.LoggingUtils");
    Method setLoggingEnabledMethod = loggingUtilsClass.getDeclaredMethod("setLoggingEnabled", boolean.class);
    setLoggingEnabledMethod.setAccessible(true);
    setLoggingEnabledMethod.invoke(null, true);

    // Setup Logback ListAppender
    rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    listAppender = new ListAppender<>();
    listAppender.start();
    rootLogger.addAppender(listAppender);
    rootLogger.setLevel(Level.DEBUG); // Ensure DEBUG logs are captured
  }

  @AfterEach
  void tearDown() throws Exception {
    rootLogger.detachAppender(listAppender);

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
      List<ILoggingEvent> logs = listAppender.list;
      assertThat(logs.stream().filter(l -> l.getLoggerName().contains("LoggingTracer")).count()).isEqualTo(0);
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
      List<ILoggingEvent> logs = listAppender.list;
      assertThat(logs.stream().filter(l -> l.getLoggerName().contains("LoggingTracer")).count()).isEqualTo(0);
    }
  }

  @Test
  void testLogging_success_noL4Log_grpc() throws Exception {
    try (EchoClient client = TestClientInitializer.createGrpcEchoClientOpentelemetry(new com.google.api.gax.tracing.LoggingTracerFactory())) {
      try {
        client.echo(EchoRequest.newBuilder().setContent("logging-test").build());
      } catch (Exception e) {}
      
      List<ILoggingEvent> logs = listAppender.list;
      assertThat(logs.stream().filter(l -> l.getLoggerName().contains("LoggingTracer")).count()).isEqualTo(0);
    }
  }

  @Test
  void testLogging_success_noL4Log_httpjson() throws Exception {
    try (EchoClient client = TestClientInitializer.createHttpJsonEchoClientOpentelemetry(new com.google.api.gax.tracing.LoggingTracerFactory())) {
      try {
        client.echo(EchoRequest.newBuilder().setContent("logging-test").build());
      } catch (Exception e) {}

      List<ILoggingEvent> logs = listAppender.list;
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

    List<ILoggingEvent> logs = listAppender.list;
    long l4DebugLogs = logs.stream().filter(l -> l.getLoggerName().contains("LoggingTracer") && l.getLevel().equals(Level.DEBUG)).count();
    
    // Validate we got at least 1 log
    assertThat(l4DebugLogs).isGreaterThan(0L);

    ILoggingEvent l4Log = logs.stream()
        .filter(l -> l.getLoggerName().contains("LoggingTracer") && l.getLevel().equals(Level.DEBUG))
        .findFirst()
        .get();

    Map<String, String> mdc = l4Log.getMDCPropertyMap();

    // F3.4 attributes: Assert what is available, TODO for what is missing.
    assertThat(mdc.keySet()).containsAtLeast("rpc.system.name", "rpc.method", "rpc.response.status_code");
    // TODO: assert error.type
    // TODO: assert gcp.client.service, gcp.client.repo, gcp.client.version
    // TODO: assert gcp.errors.domain, gcp.errors.metadata.*
    // TODO: assert exception.type, exception.message, exception.stacktrace for L2/L3 terminal logs at WARN level
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

    List<ILoggingEvent> logs = listAppender.list;
    long l4DebugLogs = logs.stream().filter(l -> l.getLoggerName().contains("LoggingTracer") && l.getLevel().equals(Level.DEBUG)).count();
    
    assertThat(l4DebugLogs).isGreaterThan(0L);
    
    // TODO: F3.5 - Assert that each retry failure logs at DEBUG level (L4) with appropriate response.payload
    // TODO: Assert that the final terminal failure logs at WARN level (L2/L3)
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
