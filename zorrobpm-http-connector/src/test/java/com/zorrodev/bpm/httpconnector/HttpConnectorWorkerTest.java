package com.zorrodev.bpm.httpconnector;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import com.zorrodev.bpm.engine.dto.BpmnErrorOutcome;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The worker against a real local HTTP server (the JDK {@code HttpServer} and a real {@code HttpClient},
 * not a mocked socket).
 *
 * <p>The server listens on loopback, so the tests run with {@code allow-private-networks=true} (a
 * dev-like configuration of the test bench, not the production default — the production default of
 * {@code false} is proven separately in {@link HttpSsrfGateTest}).
 */
class HttpConnectorWorkerTest {

    /** How many bytes the slow server (1 byte/s) manages to send. */
    private static final int SLOW_BODY_BYTES = 12;

    private HttpServer server;
    private String baseUrl;
    /** A second origin (another port): the target of the cross-origin redirect. */
    private HttpServer secondServer;
    private String secondBaseUrl;
    private final Map<String, String> secondServerHeaders = new ConcurrentHashMap<>();
    private final Map<String, String> lastRequestHeaders = new ConcurrentHashMap<>();
    private volatile String lastRequestBody;
    private volatile String lastRequestQuery;
    private volatile String lastRequestMethod;

    private final ActivityService activityService = mock(ActivityService.class);

