package com.zorrodev.bpm.httpconnector;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration of the built-in HTTP/REST outbound connector.
 *
 * <p>All keys are {@code zorrobpm.http-connector.*}. The values are validated at startup
 * ({@link HttpConnectorStartupValidator}, fail-fast), here it is only the binding with safe defaults.
 *
 * <p>Security semantics of the defaults: the connector stays disabled until an administrator enables
 * it ({@code enabled=false}); an empty {@code allowed-hosts} is deny-all (fail-closed);
 * {@code allow-private-networks=true} is only acceptable outside the prod profile.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "zorrobpm.http-connector")
public class HttpConnectorProperties {

    /** Master switch. {@code false} (the default) makes the worker reject every task with the
     *  deterministic BPMN error {@code HTTP_CONNECTOR_DISABLED}. */
    private boolean enabled = false;

    /** Allowlist of target hosts (DNS names, suffixes or literal IPs, comma separated).
     *  Empty (the default) is deny-all. A match is an exact name or a dot-separated suffix:
     *  {@code api.example.com} covers both {@code api.example.com} and {@code *.api.example.com}. */
    private String allowedHosts = "";

    /** Whether to allow private/reserved IP ranges (dev and staging against internal APIs).
     *  {@code true} in the prod profile is FATAL at startup. */
    private boolean allowPrivateNetworks = false;

    /** Hard cap of the response body in bytes. Exceeding it is a BPMN error, never a silent
     *  truncation. */
    private long maxResponseBytes = 1024L * 1024L;

    /** Default timeouts in seconds (mirroring the Camunda defaults of 20/20). */
    private int defaultConnectionTimeoutSeconds = 20;
    private int defaultReadTimeoutSeconds = 20;

    /** Upper caps: BPMN configuration cannot set a timeout above these. */
    private int maxConnectionTimeoutSeconds = 120;
    private int maxReadTimeoutSeconds = 300;

    /** How many redirects (3xx) to follow, every hop validated by the SSRF gate again.
     *  {@code 0} (the default, mirroring Camunda's {@code followRedirects=false}) means no redirect
     *  is followed. */
    private int maxRedirects = 0;

    /**
     * Server-side secret store: a key is a secret name ({@code http.authRef}), a value is a JSON object
     * of the form {@code {"type":"bearer","token":"..."}},
     * {@code {"type":"basic","username":"...","password":"..."}},
     * {@code {"type":"apiKey","name":"X-Key","value":"...","in":"header"}} ({@code in} is
     * {@code header} or {@code query}, the default is {@code header}).
     * Binds from {@code zorrobpm.http-connector.secrets.<name>} (environment or properties).
     */
    private Map<String, String> secrets = new HashMap<>();

    /**
     * The same secrets as one JSON object in one setting:
     * {@code ZORROBPM_HTTP_CONNECTOR_SECRETS_JSON='{"svc":{"type":"bearer","token":"…"}}'}.
     *
     * <p>Why a second knob when there is {@link #secrets}: secret names with an arbitrary shape
     * cannot be enumerated in a static {@code application.properties}
     * ({@code zorrobpm.http-connector.secrets.<name>}), and a fixed list of scalars in
     * {@code docker-compose.yml} left {@code authType != none} unreachable in a stock compose
     * deployment — editing the compose file by hand was the only way. A secret name with a hyphen
     * cannot be expressed in an environment variable name either (Spring turns {@code _} into a path
     * separator), so a per-name {@code SECRETS_<NAME>} pattern does not work either.
     *
     * <p>Precedence: an explicit {@link #secrets} entry overrides the value from here (a direct
     * setting matters more than the blob). It is parsed once in the constructor of the worker; broken
     * JSON fails the startup (fail-fast), not the first HTTP request in production.
     *
     * <p>A deliberate boundary: a secret in an environment variable is visible in
     * {@code docker inspect}. That is exactly like every other secret of the project (JWT, mail
     * password), and moving to a mounted file is a separate decision (a file or a volume changes the
     * deployment topology), not a silent side effect.
     */
    private String secretsJson = "";
}