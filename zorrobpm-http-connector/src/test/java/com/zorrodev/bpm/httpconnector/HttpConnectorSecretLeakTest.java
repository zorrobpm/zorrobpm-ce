package com.zorrodev.bpm.httpconnector;

import ch.qos.logback.classic.Level;
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
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A secret from {@code authRef} (a query parameter) must not reach the logs, the process variables or
 * the BPMN error message of the engine when the URL, a redirect, a header or the transport misbehaves.
 * A real local HTTP server, the {@code http.error} variable and the error message captured from the
 * mocked {@code ActivityService}, and the logs captured through a logback {@code ListAppender}.
 */
class HttpConnectorSecretLeakTest {

    /** A synthetic secret with characters that change when it is URL-encoded. */
    private static final String QUERY_CANARY = "K3y w1th %enc& specials=+";
    private static final String ENCODED_CANARY =
        URLEncoder.encode(QUERY_CANARY, StandardCharsets.UTF_8);
    private static final String REDIRECT_CANARY = "REFLECTED-99-canary";
    private static final String HEADER_CANARY = "tok-canary-LEAK";

    private HttpServer server;
    private String baseUrl;
    private volatile String lastRequestMethod;

    private final ActivityService activityService = mock(ActivityService.class);

    @BeforeEach
    void stubEngineOutcome() {
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
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            int status = 200;
            if ("/redirect-bad-location".equals(path)) {
                // A broken Location: a leading space (strip eats it) plus an inner space (URI.resolve
                // throws an IAE with the Location in its message).
                status = 302;
                exchange.getResponseHeaders().add("Location",
                    " /evil path?api_key=" + REDIRECT_CANARY);
                body = new byte[0];
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                body = "{\"paid\":true}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpConnectorWorker worker(int maxRedirects) {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts("127.0.0.1");
        props.setAllowPrivateNetworks(true);
        props.setMaxRedirects(maxRedirects);
        props.getSecrets().put("qk",
            "{\"type\":\"apiKey\",\"name\":\"api_key\",\"value\":\"" + QUERY_CANARY + "\",\"in\":\"query\"}");
        props.getSecrets().put("bk",
            // Java "\\n" becomes the JSON "\n" escape, which is a real LF in the token after parsing (a raw LF in the
            // JSON text is invalid JSON and would take another branch).
            "{\"type\":\"bearer\",\"token\":\"line1\\n" + HEADER_CANARY + "\"}");
        return new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);
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

    /** Attaches an appender to the logger; the caller has to detach it in a finally. */
    private static ListAppender<ILoggingEvent> attachAppender(Class<?> loggedClass) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggedClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void assertNoCanary(List<ILoggingEvent> events, String... canaries) {
        for (ILoggingEvent event : events) {
            for (String canary : canaries) {
                assertThat(event.getFormattedMessage())
                    .as("a log record must not contain the secret (%s)", canary)
                    .doesNotContain(canary);
            }
        }
    }

    /** The diagnostic of the error: the {@code http.error} variable of the BPMN error. */
    @SuppressWarnings("unchecked")
    private String httpErrorVar(JobDetailModel model) {
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), any(), captor.capture());
        List<com.zorrodev.bpm.contract.model.ProcessVariable> vars = captor.getValue();
        return vars.stream().filter(v -> "http.error".equals(v.getName())).findFirst()
            .orElseThrow(() -> new AssertionError("no http.error in " + vars)).getValue();
    }

    /**
     * The message of the BPMN error is what the engine puts into the incident, so it is a second place
     * where a secret could leak. This reads it back from the engine call.
     */
    private String bpmnErrorMessage(JobDetailModel model) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_CONFIG), captor.capture(), any());
        return captor.getValue();
    }

    // --- a broken URL with a secret in the query ---

    @Test
    void malformedUrlWithQuerySecret_leaksNothingToLogOrVars() {
        ListAppender<ILoggingEvent> appender = attachAppender(HttpConnectorWorker.class);
        try {
            JobDetailModel model = job(
                pv("http.url", baseUrl + "/bad path", "STRING"),
                pv("http.authType", "apiKey", "STRING"),
                pv("http.authRef", "qk", "STRING"));

            List<ProcessVariable> result = worker(0).handleJob(model);

            assertThat(result).isEmpty();
            String httpError = httpErrorVar(model);
            // Neither the raw nor the URL-encoded secret (URI.create quotes the whole input).
            assertThat(httpError).doesNotContain(QUERY_CANARY).doesNotContain(ENCODED_CANARY);
            // The bare URL is validated first, so the secret was not even pasted on: the message holds
            // no masked remainder either, and the sanitizer is not what saved it here.
            assertThat(httpError).doesNotContain("api_key=***");
            // The same for the message of the BPMN error, which is what the incident gets.
            assertThat(bpmnErrorMessage(model)).doesNotContain(QUERY_CANARY).doesNotContain(ENCODED_CANARY);
            assertNoCanary(appender.list, QUERY_CANARY, ENCODED_CANARY);
            // A broken URL must not cause an outbound request at all.
            assertThat(lastRequestMethod).as("the server saw no request at all").isNull();
        } finally {
            ((Logger) LoggerFactory.getLogger(HttpConnectorWorker.class)).detachAppender(appender);
        }
    }

    @Test
    void ssrfGateDebugLog_hidesQuerySecret() {
        Logger gateLogger = (Logger) LoggerFactory.getLogger(HttpSsrfGate.class);
        Level saved = gateLogger.getLevel();
        gateLogger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = attachAppender(HttpSsrfGate.class);
        try {
            List<ProcessVariable> result = worker(0).handleJob(job(
                pv("http.url", baseUrl + "/ok", "STRING"),
                pv("http.authType", "apiKey", "STRING"),
                pv("http.authRef", "qk", "STRING")));

            assertThat(result).isNotEmpty();
            verify(activityService, never()).throwBpmnError(any(), any(), any(), any());
            assertNoCanary(appender.list, QUERY_CANARY, ENCODED_CANARY);
        } finally {
            gateLogger.detachAppender(appender);
            gateLogger.setLevel(saved);
        }
    }

    // --- redirects, headers, transport ---

    @Test
    void malformedRedirectLocation_noSecretInDiag() {
        ListAppender<ILoggingEvent> appender = attachAppender(HttpConnectorWorker.class);
        try {
            JobDetailModel model = job(
                pv("http.url", baseUrl + "/redirect-bad-location", "STRING"),
                pv("http.authType", "apiKey", "STRING"),
                pv("http.authRef", "qk", "STRING"));

            List<ProcessVariable> result = worker(3).handleJob(model);

            assertThat(result).isEmpty();
            assertThat(httpErrorVar(model)).doesNotContain(REDIRECT_CANARY);
            assertThat(bpmnErrorMessage(model)).doesNotContain(REDIRECT_CANARY);
            assertNoCanary(appender.list, REDIRECT_CANARY);
        } finally {
            ((Logger) LoggerFactory.getLogger(HttpConnectorWorker.class)).detachAppender(appender);
        }
    }

    @Test
    void illegalHeaderCharsFromSecret_noSecretInDiag() {
        // A secret with \n: the JDK header validation throws an IAE quoting the value.
        ListAppender<ILoggingEvent> appender = attachAppender(HttpConnectorWorker.class);
        try {
            JobDetailModel model = job(
                pv("http.url", baseUrl + "/ok", "STRING"),
                pv("http.authType", "bearer", "STRING"),
                pv("http.authRef", "bk", "STRING"));

            List<ProcessVariable> result = worker(0).handleJob(model);

            assertThat(result).isEmpty();
            assertThat(httpErrorVar(model)).doesNotContain(HEADER_CANARY);
            assertThat(bpmnErrorMessage(model)).doesNotContain(HEADER_CANARY);
            assertNoCanary(appender.list, HEADER_CANARY);
        } finally {
            ((Logger) LoggerFactory.getLogger(HttpConnectorWorker.class)).detachAppender(appender);
        }
    }

    @Test
    void illegalHeaderCharsFromCustomApiKeyHeader_noSecretInDiag() {
        // An apiKey in a header with an arbitrary name, without a Bearer/Basic prefix: the JDK quotes
        // the raw value.
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setEnabled(true);
        props.setAllowedHosts("127.0.0.1");
        props.setAllowPrivateNetworks(true);
        props.getSecrets().put("ck",
            "{\"type\":\"apiKey\",\"name\":\"X-Key\",\"value\":\"line1\\n" + HEADER_CANARY + "\",\"in\":\"header\"}");
        HttpConnectorWorker w =
            new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);
        JobDetailModel model = job(
            pv("http.url", baseUrl + "/ok", "STRING"),
            pv("http.authType", "apiKey", "STRING"),
            pv("http.authRef", "ck", "STRING"));

        ListAppender<ILoggingEvent> appender = attachAppender(HttpConnectorWorker.class);
        try {
            List<ProcessVariable> result = w.handleJob(model);

            assertThat(result).isEmpty();
            assertThat(httpErrorVar(model)).doesNotContain(HEADER_CANARY);
            assertThat(bpmnErrorMessage(model)).doesNotContain(HEADER_CANARY);
            assertNoCanary(appender.list, HEADER_CANARY);
        } finally {
            ((Logger) LoggerFactory.getLogger(HttpConnectorWorker.class)).detachAppender(appender);
        }
    }

    @Test
    void transportFailure_noSecretInDiag_transientSemanticsKept() {
        // A dead port: transient (FAILED, then retries, NOT a BPMN error), and no secret anywhere.
        ListAppender<ILoggingEvent> appender = attachAppender(HttpConnectorWorker.class);
        try {
            JobDetailModel model = job(
                pv("http.url", "http://127.0.0.1:1/unreachable", "STRING"),
                pv("http.authType", "apiKey", "STRING"),
                pv("http.authRef", "qk", "STRING"));

            assertThatThrownBy(() -> worker(0).handleJob(model))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(QUERY_CANARY)
                .hasMessageNotContaining(ENCODED_CANARY);
            verify(activityService, never()).throwBpmnError(any(), any(), any(), any());
            assertNoCanary(appender.list, QUERY_CANARY, ENCODED_CANARY);
        } finally {
            ((Logger) LoggerFactory.getLogger(HttpConnectorWorker.class)).detachAppender(appender);
        }
    }

    // --- the sanitizer: a table of hostile inputs ---

    @Test
    void sanitizeDiag_redactsSecretsAndBoundsSize() {
        // The raw and the encoded secret from the URI.create quote.
        assertThat(HttpConnectorWorker.sanitizeDiag(
            "Illegal character in path at index 26: http://h/bad path?api_key=" + QUERY_CANARY))
            .doesNotContain(QUERY_CANARY, ENCODED_CANARY)
            .contains("api_key=***");
        assertThat(HttpConnectorWorker.sanitizeDiag(
            "Illegal character in path at index 26: http://h/bad path?api_key=" + ENCODED_CANARY))
            .doesNotContain(QUERY_CANARY, ENCODED_CANARY)
            .contains("api_key=***");
        // A raw secret with spaces, reflected in a Location: cut whole, not up to the first space.
        assertThat(HttpConnectorWorker.sanitizeDiag(
            "Illegal character in path at index 5: /evil path?api_key=K3y w1th %enc"))
            .doesNotContain("K3y", "w1th");
        assertThat(HttpConnectorWorker.sanitizeDiag(
            "Illegal character in path at index 5: /evil path?api_key=" + REDIRECT_CANARY))
            .doesNotContain(REDIRECT_CANARY)
            .contains("api_key=***");
        // The quoted header value of the JDK, including a \n inside the quotes.
        assertThat(HttpConnectorWorker.sanitizeDiag(
            "invalid header value: \"Bearer line1\n" + HEADER_CANARY + "\""))
            .doesNotContain(HEADER_CANARY)
            .contains("Bearer ***");
        // An apiKey in a header with an arbitrary name: the JDK quotes the raw value without a scheme
        // prefix.
        assertThat(HttpConnectorWorker.sanitizeDiag(
            "invalid header value: \"X-Key line1\n" + HEADER_CANARY + "\""))
            .doesNotContain(HEADER_CANARY);
        assertThat(HttpConnectorWorker.sanitizeDiag("Basic dXNlcjpwYXNz wires down"))
            .doesNotContain("dXNlcjpwYXNz")
            .contains("Basic ***");
        // Userinfo in a URL.
        assertThat(HttpConnectorWorker.sanitizeDiag("only http/https (got scheme 'http://admin:s3cret@h/x')"))
            .doesNotContain("s3cret")
            .contains("://***@");
        // The size bound: sanitization runs before the truncation.
        String huge = "x".repeat(10_000) + "?api_key=" + QUERY_CANARY + "y".repeat(10_000);
        String redacted = HttpConnectorWorker.sanitizeDiag(huge);
        assertThat(redacted).doesNotContain(QUERY_CANARY, ENCODED_CANARY);
        assertThat(redacted.length())
            .isLessThanOrEqualTo(HttpConnectorWorker.MAX_DIAG_CHARS + 20);
        // Null safety.
        assertThat(HttpConnectorWorker.sanitizeDiag(null)).isEmpty();
    }

    // --- the connector is disabled by default ---

    @Test
    void connectorDisabledByDefault_noSecretsNeeded() {
        assertThat(new HttpConnectorProperties().isEnabled()).isFalse();
        JobDetailModel model = job(pv("http.url", baseUrl + "/ok", "STRING"));

        HttpConnectorProperties props = new HttpConnectorProperties();
        HttpConnectorWorker w =
            new HttpConnectorWorker(props, new HttpSsrfGate(props), activityService);
        List<ProcessVariable> result = w.handleJob(model);

        assertThat(result).isEmpty();
        verify(activityService).throwBpmnError(eq(model.getServiceTaskId()),
            eq(HttpConnectorWorker.ERR_DISABLED), any(), any());
    }
}