    @BeforeEach
    void stubEngineOutcome() {
        // The engine always answers with an outcome; the tests that care about it stub their own.
        when(activityService.throwBpmnError(any(), any(), any(), any()))
            .thenReturn(new BpmnErrorOutcome(true, "boundaryEvent", UUID.randomUUID(), null));
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        server.createContext("/", exchange -> {
            lastRequestMethod = exchange.getRequestMethod();
            lastRequestQuery = exchange.getRequestURI().getRawQuery();
            exchange.getRequestHeaders().forEach((k, v) -> lastRequestHeaders.put(k.toLowerCase(), String.join(",", v)));
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            int status = 200;
            switch (path) {
                case "/ok" -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.getResponseHeaders().add("Set-Cookie", "session=secret; Path=/");
                    exchange.getResponseHeaders().add("X-Custom", "yes");
                    body = "{\"paid\":true}".getBytes(StandardCharsets.UTF_8);
                }
                case "/text" -> {
                    exchange.getResponseHeaders().add("Content-Type", "text/plain");
                    body = "plain-ok".getBytes(StandardCharsets.UTF_8);
                }
                case "/missing" -> {
                    status = 404;
                    body = "nope".getBytes(StandardCharsets.UTF_8);
                }
                case "/fail" -> {
                    status = 503;
                    body = "busy".getBytes(StandardCharsets.UTF_8);
                }
                case "/echo" -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    body = lastRequestBody.getBytes(StandardCharsets.UTF_8);
                }
                case "/huge" -> {
                    body = new byte[2 * 1024 * 1024];
                }
                case "/slow-body" -> {
                    // The headers go out at once, the body one byte per second. This is the scenario
                    // that used to hold a worker thread: HttpRequest.timeout of the JDK applies until
                    // the headers, so such a server keeps the thread busy without a bound.
                    exchange.getResponseHeaders().add("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        for (int i = 0; i < SLOW_BODY_BYTES; i++) {
                            os.write('x');
                            os.flush();
                            Thread.sleep(1000);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }
                case "/redirect-cross-origin" -> {
                    status = 302;
                    exchange.getResponseHeaders().add("Location", secondBaseUrl + "/ok");
                    body = new byte[0];
                }
                case "/redirect" -> {
                    status = 302;
                    exchange.getResponseHeaders().add("Location", "/ok");
                    body = new byte[0];
                }
                case "/redirect-evil" -> {
                    status = 302;
                    exchange.getResponseHeaders().add("Location", "http://169.254.169.254/");
                    body = new byte[0];
                }
                default -> {
                    status = 404;
                    body = "unknown".getBytes(StandardCharsets.UTF_8);
                }
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        // A separate origin, to prove that a secret does not go to another host.
        secondServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secondBaseUrl = "http://127.0.0.1:" + secondServer.getAddress().getPort();
        secondServer.createContext("/", exchange -> {
            exchange.getRequestHeaders().forEach((k, v) -> secondServerHeaders.put(k.toLowerCase(), String.join(",", v)));
            byte[] body = "second-origin".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        secondServer.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        secondServer.stop(0);
    }

    private HttpConnectorWorker worker(boolean enabled, String allowedHosts, boolean allowPrivate,
            long maxResponseBytes, int maxRedirects) {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(enabled);
        props.setAllowedHosts(allowedHosts);
        props.setAllowPrivateNetworks(allowPrivate);
        props.setMaxResponseBytes(maxResponseBytes);
        props.setMaxRedirects(maxRedirects);
        return new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);
    }

    private HttpConnectorWorker localWorker() {
        return worker(true, "127.0.0.1", true, 1024 * 1024, 0);
    }

    /** The same bench, with a short http.readTimeout. */
    private HttpConnectorWorker workerWithReadTimeout(int readTimeoutSeconds, int maxRedirects) {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts("127.0.0.1");
        props.setAllowPrivateNetworks(true);
        props.setMaxResponseBytes(1024 * 1024);
        props.setMaxRedirects(maxRedirects);
        props.setDefaultReadTimeoutSeconds(readTimeoutSeconds);
        return new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);
    }

    private String bearerSecret() {
        return "s3cr3t-token";
    }

    private static ProcessVariable pv(String name, String value, String type) {
        ProcessVariable v = new ProcessVariable();
        v.setName(name);
        v.setValue(value);
        v.setType(type);
        return v;
    }

    private static JobDetailModel job(ProcessVariable... vars) {
        JobDetailModel model = new JobDetailModel();
        model.setServiceTaskId(UUID.randomUUID());
        model.setProcessInstanceId(UUID.randomUUID());
        model.setJob("zorrobpm:http");
        Map<String, ProcessVariable> map = new HashMap<>();
        for (ProcessVariable v : vars) {
            map.put(v.getName(), v);
        }
        model.setVariables(map);
        return model;
    }

    private static String varValue(List<ProcessVariable> vars, String name) {
        return vars.stream().filter(v -> name.equals(v.getName())).findFirst()
            .orElseThrow(() -> new AssertionError("no variable '" + name + "' in " + vars)).getValue();
    }

    private static ListAppender<ILoggingEvent> attachAppender(Class<?> loggedClass) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggedClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(Class<?> loggedClass, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(loggedClass)).detachAppender(appender);
    }

    private static List<String> messagesOf(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    // --- the SUCCESS path ---

    @Test
    void get_ok_returnsFlatTriple() {
        List<ProcessVariable> result = localWorker().handleJob(job(pv("http.url", baseUrl + "/ok", "STRING")));

        assertThat(varValue(result, "http.status")).isEqualTo("200");
        assertThat(varValue(result, "http.body")).isEqualTo("{\"paid\":true}");
        assertThat(result.stream().filter(v -> "http.body".equals(v.getName())).findFirst().orElseThrow().getType())
            .isEqualTo("JSON");
        verify(activityService, never()).throwBpmnError(any(), any(), any(), any());
    }

    @Test
    void responseHeaders_availableToMapping_butSetCookieFiltered() {
        List<ProcessVariable> result = localWorker().handleJob(job(pv("http.url", baseUrl + "/ok", "STRING")));

        String headersJson = varValue(result, "http.headers");
        // Set-Cookie is filtered out by default.
        assertThat(headersJson).doesNotContain("session=secret").doesNotContain("set-cookie");
        assertThat(headersJson).contains("x-custom");
    }

    @Test
    void method_headers_query_body_postEcho() {
        List<ProcessVariable> result = localWorker().handleJob(job(
            pv("http.url", baseUrl + "/echo", "STRING"),
            pv("http.method", "POST", "STRING"),
            pv("http.headers", "{\"X-Tenant\":\"t1\"}", "JSON"),
            pv("http.queryParameters", "{\"page\":\"2\"}", "JSON"),
            pv("http.body", "{\"a\":1}", "JSON")));

        assertThat(varValue(result, "http.status")).isEqualTo("200");
        assertThat(varValue(result, "http.body")).isEqualTo("{\"a\":1}");
        assertThat(lastRequestHeaders.get("x-tenant")).isEqualTo("t1");
        assertThat(lastRequestQuery).isEqualTo("page=2");
        assertThat(lastRequestMethod).isEqualTo("POST");
    }

    // --- secrets as one environment variable ---

    private HttpConnectorWorker workerWithSecretsJson(String secretsJson) {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts("127.0.0.1");
        props.setAllowPrivateNetworks(true);
        props.setSecretsJson(secretsJson);
        return new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);
    }

    /**
     * A secret that arrives as one JSON object (what a deployment really passes) works like any other
     * authRef. Without the parsing of the blob the authRef is "unknown" and the task gets the BPMN
     * error HTTP_CONNECTOR_CONFIG instead of 200.
     */
    @Test
    void bearerAuth_fromSecretsJsonBlob_sentAsHeader() {
        List<ProcessVariable> result = workerWithSecretsJson(
            "{\"svc\":{\"type\":\"bearer\",\"token\":\"" + bearerSecret() + "\"}}")
            .handleJob(job(
                pv("http.url", baseUrl + "/ok", "STRING"),
                pv("http.authType", "bearer", "STRING"),
                pv("http.authRef", "svc", "STRING")));

        assertThat(varValue(result, "http.status")).isEqualTo("200");
        assertThat(lastRequestHeaders.get("authorization")).isEqualTo("Bearer " + bearerSecret());
    }

    /** A typo in the secrets has to fail the startup, not the first request in production. */
    @Test
    void secretsJson_malformed_failsFastAtConstruction() {
        assertThatThrownBy(() -> workerWithSecretsJson("{not-json"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("secrets-json");
        assertThatThrownBy(() -> workerWithSecretsJson("[\"svc\"]"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must be a JSON object");
    }

    /** An explicit zorrobpm.http-connector.secrets entry wins over the blob. */
    @Test
    void explicitSecretOverridesSecretsJsonBlob() {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts("127.0.0.1");
        props.setAllowPrivateNetworks(true);
        props.setSecretsJson("{\"svc\":{\"type\":\"bearer\",\"token\":\"from-blob\"}}");
        props.getSecrets().put("svc", "{\"type\":\"bearer\",\"token\":\"from-properties\"}");
        HttpConnectorWorker worker =
            new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);

        worker.handleJob(job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.authType", "bearer", "STRING"),
            pv("http.authRef", "svc", "STRING")));

        assertThat(lastRequestHeaders.get("authorization")).isEqualTo("Bearer from-properties");
    }

    // --- the slow body deadline and the redirect origin ---

    /**
     * A slow server (1 byte/s) must not hold the worker thread longer than http.readTimeout: with
     * readTimeout=2s a call used to run for 12.3s.
     *
     * <p>Without the deadline the body is read without a bound — the call succeeds after ~12s
     * (SLOW_BODY_BYTES bytes, one per second) and the test fails on "a timeout was expected, 200
     * came back". With the deadline the exchange breaks there: an HttpTimeoutException, the same class
     * the JDK itself throws, so it takes the transient branch (FAILED, then retries) and not the
     * deterministic error code for an invalid configuration.
     */
    @Test
    void slowResponseBody_abortedByReadTimeout() {
        HttpConnectorWorker worker = workerWithReadTimeout(2, 0);
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> worker.handleJob(job(
            pv("http.url", baseUrl + "/slow-body", "STRING"))))
            .isInstanceOf(IllegalStateException.class)
            .hasRootCauseInstanceOf(java.net.http.HttpTimeoutException.class);

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(elapsedMs)
            .as("the exchange was broken at the deadline (~2s), not after %s bytes per second", SLOW_BODY_BYTES)
            .isLessThan(SLOW_BODY_BYTES * 1000L);
        // Transient (retries), not a deterministic BPMN error without retries: a slow server is not an
        // "invalid configuration".
        verify(activityService, never()).throwBpmnError(any(), eq("HTTP_CONNECTOR_CONFIG"), any(), any());
    }

    /**
     * A secret from authRef goes to the origin of the original request and is NOT repeated on a
     * redirect hop that changes origin, even when both hosts are allowed (otherwise the secret leaks
     * to a third party we already trust).
     *
     * <p>Without that rule the second server receives Authorization and the assertion fails.
     */
    @Test
    void bearerSecret_notForwardedToCrossOriginRedirectHop() {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts("127.0.0.1");
        props.setAllowPrivateNetworks(true);
        props.setMaxRedirects(3);
        props.getSecrets().put("svc", "{\"type\":\"bearer\",\"token\":\"" + bearerSecret() + "\"}");
        HttpConnectorWorker worker =
            new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);

        List<ProcessVariable> result = worker.handleJob(job(
            pv("http.url", baseUrl + "/redirect-cross-origin", "STRING"),
            pv("http.authType", "bearer", "STRING"),
            pv("http.authRef", "svc", "STRING")));

        // The redirect worked — the target origin answered with its own body.
        assertThat(varValue(result, "http.status")).isEqualTo("200");
        assertThat(varValue(result, "http.body")).isEqualTo("second-origin");
        // The first hop is its own origin, the secret is legitimate there.
        assertThat(lastRequestHeaders.get("authorization")).isEqualTo("Bearer " + bearerSecret());
        // The second hop is a foreign origin (another port): no secret there.
        assertThat(secondServerHeaders.get("authorization"))
            .as("Authorization must not cross the origin boundary")
            .isNull();
    }

    @Test
    void bearerAuth_fromSecretRef_sentAsHeader() {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts("127.0.0.1");
        props.setAllowPrivateNetworks(true);
        props.getSecrets().put("svc", "{\"type\":\"bearer\",\"token\":\"tok123\"}");
        HttpConnectorWorker worker =
            new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);

        worker.handleJob(job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.authType", "bearer", "STRING"),
            pv("http.authRef", "svc", "STRING")));

