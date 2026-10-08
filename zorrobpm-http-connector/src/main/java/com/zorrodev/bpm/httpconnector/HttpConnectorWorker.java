package com.zorrodev.bpm.httpconnector;

import com.zorrodev.bpm.engine.dto.BpmnErrorOutcome;
import com.zorrodev.bpm.engine.service.ActivityService;
import com.zorrodev.bpm.exchange.JobDetailModel;
import com.zorrodev.bpm.exchange.ProcessVariable;
import com.zorrodev.bpm.handler.JobHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Built-in worker of the HTTP/REST outbound connector.
 *
 * <p>A plain {@code SERVICE_TASK} with {@code zeebe:taskDefinition type="zorrobpm:http"} takes the
 * regular engine path without a single changed line: the engine hands the job over on the queue of
 * this job type and the handler starter subscribes this bean to it like any other worker. The
 * connector is configured through the input mapping of the task, under the reserved names
 * {@code http.*}: the input mapping has already evaluated FEEL and put the values into
 * {@code JobDetailModel.variables}.
 *
 * <p>Result contract: a flat triple that the ordinary output mapping of the element writes into
 * process variables, {@code http.status} (LONG), {@code http.headers} (JSON, without
 * {@code set-cookie}) and {@code http.body} (JSON or STRING, depending on the Content-Type). No
 * names are hardcoded beyond this triple.
 *
 * <p>Errors, strictly: {@code 2xx} completes the task; any other status throws the BPMN error
 * {@code HTTP_<status>} on the task, which an error boundary event catches or which opens an incident
 * on the task when nothing catches it; deterministic errors (connector disabled, SSRF rejection,
 * invalid configuration, oversized response, a literal secret) throw {@code HTTP_CONNECTOR_<X>}
 * without pointless retries; transient errors (DNS, connect, timeout) leave as an exception, so the
 * engine marks the job FAILED and retries it before opening an incident.
 */
@Slf4j
@Component
public class HttpConnectorWorker implements JobHandler {

    static final String JOB_TYPE = "zorrobpm:http";

    static final String ERR_DISABLED = "HTTP_CONNECTOR_DISABLED";
    static final String ERR_CONFIG = "HTTP_CONNECTOR_CONFIG";

    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final Set<String> REDIRECT_STATUSES = Set.of("301", "302", "303", "307", "308");

    /** Input names that must never carry a secret as a literal (only an authRef reference). */
    private static final Set<String> SECRET_LITERAL_INPUTS = Set.of(
        "http.auth", "http.apikey", "http.api-key", "http.token",
        "http.password", "http.clientsecret", "http.client-secret", "http.authorization");

    /** Response headers that are not available to the mapping; filtered out by default. */
    private static final Set<String> FILTERED_RESPONSE_HEADERS = Set.of("set-cookie");

    /**
     * Upper bound of a diagnostic message that reaches the log and the process variables.
     * Sanitization ({@link #sanitizeDiag}) runs before the truncation: the truncation bounds the
     * size, it does not hide secrets.
     */
    static final int MAX_DIAG_CHARS = 300;

    /**
     * The single place where diagnostics of this module are sanitized (the mirror of
     * {@link HttpSsrfGate}, the single place where a URL is checked). Every text that can reach the
     * log or the process variables (exception messages about a broken URL, a redirect, a header or the
     * transport, and the resulting URIs) goes through this method only; fixing up individual catch
     * blocks is not allowed.
     *
     * <p>What it cuts (the order matters, secrets first, size second):
     * <ol>
     *   <li>query parameter values ({@code ?name=value} / {@code &name=value} / {@code #name=value})
     *       — an apiKey in a query parameter lives exactly there;</li>
     *   <li>userinfo ({@code scheme://user:pass@host});</li>
     *   <li>{@code Bearer}/{@code Basic} schemes, both quoted (the JDK form
     *       {@code invalid header value: "Bearer ..."} with a possible {@code \n} inside the quotes)
     *       and bare;</li>
     *   <li>truncation to {@link #MAX_DIAG_CHARS}.</li>
     * </ol>
     *
     * <p>The bias is deliberate: a false positive (a plain {@code a=b} after a {@code ?} in the text)
     * becomes {@code ***} instead of a leak.
     */
    static String sanitizeDiag(String raw) {
        String s = raw == null ? "" : raw;
        s = QUERY_VALUE.matcher(s).replaceAll("$1=***");
        s = USERINFO.matcher(s).replaceAll("://***@");
        s = BARE_CREDENTIAL.matcher(s).replaceAll("$1 ***");
        s = QUOTED_CREDENTIAL.matcher(s).replaceAll("$1 ***\"");
        s = QUOTED_SECRET_VALUE.matcher(s).replaceAll("\"***\"");
        if (s.length() > MAX_DIAG_CHARS) {
            s = s.substring(0, MAX_DIAG_CHARS) + "…(truncated)";
        }
        return s;
    }

