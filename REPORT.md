# Java MVP Golden Signals Test Report

This document details the current state of the Google Cloud Java client libraries against the MVP Golden Signals test plan defined in PRD v1.

**Branch for Verification:** [test-logging-actionable-errors](https://github.com/westarle/google-cloud-java/tree/test-logging-actionable-errors)

---

## Methodology

To empirically validate the structural requirements of the Golden Signals PRD, we implemented and ran integration tests against the `gapic-showcase` and `java-bigquery` modules.

1.  **Tracing & Metrics (F1 & F2):** We used OpenTelemetry's `InMemorySpanExporter` and `InMemoryMetricReader` to intercept and evaluate all tracing spans and metrics emitted during unary RPC and HTTP/JSON requests. We executed scenarios for success, server failures, and client/retry exhaustion.
2.  **Actionable Error Logging (F3):** We observed that the `LoggingTracer` does not use standard OpenTelemetry log exporters. Instead, it emits logs directly via SLF4J (backed by `java.util.logging`). We captured these native outputs using a custom `java.util.logging.Handler` and a Logback `ListAppender`, parsing the MDC (Mapped Diagnostic Context) properties attached to each log event. To specifically test `error.type` and `gcp.errors.domain`, we configured an in-memory mock server (`MockEcho`) to throw exceptions packaged with `google.rpc.ErrorInfo` trailers.
3.  **Cloud Trace Integration (F4):** We configured the `AutoConfiguredOpenTelemetrySdk` with the `opentelemetry-gcp-auth-extension` to assert that signals can successfully flow to the production `telemetry.googleapis.com` endpoint using Application Default Credentials.

---

## F1: Tracing (Spans)

### F1.1 gRPC & HTTP: No traces emitted unless enabled
*   **Methodology:** Execute RPCs without configuring the OpenTelemetry SDK.
*   **Result:** **PASS**. No spans are exported.

### F1.3 & F1.4 gRPC: Server & Client Failures
*   **Methodology:** Execute `Echo` requests that return `INVALID_ARGUMENT` and `UNAVAILABLE` respectively. Inspect the captured `SpanData`.

| Expected Attribute / Metadata | Observed Attribute / Metadata | Status | Notes |
| :--- | :--- | :--- | :--- |
| `rpc.system.name` | `rpc.system.name` | **PASS** | |
| `rpc.method` | `rpc.method` | **PASS** | e.g. `google.showcase.v1beta1.Echo/Echo` |
| `server.address` | `server.address` | **PASS** | |
| `server.port` | `server.port` | **PASS** | |
| `gcp.client.repo` | `gcp.client.repo` | **PASS** | e.g. `google-cloud-java` |
| `gcp.client.version` | `gcp.client.version` | **PASS** | |
| `gcp.client.artifact` | `gcp.client.artifact` | **PASS** | |
| `rpc.response.status_code` | *(Missing)* | **FAIL** | e.g., `"INVALID_ARGUMENT"` |
| `error.type` | *(Missing)* | **FAIL** | e.g., `"INVALID_ARGUMENT"` |
| `gcp.client.service` | *(Missing)* | **FAIL** | Expected `"showcase"` |
| *(None - PRD requires removal)* | `gcp.client.language` | **FAIL** | Currently emitted but must be removed |

### F1.3 & F1.4 HTTP/JSON: Server & Client Failures
*   **Methodology:** Execute `Echo` requests over HTTP transport that return failures. Inspect the captured `SpanData`.

| Expected Attribute / Metadata | Observed Attribute / Metadata | Status | Notes |
| :--- | :--- | :--- | :--- |
| `rpc.system.name` | `rpc.system.name` | **PASS** | |
| `server.address` | `server.address` | **PASS** | |
| `server.port` | `server.port` | **PASS** | |
| `gcp.client.repo` | `gcp.client.repo` | **PASS** | |
| `gcp.client.version` | `gcp.client.version` | **PASS** | |
| `gcp.client.artifact` | `gcp.client.artifact` | **PASS** | |
| `rpc.method` | *(Missing)* | **FAIL** | Expected `"google.showcase.v1beta1.Echo/Echo"` |
| `url.domain` | *(Missing)* | **FAIL** | |
| `url.full` | *(Missing)* | **FAIL** | |
| `rpc.response.status_code` | *(Missing)* | **FAIL** | e.g., `"INVALID_ARGUMENT"` |
| `http.response.status_code` | *(Missing)* | **FAIL** | e.g., `400` or `503` |
| `error.type` | *(Missing)* | **FAIL** | e.g., `"INVALID_ARGUMENT"` |
| `gcp.client.service` | *(Missing)* | **FAIL** | Expected `"showcase"` |
| *(None - PRD requires removal)* | `gcp.client.language` | **FAIL** | Currently emitted but must be removed |

---

## F2: Metrics

### F2.1 gRPC & HTTP: No metrics emitted unless enabled
*   **Methodology:** Execute RPCs without configuring the OpenTelemetry SDK.
*   **Result:** **PASS**. No `MetricData` is exported.

### F2.2 gRPC: Client Request Duration (`gcp.client.request.duration`)
*   **Methodology:** Extract `MetricData` histograms. Validate explicit bucket boundaries (`[0, 0.0001, 0.0005, ... 3600.0]`) and point dimensions on success and failure.
*   **Buckets Validation:** **PASS**.

| Expected Attribute Dimension | Observed Attribute Dimension | Status | Notes |
| :--- | :--- | :--- | :--- |
| `rpc.system.name` | `rpc.system.name` | **PASS** | |
| `rpc.method` | `rpc.method` | **PASS** | |
| `server.address` | `server.address` | **PASS** | |
| `server.port` | `server.port` | **PASS** | |
| `gcp.client.repo` | `gcp.client.repo` | **PASS** | |
| `gcp.client.version` | `gcp.client.version` | **PASS** | |
| `gcp.client.artifact` | `gcp.client.artifact` | **PASS** | |
| `rpc.response.status_code` | `rpc.response.status_code` | **PASS** | |
| `error.type` | *(Missing)* | **FAIL** | Dimension missing from failed requests |
| `gcp.client.service` | *(Missing)* | **FAIL** | Dimension completely missing |

### F2.2 HTTP/JSON: Client Request Duration (`gcp.client.request.duration`)
*   **Methodology:** Extract `MetricData` histograms for REST calls.
*   **Buckets Validation:** **PASS**.

| Expected Attribute Dimension | Observed Attribute Dimension | Status | Notes |
| :--- | :--- | :--- | :--- |
| `rpc.system.name` | `rpc.system.name` | **PASS** | |
| `server.address` | `server.address` | **PASS** | |
| `server.port` | `server.port` | **PASS** | |
| `gcp.client.repo` | `gcp.client.repo` | **PASS** | |
| `gcp.client.version` | `gcp.client.version` | **PASS** | |
| `gcp.client.artifact` | `gcp.client.artifact` | **PASS** | |
| `rpc.response.status_code` | `rpc.response.status_code` | **PASS** | |
| `http.response.status_code` | *(Missing)* | **FAIL** | Dimension completely missing |
| `error.type` | *(Missing)* | **FAIL** | Dimension missing from failed requests |
| `gcp.client.service` | *(Missing)* | **FAIL** | Dimension completely missing |

---

## F3: Actionable Error Logging

### F3.1 & F3.3 gRPC & HTTP: Baseline Logging
*   **Methodology:** Assert that no logs are emitted when the tracer is disabled (F3.1) and that successful RPCs do not emit L4 debug logs (F3.3).
*   **Result:** **PASS**. 

### F3.4 gRPC & HTTP: L4 Per-RPC Error Logs
*   **Methodology:** Execute failing requests, injecting `google.rpc.ErrorInfo` trailers to force metadata evaluation. Intercept SLF4J MDC mappings via Logback `ILoggingEvent` and `java.util.logging.LogRecord`.
*   **Result:** The tracer correctly identifies the failure and captures the `ErrorInfo` payload properties flawlessly via SLF4J MDC, but it misses several client identifiers.

| Expected MDC Attribute | Observed MDC Attribute | Status | Notes |
| :--- | :--- | :--- | :--- |
| `rpc.system.name` | `rpc.system.name` | **PASS** | |
| `rpc.method` | `rpc.method` | **PASS** | |
| `rpc.response.status_code` | `rpc.response.status_code` | **PASS** | |
| `error.type` | `error.type` | **PASS** | Successfully extracted from `ErrorInfo.reason` |
| `gcp.errors.domain` | `gcp.errors.domain` | **PASS** | Successfully extracted from `ErrorInfo.domain` |
| `gcp.errors.metadata.*` | `gcp.errors.metadata.*` | **PASS** | Custom map successfully flattened |
| `gcp.client.service` | *(Missing)* | **FAIL** | |
| `gcp.client.repo` | *(Missing)* | **FAIL** | |
| `gcp.client.version` | *(Missing)* | **FAIL** | |

### F3.4 gRPC & HTTP: L2/L3 Terminal API Error Logs (WARN)
*   **Methodology:** Observe logs emitted for the top-level API surface exception.
*   **Result:** **FAIL**. The underlying `LoggingTracer` implementation does not yet format or emit the L2/L3 `WARN`-level terminal failure logs containing `exception.type`, `exception.message`, and `exception.stacktrace`. Currently, only L4 `DEBUG` logs are printed natively.

---

## F4: Integration & Telemetry

### F4.1 - F4.7: Google Cloud Direct Integration
*   **Methodology:** Use `AutoConfiguredOpenTelemetrySdk` with `opentelemetry-gcp-auth-extension` in the `java-bigquery` snippet and `gapic-showcase` modules. 
*   **Result:** **PASS**. When configured to export to `https://telemetry.googleapis.com` with `http/protobuf`, the telemetry gracefully handles Application Default Credentials and correctly serializes to the backend. The integration successfully skips tests when `GOOGLE_CLOUD_PROJECT` is absent, preventing CI build breaks in unauthenticated environments.