        assertThat(lastRequestHeaders.get("authorization")).isEqualTo("Bearer tok123");
    }

    @Test
    void apiKeyAuth_inQuery_appendedToUrl() {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts("127.0.0.1");
        props.setAllowPrivateNetworks(true);
        props.getSecrets().put("k", "{\"type\":\"apiKey\",\"name\":\"api_key\",\"value\":\"v1\",\"in\":\"query\"}");
        HttpConnectorWorker worker =
            new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);

        worker.handleJob(job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.authType", "apiKey", "STRING"),
            pv("http.authRef", "k", "STRING")));

        assertThat(lastRequestQuery).isEqualTo("api_key=v1");
    }

    // --- non-2xx strictly through a BPMN error ---

    @Test
    void notFound_throwsBpmnError_http404_withVars() {
        JobDetailModel model = job(pv("http.url", baseUrl + "/missing", "STRING"));

        List<ProcessVariable> result = localWorker().handleJob(model);

        assertThat(result).isEmpty();
        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()), eq("HTTP_404"), any(), any());
    }

    @Test
    void serviceUnavailable_throwsBpmnError_http503() {
        JobDetailModel model = job(pv("http.url", baseUrl + "/fail", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()), eq("HTTP_503"), any(), any());
    }

    // --- the outcome of the BPMN error, as reported by the engine ---

    /**
     * An uncaught error leaves an incident on the service task: the worker logs that incident id and
     * sends no completion. Inverting the {@code caught} mapping in the worker makes it log a boundary
     * event instead, which this test catches.
     */
    @Test
    void bpmnError_uncaught_incidentIdReported_noCompletionSent() {
        UUID incidentId = UUID.randomUUID();
        when(activityService.throwBpmnError(any(), any(), any(), any()))
            .thenReturn(new BpmnErrorOutcome(false, null, UUID.randomUUID(), incidentId));
        JobDetailModel model = job(pv("http.url", baseUrl + "/missing", "STRING"));
        ListAppender<ILoggingEvent> appender = attachAppender(HttpConnectorWorker.class);

        try {
            List<ProcessVariable> result = localWorker().handleJob(model);

            assertThat(result).isEmpty();
            verify(activityService).throwBpmnError(eq(model.getServiceTaskId()), eq("HTTP_404"),
                eq("HTTP 404"), any());
            assertThat(messagesOf(appender))
                .anyMatch(m -> m.contains("was not caught") && m.contains("incident " + incidentId))
                .noneMatch(m -> m.contains("caught by boundary event"));
        } finally {
            detachAppender(HttpConnectorWorker.class, appender);
        }
    }

    /**
     * An error boundary event caught the error: the worker logs the boundary event id. The mirror of
     * the incident case, and the second half of the mutation that {@code caught} must survive.
     */
    @Test
    void bpmnError_caught_boundaryEventReported_noCompletionSent() {
        String boundaryEventId = "onFailureBoundary";
        when(activityService.throwBpmnError(any(), any(), any(), any()))
            .thenReturn(new BpmnErrorOutcome(true, boundaryEventId, UUID.randomUUID(), null));
        JobDetailModel model = job(pv("http.url", baseUrl + "/missing", "STRING"));
        ListAppender<ILoggingEvent> appender = attachAppender(HttpConnectorWorker.class);

        try {
            List<ProcessVariable> result = localWorker().handleJob(model);

            assertThat(result).isEmpty();
            assertThat(messagesOf(appender))
                .anyMatch(m -> m.contains("caught by boundary event " + boundaryEventId))
                .noneMatch(m -> m.contains("incident "));
        } finally {
            detachAppender(HttpConnectorWorker.class, appender);
        }
    }

    // --- deterministic errors, without empty retries ---

    @Test
    void disabled_throwsHttpConnectorDisabled() {
        JobDetailModel model = job(pv("http.url", baseUrl + "/ok", "STRING"));

        List<ProcessVariable> result =
            worker(false, "127.0.0.1", true, 1024 * 1024, 0).handleJob(model);

        assertThat(result).isEmpty();
        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_DISABLED), any(), any());
    }

    @Test
    void denyAll_whenAllowlistEmpty() {
        JobDetailModel model = job(pv("http.url", baseUrl + "/ok", "STRING"));

        worker(true, "", true, 1024 * 1024, 0).handleJob(model);

        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any(), any());
    }

    @Test
    void missingUrl_rejected() {
        JobDetailModel model = job(pv("http.method", "GET", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any(), any());
    }

    @Test
    void badMethod_rejected() {
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.method", "TRACE", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any(), any());
    }

    @Test
    void getWithBody_rejected() {
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.method", "GET", "STRING"),
            pv("http.body", "x", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any(), any());
    }

    @Test
    void literalSecret_rejected_failClosed() {
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.auth", "{\"type\":\"bearer\",\"token\":\"leak\"}", "JSON"));

        localWorker().handleJob(model);

        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any(), any());
    }

    @Test
    void literalAuthorizationHeader_rejected() {
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.headers", "{\"Authorization\":\"Bearer leak\"}", "JSON"));

        localWorker().handleJob(model);

        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any(), any());
    }

    @Test
    void unknownAuthRef_rejected() {
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.authType", "bearer", "STRING"),
            pv("http.authRef", "nope", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any(), any());
    }

    @Test
    void oauthAuthType_rejected_notSupported() {
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.authType", "oauth-client-credentials", "STRING"),
            pv("http.authRef", "o", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any(), any());
    }

    @Test
    void timeoutBeyondCap_rejected() {
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.readTimeout", "9999", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any(), any());
    }

    @Test
    void oversizeBody_rejected_notTruncated() {
        // Oversized: rejected with a BPMN error, never silently truncated.
        JobDetailModel model = job(pv("http.url", baseUrl + "/huge", "STRING"));

        worker(true, "127.0.0.1", true, 1024, 0).handleJob(model);

        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any(), any());
    }

    @Test
    void redirect_notFollowed_byDefault() {
        // maxRedirects=0 (the default, mirroring followRedirects=false): a 302 beyond the limit is a
        // BPMN error.
        JobDetailModel model = job(pv("http.url", baseUrl + "/redirect", "STRING"));

        localWorker().handleJob(model);

        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any(), any());
    }

    @Test
    void redirect_followed_whenOptedIn_eachHopValidated() {
        List<ProcessVariable> result =
            worker(true, "127.0.0.1", true, 1024 * 1024, 3)
                .handleJob(job(pv("http.url", baseUrl + "/redirect", "STRING")));

        assertThat(varValue(result, "http.status")).isEqualTo("200");
        verify(activityService, never()).throwBpmnError(any(), any(), any(), any());
    }

    @Test
    void redirect_toPrivateHost_rejected_evenWhenOptedIn() {
        // A redirect to 169.254.169.254: the hop has to pass the gate (the allowlist is 127.0.0.1 only).
        JobDetailModel model = job(pv("http.url", baseUrl + "/redirect-evil", "STRING"));

        worker(true, "127.0.0.1", true, 1024 * 1024, 3).handleJob(model);

        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any(), any());
    }

    @Test
    void transientFailure_connectionRefused_goesFailed_notBpmnError() {
        // Nothing listens: a transient failure leaves as an exception and takes the FAILED path (the engine
        // retries), it does NOT become a BPMN error.
        JobDetailModel model = job(pv("http.url", "http://127.0.0.1:1/", "STRING"));

        assertThatThrownBy(() -> localWorker().handleJob(model))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("transient");
        verify(activityService, never()).throwBpmnError(any(), any(), any(), any());
    }

    @Test
    void malformedUrlWithInnerSpace_deterministicError_noRetries() {
        // An inner space makes URI.create throw an unchecked IAE: it has to become the BPMN error
        // ERR_CONFIG and not empty FAILED retries.
        JobDetailModel model = job(pv("http.url", "http://exa mple.com/", "STRING"));

        List<ProcessVariable> result = localWorker().handleJob(model);

        assertThat(result).isEmpty();
        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any(), any());
    }

    @Test
    void timeoutEdgeValues_rejected_deterministically() {
        // 0, a negative value, NaN and Infinity are deterministic rejections.
        for (String bad : new String[]{"0", "-5", "NaN", "Infinity"}) {
            JobDetailModel model = job(
                pv("http.url", baseUrl + "/ok", "STRING"),
                pv("http.readTimeout", bad, "STRING"));
            localWorker().handleJob(model);
            verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
                eq(HttpConnectorWorker.ERR_CONFIG), any(), any());
        }
    }

    @Test
    void jobType_isReservedHttp() {
        assertThat(localWorker().getJob()).isEqualTo("zorrobpm:http");
    }
}