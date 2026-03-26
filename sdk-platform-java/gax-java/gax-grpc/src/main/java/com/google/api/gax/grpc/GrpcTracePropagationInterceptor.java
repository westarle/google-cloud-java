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

import com.google.api.core.InternalApi;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

/**
 * An interceptor to handle dynamic trace context propagation.
 *
 * <p>Package-private for internal usage.
 */
@InternalApi
public class GrpcTracePropagationInterceptor implements ClientInterceptor {

  private static volatile Boolean isOpentelemetryAvailable;

  private static boolean isOpenTelemetryAvailable() {
    if (isOpentelemetryAvailable == null) {
      synchronized (GrpcTracePropagationInterceptor.class) {
        if (isOpentelemetryAvailable == null) {
          try {
            Class.forName("io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator");
            isOpentelemetryAvailable = true;
          } catch (ClassNotFoundException e) {
            // OpenTelemetry API is not available
            isOpentelemetryAvailable = false;
          }
        }
      }
    }
    return isOpentelemetryAvailable;
  }

  public GrpcTracePropagationInterceptor() {}

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, final CallOptions callOptions, Channel next) {
    if (!isOpenTelemetryAvailable()) {
      return next.newCall(method, callOptions);
    }
    return OpenTelemetryContextInjector.interceptCall(method, callOptions, next);
  }

  private static class OpenTelemetryContextInjector {
    private static final TextMapSetter<Metadata> setter =
        new TextMapSetter<Metadata>() {
          @Override
          public void set(Metadata carrier, String key, String value) {
            if (carrier != null) {
              carrier.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
            }
          }
        };

    static <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, final CallOptions callOptions, Channel next) {
      ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
      return new SimpleForwardingClientCall<ReqT, RespT>(call) {
        @Override
        public void start(ClientCall.Listener<RespT> responseListener, Metadata headers) {
          try {
            TextMapPropagator propagator = W3CTraceContextPropagator.getInstance();
            propagator.inject(Context.current(), headers, setter);
          } catch (NoSuchMethodError e) {
            // Silently ignore if incompatible OpenTelemetry version
          }
          super.start(responseListener, headers);
        }
      };
    }
  }
}
