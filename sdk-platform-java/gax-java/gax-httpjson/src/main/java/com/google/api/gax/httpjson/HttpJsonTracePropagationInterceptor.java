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

import com.google.api.core.InternalApi;
import com.google.api.gax.httpjson.ForwardingHttpJsonClientCall.SimpleForwardingHttpJsonClientCall;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.HashMap;
import java.util.Map;

/**
 * An interceptor to handle dynamic trace context propagation.
 *
 * <p>Package-private for internal usage.
 */
@InternalApi
public class HttpJsonTracePropagationInterceptor implements HttpJsonClientInterceptor {

  private static volatile Boolean isOpentelemetryAvailable;

  private static boolean isOpenTelemetryAvailable() {
    if (isOpentelemetryAvailable == null) {
      synchronized (HttpJsonTracePropagationInterceptor.class) {
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

  public HttpJsonTracePropagationInterceptor() {}

  @Override
  public <ReqT, RespT> HttpJsonClientCall<ReqT, RespT> interceptCall(
      ApiMethodDescriptor<ReqT, RespT> method,
      HttpJsonCallOptions callOptions,
      HttpJsonChannel next) {
    if (!isOpenTelemetryAvailable()) {
      return next.newCall(method, callOptions);
    }
    return OpenTelemetryContextInjector.interceptCall(method, callOptions, next);
  }

  private static class OpenTelemetryContextInjector {

    private static final TextMapSetter<Map<String, String>> setter =
        new TextMapSetter<Map<String, String>>() {
          @Override
          public void set(Map<String, String> carrier, String key, String value) {
            if (carrier != null) {
              carrier.put(key, value);
            }
          }
        };

    static <ReqT, RespT> HttpJsonClientCall<ReqT, RespT> interceptCall(
        ApiMethodDescriptor<ReqT, RespT> method,
        HttpJsonCallOptions callOptions,
        HttpJsonChannel next) {
      HttpJsonClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
      return new SimpleForwardingHttpJsonClientCall<ReqT, RespT>(call) {
        @Override
        public void start(
            HttpJsonClientCall.Listener<RespT> responseListener, HttpJsonMetadata headers) {
          Map<String, String> traceHeaders = new HashMap<>();
          try {
            TextMapPropagator propagator = W3CTraceContextPropagator.getInstance();
            propagator.inject(Context.current(), traceHeaders, setter);
          } catch (NoSuchMethodError e) {
            // Silently ignore if incompatible OpenTelemetry version
          }

          if (traceHeaders.isEmpty()) {
            super.start(responseListener, headers);
            return;
          }

          Map<String, Object> modifiableHeaders = new HashMap<>(headers.getHeaders());
          modifiableHeaders.putAll(traceHeaders);
          super.start(responseListener, headers.toBuilder().setHeaders(modifiableHeaders).build());
        }
      };
    }
  }
}
