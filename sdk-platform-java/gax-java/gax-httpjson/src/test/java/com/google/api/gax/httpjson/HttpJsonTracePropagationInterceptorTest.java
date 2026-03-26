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
package com.google.api.gax.httpjson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class HttpJsonTracePropagationInterceptorTest {

  private static OpenTelemetrySdk openTelemetry;
  private static Tracer tracer;

  @BeforeAll
  public static void setUp() {
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
            .build();
    openTelemetry =
        OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(
                ContextPropagators.create(
                    io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()))
            .buildAndRegisterGlobal();
    tracer = openTelemetry.getTracer("test");
  }

  @AfterAll
  public static void tearDown() {
    GlobalOpenTelemetry.resetForTest();
  }

  @Test
  public void testPropagation() {
    HttpJsonTracePropagationInterceptor interceptor = new HttpJsonTracePropagationInterceptor();
    HttpJsonChannel channel = mock(HttpJsonChannel.class);
    ApiMethodDescriptor<String, String> methodDescriptor =
        ApiMethodDescriptor.<String, String>newBuilder()
            .setFullMethodName("test/test")
            .setHttpMethod("GET")
            .setRequestFormatter(mock(HttpRequestFormatter.class))
            .setResponseParser(mock(HttpResponseParser.class))
            .build();

    @SuppressWarnings("unchecked")
    HttpJsonClientCall<String, String> clientCall = mock(HttpJsonClientCall.class);
    when(channel.newCall(methodDescriptor, HttpJsonCallOptions.DEFAULT)).thenReturn(clientCall);

    HttpJsonClientCall<String, String> interceptedCall =
        interceptor.interceptCall(methodDescriptor, HttpJsonCallOptions.DEFAULT, channel);

    HttpJsonMetadata metadata = HttpJsonMetadata.newBuilder().build();
    @SuppressWarnings("unchecked")
    HttpJsonClientCall.Listener<String> listener = mock(HttpJsonClientCall.Listener.class);

    // We capture the modified headers directly from the underlying call.
    // The interceptor creates a new HttpJsonMetadata.Builder, modifies it, and builds it.
    // We mock start to capture the argument.
    final HttpJsonMetadata[] capturedMetadata = new HttpJsonMetadata[1];
    HttpJsonClientCall<String, String> verifyingClientCall =
        new HttpJsonClientCall<String, String>() {
          @Override
          public void start(Listener<String> responseListener, HttpJsonMetadata requestHeaders) {
            capturedMetadata[0] = requestHeaders;
          }

          @Override
          public void request(int numMessages) {}

          @Override
          public void cancel(String message, Throwable cause) {}

          @Override
          public void halfClose() {}

          @Override
          public void sendMessage(String message) {}
        };

    when(channel.newCall(methodDescriptor, HttpJsonCallOptions.DEFAULT))
        .thenReturn(verifyingClientCall);
    interceptedCall =
        interceptor.interceptCall(methodDescriptor, HttpJsonCallOptions.DEFAULT, channel);

    Span span = tracer.spanBuilder("test-span").startSpan();
    try (Scope ignored = span.makeCurrent()) {
      interceptedCall.start(listener, metadata);
    } finally {
      span.end();
    }

    HttpJsonMetadata modifiedMetadata = capturedMetadata[0];
    assertTrue(modifiedMetadata.getHeaders().containsKey("traceparent"));

    // Assert the traceparent header was added and matches the span
    String traceparent = (String) modifiedMetadata.getHeaders().get("traceparent");
    String expectedTraceId = span.getSpanContext().getTraceId();
    String expectedSpanId = span.getSpanContext().getSpanId();
    assertEquals("00-" + expectedTraceId + "-" + expectedSpanId + "-01", traceparent);
  }
}
