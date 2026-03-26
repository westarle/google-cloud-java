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
package com.google.api.gax.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
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

public class GrpcTracePropagationInterceptorTest {

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
    GrpcTracePropagationInterceptor interceptor = new GrpcTracePropagationInterceptor();
    Channel channel = mock(Channel.class);
    MethodDescriptor<String, String> methodDescriptor =
        MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test/test")
            .setRequestMarshaller(mock(MethodDescriptor.Marshaller.class))
            .setResponseMarshaller(mock(MethodDescriptor.Marshaller.class))
            .build();

    @SuppressWarnings("unchecked")
    ClientCall<String, String> clientCall = mock(ClientCall.class);
    when(channel.newCall(methodDescriptor, CallOptions.DEFAULT)).thenReturn(clientCall);

    ClientCall<String, String> interceptedCall =
        interceptor.interceptCall(methodDescriptor, CallOptions.DEFAULT, channel);

    Metadata metadata = new Metadata();
    @SuppressWarnings("unchecked")
    ClientCall.Listener<String> listener = mock(ClientCall.Listener.class);

    Span span = tracer.spanBuilder("test-span").startSpan();
    try (Scope ignored = span.makeCurrent()) {
      interceptedCall.start(listener, metadata);
    } finally {
      span.end();
    }

    String traceparent =
        metadata.get(Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER));

    // Assert the traceparent header was added and matches the span
    String expectedTraceId = span.getSpanContext().getTraceId();
    String expectedSpanId = span.getSpanContext().getSpanId();
    assertEquals("00-" + expectedTraceId + "-" + expectedSpanId + "-01", traceparent);
  }
}