    private static final java.util.regex.Pattern QUERY_VALUE =
        java.util.regex.Pattern.compile("([?&#][^?&#=\\s\"']+)=([^?&#\\s\"']*(?:\\s+[^?&#\\s\"']*)*)");
    private static final java.util.regex.Pattern USERINFO =
        java.util.regex.Pattern.compile("://[^/\\s\"']*@");
    private static final java.util.regex.Pattern QUOTED_CREDENTIAL =
        java.util.regex.Pattern.compile("((?i)Bearer|Basic)\\s+[^\"]*\"");
    private static final java.util.regex.Pattern QUOTED_SECRET_VALUE =
        // An apiKey in a header with an arbitrary name is quoted by the JDK without a Bearer/Basic
        // prefix, so it needs a pattern of its own. The lookahead skips the Bearer/Basic form (two
        // patterns above handle it) and an already sanitized "***"; the {8,} threshold leaves short
        // quotes alone.
        java.util.regex.Pattern.compile("\"((?!Bearer |Basic |[^\"]*\\*\\*\\*)[^\"]{8,})\"");
    private static final java.util.regex.Pattern BARE_CREDENTIAL =
        // No quote in the class: otherwise it eats the closing quote of the JDK form
        // ("Bearer tok..." into "Bearer ***); the quoted form is fixed by the next pattern.
        java.util.regex.Pattern.compile("((?i)Bearer|Basic)\\s+[^\\s\"]+");

    /**
     * Watchdog for reading the body. {@code HttpRequest.timeout} of the JDK applies only until the
     * headers arrive — the body read through {@code BodyHandlers.ofInputStream()} is not covered by
     * it, and a slow allowed server (1 byte/s) then holds a worker thread indefinitely. The watchdog
     * closes the response stream at the deadline; the blocking {@code read()} then fails with an
     * IOException, which is recognized as "the deadline has passed" and turned into a
     * {@link java.net.HttpTimeoutException} — the same class the JDK itself would throw, so the
     * transient branch of {@code handleJob} retries and opens an incident, and not the deterministic
     * error code for an invalid configuration. A slow server is not an "invalid configuration".
     *
     * <p>One daemon thread for the module: there can be many workers, while the watchdog has a single
     * task, a read, and it is always canceled in a {@code finally}.
     */
    private static final ScheduledExecutorService DEADLINE_WATCHDOG =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "http-connector-deadline");
            t.setDaemon(true);
            return t;
        });

    private final HttpConnectorProperties properties;
    private final HttpSsrfGate ssrfGate;
    private final ActivityService activityService;
    // A mapper of its own rather than an injected bean: the connector only parses text with it, and
    // the engine and the starter bring their own mappers for their own messages.
    private final JsonMapper objectMapper = JsonMapper.builder().build();

    public HttpConnectorWorker(HttpConnectorProperties properties, HttpSsrfGate ssrfGate,
            ActivityService activityService) {
        this.properties = properties;
        this.ssrfGate = ssrfGate;
        this.activityService = activityService;
        mergeSecretsJson();
    }

    /**
     * Parses {@code zorrobpm.http-connector.secrets-json} into the secret map once, at startup. Broken
     * JSON or a non-object is a fail-fast (the startup fails), not the first request in production: a
     * configuration with a typo in the secrets has to be caught at deployment time, the way
     * {@link HttpConnectorStartupValidator} does it for the other settings. An explicit
     * {@code secrets} entry overrides the value from here.
     */
    private void mergeSecretsJson() {
        String blob = properties.getSecretsJson();
        if (blob == null || blob.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(blob);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                "zorrobpm.http-connector.secrets-json is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (!root.isObject()) {
            throw new IllegalStateException(
                "zorrobpm.http-connector.secrets-json must be a JSON object of {name: secret}");
        }
        for (String name : root.propertyNames()) {
            JsonNode secret = root.get(name);
            if (!secret.isObject()) {
                throw new IllegalStateException(
                    "zorrobpm.http-connector.secrets-json['" + name + "'] must be a JSON object");
            }
            properties.getSecrets().putIfAbsent(name, secret.toString());
        }
    }

    @Override
    public String getJob() {
        return JOB_TYPE;
    }

    @Override
    public List<ProcessVariable> handleJob(JobDetailModel model) {
        try {
            return execute(model);
        } catch (HttpSsrfGate.SsrfRejectedException | HttpConnectorConfigException e) {
            // Deterministic: retrying is pointless — a BPMN error instead of empty retries. Only the
            // sanitized text goes into the log and the variables, because a secret from authRef can
            // reach the message through a pasted URI, a Location header or another header.
            String code = e instanceof HttpSsrfGate.SsrfRejectedException
                ? ERR_CONFIG
                : ((HttpConnectorConfigException) e).errorCode;
            String diagnostic = sanitizeDiag(e.getMessage());
            log.warn("HTTP connector deterministic error for task {}: {}", model.getServiceTaskId(), diagnostic);
            reportBpmnError(model.getServiceTaskId(), code, diagnostic,
                List.of(contractErrorVar(diagnostic)));
            return List.of();
        } catch (IllegalArgumentException e) {
            // URI.create, URI.resolve and the header builder throw unchecked IAE (an inner space in
            // the URL, a broken Location from an allowed server): that is a deterministic invalid
            // input, not a transient one, and it gets the same BPMN error — otherwise empty FAILED
            // retries. The IAE message quotes its input (the URL, a header value), so only the
            // sanitized text goes into the log and the variables. The prefix is a stable reason code,
            // not the exception class: the class name is not exposed, mapping on it is brittle and it
            // would add noise to the error contract.
            String diagnostic = sanitizeDiag("invalid URL or redirect location: " + e.getMessage());
            log.warn("HTTP connector invalid input for task {}: {}", model.getServiceTaskId(), diagnostic);
            reportBpmnError(model.getServiceTaskId(), ERR_CONFIG, diagnostic,
                List.of(contractErrorVar(diagnostic)));
            return List.of();
        } catch (IOException | InterruptedException e) {
            // Transient: propagate it, the engine marks the job FAILED and retries before opening the
            // incident. A worker
            // killed between the HTTP effect and the completion is the documented at-least-once
            // delivery; the completion is already idempotent, so the HTTP side is not deduplicated.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // The transient message goes into the FAILED completion of the engine (an incident with
            // that text), so it is sanitized here rather than at the consumer.
            throw new IllegalStateException(
                "HTTP connector transient failure: " + sanitizeDiag(e.getMessage()), e);
        }
    }

    private List<ProcessVariable> execute(JobDetailModel model) throws IOException, InterruptedException {
        if (!properties.isEnabled()) {
            throw new HttpConnectorConfigException(ERR_DISABLED,
                "zorrobpm.http-connector.enabled=false — ask the administrator to enable the connector");
        }
        Map<String, ProcessVariable> inputs = model.getVariables() != null ? model.getVariables() : Map.of();
        rejectLiteralSecrets(inputs);

        String url = requiredText(inputs, "http.url");
        String method = textOrDefault(inputs, "http.method", "GET").toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "http.method must be one of " + ALLOWED_METHODS + " (got '" + method + "')");
        }
        Map<String, String> headers = mapOrEmpty(inputs, "http.headers");
        Map<String, String> queryParameters = mapOrEmpty(inputs, "http.queryParameters");
        String body = inputs.containsKey("http.body") ? decodeText(inputs.get("http.body")) : null;
        if (body != null && !BODY_METHODS.contains(method)) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "http.body is only allowed for POST/PUT/PATCH (got method '" + method + "')");
        }
        int connectTimeout = timeoutSeconds(inputs, "http.connectionTimeout",
            properties.getDefaultConnectionTimeoutSeconds(), properties.getMaxConnectionTimeoutSeconds());
        int readTimeout = timeoutSeconds(inputs, "http.readTimeout",
            properties.getDefaultReadTimeoutSeconds(), properties.getMaxReadTimeoutSeconds());

        String authType = textOrDefault(inputs, "http.authType", "none").toLowerCase(Locale.ROOT);
        String authRef = textOrNull(inputs, "http.authRef");
        AuthHeader auth = resolveAuth(authType, authRef);

        URI uri = buildUri(url, queryParameters, auth);
        // The SSRF gate runs before a single byte goes into a socket (one single place, every redirect
        // hop included).
        ssrfGate.validate(uri);

        HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(connectTimeout))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        // One deadline for the whole exchange: the headers, every redirect hop and the body.
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(readTimeout);

        HttpResponse<InputStream> response =
            sendWithRedirects(client, method, uri, headers, body, auth, readTimeout, deadlineNanos);
        int status = response.statusCode();
        Map<String, String> responseHeaders = filterResponseHeaders(response.headers().map());
        String contentType = response.headers().firstValue("content-type").orElse("");
        String responseBody = readBodyWithinDeadline(response.body(), properties.getMaxResponseBytes(),
            deadlineNanos, readTimeout);
        // The body may have been cut short because it was oversized — close the stream, the connection
        // is not reused.
        response.body().close();

        String bodyType = isJsonContent(contentType) ? "JSON" : "STRING";
        if (status >= 200 && status < 300) {
            List<ProcessVariable> result = new ArrayList<>();
            result.add(longVar("http.status", status));
            result.add(jsonVar("http.headers", toJson(responseHeaders)));
            result.add(bodyVar(responseBody, bodyType));
            return result;
        }
        // Anything but 2xx is a BPMN error, never a silent success. throwBpmnError takes the CONTRACT
        // variable model (engine API), while the SUCCESS path above uses the EXCHANGE model (the
        // JobHandler SPI): the two are not to be mixed up.
        List<com.zorrodev.bpm.contract.model.ProcessVariable> errorVars = new ArrayList<>();
        errorVars.add(contractLongVar("http.status", status));
        errorVars.add(contractJsonVar("http.headers", toJson(responseHeaders)));
        errorVars.add(contractBodyVar(responseBody, bodyType));
        errorVars.add(contractErrorVar("HTTP " + status));
        reportBpmnError(model.getServiceTaskId(), "HTTP_" + status, "HTTP " + status, errorVars);
        return List.of();
    }

    /**
     * Reports a deterministic outcome as a BPMN error on the service task and logs what the engine
     * made of it: an error boundary event caught the error, or an incident opened on the task because
     * nothing caught it.
     *
     * <p>{@code sanitizedDiagnostic} is what the engine puts into the incident message, so it must be
     * the output of {@link #sanitizeDiag} and never a raw response or URL. The worker sends no
     * completion afterwards in either case: a caught error has already interrupted the activity, an
     * uncaught one has left an incident for an operator.
     */
    private void reportBpmnError(UUID serviceTaskId, String errorCode, String sanitizedDiagnostic,
            List<com.zorrodev.bpm.contract.model.ProcessVariable> variables) {
        BpmnErrorOutcome outcome =
            activityService.throwBpmnError(serviceTaskId, errorCode, sanitizedDiagnostic, variables);
        if (outcome.caught()) {
            log.info("HTTP connector: BPMN error {} of task {} caught by boundary event {}",
                errorCode, serviceTaskId, outcome.boundaryEventId());
        } else {
            log.warn("HTTP connector: BPMN error {} of task {} was not caught, incident {} opened",
                errorCode, serviceTaskId, outcome.incidentId());
        }
    }

    private HttpResponse<InputStream> sendWithRedirects(HttpClient client, String method, URI uri,
            Map<String, String> headers, String body, AuthHeader auth, int readTimeout, long deadlineNanos)
            throws IOException, InterruptedException {
        URI current = uri;
        String currentMethod = method;
        String currentBody = body;
        int maxRedirects = properties.getMaxRedirects();
        // A secret from authRef goes to the origin of the original request only. A change of origin
        // (even to a second allowed host) drops the auth headers, the way browsers do it: otherwise a
        // secret leaks to a third party we already trust by configuration. They are not restored when
        // a chain returns to the original origin — a chain that leaves and comes back must not differ
        // from one that does not.
        final URI originalOrigin = originOf(uri);
        boolean authAllowed = true;
        for (int hop = 0; ; hop++) {
            HttpRequest request = buildRequest(current, currentMethod, headers, currentBody,
                authAllowed ? auth : AuthHeader.none(), readTimeout);
            HttpResponse<InputStream> response = sendBounded(client, request, deadlineNanos, readTimeout);
            int status = response.statusCode();
            if (!REDIRECT_STATUSES.contains(String.valueOf(status)) || hop >= maxRedirects) {
                if (REDIRECT_STATUSES.contains(String.valueOf(status)) && hop >= maxRedirects) {
                    response.body().close();
                    throw new HttpConnectorConfigException(ERR_CONFIG,
                        "too many redirects (max " + maxRedirects + ") — "
                        + "ask the administrator to raise zorrobpm.http-connector.max-redirects");
                }
                return response;
            }
            String location = response.headers().firstValue("location").orElse(null);
            response.body().close();
            if (location == null || location.isBlank()) {
                throw new HttpConnectorConfigException(ERR_CONFIG,
                    "redirect (" + status + ") without Location header");
            }
            URI next = current.resolve(location.strip());
            // Every hop goes through the same gate (a redirect to an internal host is rejected here).
            ssrfGate.validate(next);
            if (!originOf(next).equals(originalOrigin)) {
                authAllowed = false;
            }
            current = next;
            if (status == 303 || ((status == 301 || status == 302) && currentBody != null)) {
                currentMethod = "GET";
                currentBody = null;
            }
        }
    }

    /** Origin = scheme + host + port (the authority), the way a browser understands it. */
    private static URI originOf(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null);
        } catch (java.net.URISyntaxException e) {
            // The scheme and the host have already been checked by ssrfGate.validate and URI.create,
            // so this is not reachable. The full URI does not go into the message: its query may hold a
            // secret.
            throw new IllegalArgumentException("cannot derive origin of validated URI", e);
        }
    }

    /**
     * Sending under the shared deadline. {@code sendAsync} plus {@code get(remaining)} instead of
     * {@code send}: the blocking {@code send} cannot hand the remaining time back to the exchange.
     * Canceling the future when the deadline passes breaks the exchange on the JDK side, so our thread
     * is freed at once.
     */
    private HttpResponse<InputStream> sendBounded(HttpClient client, HttpRequest request,
            long deadlineNanos, int readTimeout) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<InputStream>> pending =
            client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        try {
            return pending.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw deadlineExceeded(readTimeout, "response headers");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException("HTTP exchange failed: " + cause, cause);
        }
    }

    /**
     * Reading the body under the same deadline. The watchdog closes the response stream exactly when
     * the deadline passes; the blocking {@code read()} then fails with an IOException, and "the
     * deadline has passed" is told apart from "the server dropped the connection" by the time.
     */
    private String readBodyWithinDeadline(InputStream body, long maxBytes, long deadlineNanos, int readTimeout)
            throws IOException {
        ScheduledFuture<?> watchdog = DEADLINE_WATCHDOG.schedule(() -> {
            try {
                body.close();
            } catch (IOException | RuntimeException ignored) {
                // Closing a read that is in flight may throw; that is expected, the deadline did its
                // work.
            }
        }, remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
        try {
            return readBounded(body, maxBytes);
        } catch (IOException e) {
            if (deadlineExpired(deadlineNanos)) {
                throw deadlineExceeded(readTimeout, "response body");
            }
            throw e;
        } finally {
            watchdog.cancel(false);
        }
    }

    private static long remainingNanos(long deadlineNanos) throws HttpTimeoutException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw deadlineExceeded(0, "exchange");
        }
        return remaining;
    }

    private static boolean deadlineExpired(long deadlineNanos) {
        return System.nanoTime() - deadlineNanos >= 0;
    }

    private static HttpTimeoutException deadlineExceeded(int readTimeout, String what) {
        // The same class the JDK itself throws for HttpRequest.timeout: an IOException goes to the
        // transient branch of handleJob (FAILED, then retries and an incident), not to the
        // deterministic configuration error.
        return new HttpTimeoutException("no " + what + " within http.readTimeout=" + readTimeout + "s");
    }

    private static HttpRequest buildRequest(URI uri, String method, Map<String, String> headers,
            String body, AuthHeader auth, int readTimeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(readTimeout));
        headers.forEach(builder::header);
        auth.applyTo(builder);
        if (BODY_METHODS.contains(method)) {
            builder.method(method, body != null
                ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }

    private URI buildUri(String url, Map<String, String> queryParameters, AuthHeader auth) {
        String base = url.strip();
        // The bare URL is validated first, the secret is pasted on after it. Otherwise URI.create
        // quotes the whole input (including api_key=...) in its exception text, and the secret ends up
        // in the log and in the process variables.
        URI baseUri = URI.create(base);
        StringBuilder query = new StringBuilder();
        queryParameters.forEach((k, v) -> appendQueryParam(query, k, v));
        if (auth.queryParam != null) {
            appendQueryParam(query, auth.queryParam.name(), auth.queryParam.value());
        }
        if (query.length() == 0) {
            return baseUri;
        }
        String separator = base.contains("?") ? (base.endsWith("?") || base.endsWith("&") ? "" : "&") : "?";
        return URI.create(base + separator + query);
    }

    private static void appendQueryParam(StringBuilder query, String name, String value) {
        if (query.length() > 0) {
            query.append('&');
        }
        query.append(URLEncoder.encode(name, StandardCharsets.UTF_8));
        query.append('=');
        query.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    /** Secrets go by reference through authRef only; literals in the inputs are rejected, fail-closed. */
    private void rejectLiteralSecrets(Map<String, ProcessVariable> inputs) {
        for (String name : inputs.keySet()) {
            if (name != null && SECRET_LITERAL_INPUTS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new HttpConnectorConfigException(ERR_CONFIG,
                    "secret '" + name + "' must not be passed literally — use http.authRef instead");
            }
        }
        if (inputs.containsKey("http.headers")) {
            Map<String, String> headers = tryParseStringMap(inputs.get("http.headers"));
            if (headers != null) {
                for (String headerName : headers.keySet()) {
                    if ("authorization".equalsIgnoreCase(headerName) || "proxy-authorization".equalsIgnoreCase(headerName)) {
                        throw new HttpConnectorConfigException(ERR_CONFIG,
                            "Authorization header must not be passed literally in http.headers — use http.authRef instead");
                    }
                }
            }
        }
    }

    private AuthHeader resolveAuth(String authType, String authRef) {
        if ("none".equals(authType)) {
            if (authRef != null) {
                throw new HttpConnectorConfigException(ERR_CONFIG,
                    "http.authRef is set but http.authType is 'none'");
            }
            return AuthHeader.none();
        }
        if (authRef == null || authRef.isBlank()) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "http.authType '" + authType + "' requires http.authRef (secret name)");
        }
        String secretJson = properties.getSecrets() != null ? properties.getSecrets().get(authRef) : null;
        if (secretJson == null) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "unknown http.authRef '" + authRef + "' (not in zorrobpm.http-connector.secrets)");
        }
        JsonNode secret;
        try {
            secret = objectMapper.readTree(secretJson);
        } catch (JacksonException e) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "secret '" + authRef + "' is not valid JSON");
        }
        String secretType = secret.path("type").asString("").toLowerCase(Locale.ROOT);
        if (!authType.equals(secretType)) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "http.authType '" + authType + "' does not match secret '" + authRef + "' type '" + secretType + "'");
        }
        // Only none/apiKey/basic/bearer; OAuth is not supported here.
        return switch (authType) {
            case "bearer" -> {
                String token = secret.path("token").asString(null);
                if (token == null || token.isBlank()) {
                    throw new HttpConnectorConfigException(ERR_CONFIG,
                        "secret '" + authRef + "' (bearer) must contain a non-empty 'token'");
                }
                yield AuthHeader.bearer(token);
            }
            case "basic" -> {
                String username = secret.path("username").asString(null);
                String password = secret.path("password").asString(null);
                if (username == null || password == null) {
                    throw new HttpConnectorConfigException(ERR_CONFIG,
                        "secret '" + authRef + "' (basic) must contain 'username' and 'password'");
                }
                yield AuthHeader.basic(username, password);
            }
            case "apikey" -> {
                String name = secret.path("name").asString(null);
                String value = secret.path("value").asString(null);
                String in = secret.path("in").asString("header").toLowerCase(Locale.ROOT);
                if (name == null || name.isBlank() || value == null) {
                    throw new HttpConnectorConfigException(ERR_CONFIG,
                        "secret '" + authRef + "' (apiKey) must contain 'name' and 'value'");
                }
                if (!in.equals("header") && !in.equals("query")) {
                    throw new HttpConnectorConfigException(ERR_CONFIG,
                        "secret '" + authRef + "' (apiKey) 'in' must be 'header' or 'query'");
                }
                yield AuthHeader.apiKey(name, value, in);
            }
            default -> throw new HttpConnectorConfigException(ERR_CONFIG,
                "unsupported http.authType '" + authType + "' (supported: none, apiKey, basic, bearer)");
        };
    }

    private record AuthHeader(String headerName, String headerValue, QueryParam queryParam) {
        static AuthHeader none() {
            return new AuthHeader(null, null, null);
        }
        static AuthHeader bearer(String token) {
            return new AuthHeader("Authorization", "Bearer " + token, null);
        }
        static AuthHeader basic(String username, String password) {
            String encoded = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            return new AuthHeader("Authorization", "Basic " + encoded, null);
        }
        static AuthHeader apiKey(String name, String value, String in) {
            return "query".equals(in)
                ? new AuthHeader(null, null, new QueryParam(name, value))
                : new AuthHeader(name, value, null);
        }
        void applyTo(HttpRequest.Builder builder) {
            if (headerName != null) {
                builder.header(headerName, headerValue);
            }
        }
    }

    private record QueryParam(String name, String value) {
    }

    private Map<String, String> filterResponseHeaders(Map<String, List<String>> raw) {
        // A TreeMap with CASE_INSENSITIVE_ORDER catches set-cookie in any case.
        Map<String, List<String>> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byName.putAll(raw);
        Map<String, String> result = new LinkedHashMap<>();
        byName.forEach((name, values) -> {
            if (FILTERED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            result.put(name.toLowerCase(Locale.ROOT), String.join(", ", values));
        });
        return result;
    }

    private String readBounded(InputStream body, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = body.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                // Oversized: rejected with a BPMN error, never silently truncated.
                throw new HttpConnectorConfigException(ERR_CONFIG,
                    "response body exceeds zorrobpm.http-connector.max-response-bytes (" + maxBytes + ")");
            }
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isJsonContent(String contentType) {
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.contains("json");
    }

    private String requiredText(Map<String, ProcessVariable> inputs, String name) {
        ProcessVariable pv = inputs.get(name);
        if (pv == null || pv.getValue() == null || pv.getValue().isBlank()) {
            throw new HttpConnectorConfigException(ERR_CONFIG, "missing required input '" + name + "'");
        }
        return pv.getValue().strip();
    }

    private String textOrDefault(Map<String, ProcessVariable> inputs, String name, String def) {
        String v = textOrNull(inputs, name);
        return v != null ? v : def;
    }

    private static String textOrNull(Map<String, ProcessVariable> inputs, String name) {
        ProcessVariable pv = inputs.get(name);
        if (pv == null || pv.getValue() == null || pv.getValue().isBlank()) {
            return null;
        }
        return pv.getValue().strip();
    }

    private static String decodeText(ProcessVariable pv) {
        return pv.getValue() != null ? pv.getValue() : "";
    }

    private Map<String, String> mapOrEmpty(Map<String, ProcessVariable> inputs, String name) {
        ProcessVariable pv = inputs.get(name);
        if (pv == null || pv.getValue() == null || pv.getValue().isBlank()) {
            return Map.of();
        }
        Map<String, String> parsed = tryParseStringMap(pv);
        if (parsed == null) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "input '" + name + "' must be a JSON object of string to string (got type '" + pv.getType() + "')");
        }
        return parsed;
    }

    private Map<String, String> tryParseStringMap(ProcessVariable pv) {
        return tryParseStringMapValue(pv.getValue());
    }

    private Map<String, String> tryParseStringMapValue(String value) {
        if (value == null) {
            return null;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(value);
        } catch (JacksonException e) {
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : node.propertyNames()) {
            JsonNode entry = node.get(name);
            String text = entry.asString(null);
            result.put(name, text != null ? text : entry.toString());
        }
        return result;
    }

    private int timeoutSeconds(Map<String, ProcessVariable> inputs, String name, int def, int max) {
        ProcessVariable pv = inputs.get(name);
        if (pv == null || pv.getValue() == null || pv.getValue().isBlank()) {
            return def;
        }
        long seconds;
        try {
            seconds = (long) Double.parseDouble(pv.getValue().strip());
        } catch (NumberFormatException e) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "input '" + name + "' must be a number of seconds (got '" + pv.getValue() + "')");
        }
        if (seconds <= 0 || seconds > max) {
            throw new HttpConnectorConfigException(ERR_CONFIG,
                "input '" + name + "' must be within 1.." + max + " seconds (got " + seconds + ")");
        }
        return (int) seconds;
    }

    private static ProcessVariable longVar(String name, long value) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName(name);
        pv.setValue(Long.toString(value));
        pv.setType("LONG");
        return pv;
    }

    private ProcessVariable jsonVar(String name, String json) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName(name);
        pv.setValue(json);
        pv.setType("JSON");
        return pv;
    }

    private ProcessVariable bodyVar(String body, String type) {
        ProcessVariable pv = new ProcessVariable();
        pv.setName("http.body");
        pv.setValue(body);
        pv.setType(type);
        return pv;
    }

    /** The CONTRACT model (the engine API of throwBpmnError), kept apart from the EXCHANGE model above. */
    private static com.zorrodev.bpm.contract.model.ProcessVariable contractVar(
            String name, String value, com.zorrodev.bpm.contract.model.ProcessVariableType type) {
        com.zorrodev.bpm.contract.model.ProcessVariable pv =
            new com.zorrodev.bpm.contract.model.ProcessVariable();
        pv.setName(name);
        pv.setValue(value);
        pv.setType(type);
        return pv;
    }

    private static com.zorrodev.bpm.contract.model.ProcessVariable contractLongVar(String name, long value) {
        return contractVar(name, Long.toString(value),
            com.zorrodev.bpm.contract.model.ProcessVariableType.LONG);
    }

    private static com.zorrodev.bpm.contract.model.ProcessVariable contractJsonVar(String name, String json) {
        return contractVar(name, json, com.zorrodev.bpm.contract.model.ProcessVariableType.JSON);
    }

    private static com.zorrodev.bpm.contract.model.ProcessVariable contractBodyVar(String body, String bodyType) {
        return contractVar("http.body", body,
            "JSON".equals(bodyType)
                ? com.zorrodev.bpm.contract.model.ProcessVariableType.JSON
                : com.zorrodev.bpm.contract.model.ProcessVariableType.STRING);
    }

    private static com.zorrodev.bpm.contract.model.ProcessVariable contractErrorVar(String message) {
        return contractVar("http.error", message,
            com.zorrodev.bpm.contract.model.ProcessVariableType.STRING);
    }

    private String toJson(Map<String, String> map) {
        return objectMapper.writeValueAsString(map);
    }

    /** A deterministic error of the connector; it carries the BPMN error code (HTTP_CONNECTOR_*). */
    static class HttpConnectorConfigException extends RuntimeException {
        final String errorCode;
        HttpConnectorConfigException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }
}