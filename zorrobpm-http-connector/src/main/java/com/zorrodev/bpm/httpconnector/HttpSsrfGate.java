package com.zorrodev.bpm.httpconnector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The SSRF gate: the single place where the outbound URLs of the connector are checked. One bean, no
 * second way around it — the worker has to go through it before a single byte goes into a socket,
 * every redirect hop included.
 *
 * <p>The order, fail-closed at each step (a check before the network effect):
 * <ol>
 *   <li>the scheme is strictly {@code http}/{@code https}, otherwise rejected;</li>
 *   <li>userinfo in the URL ({@code http://user:pass@host}) — rejected;</li>
 *   <li>an empty {@code allowed-hosts} is deny-all;</li>
 *   <li>the host has to match the allowlist (an exact name or a dot-separated suffix; a literal IP
 *       only on an exact match);</li>
 *   <li>resolution through {@code InetAddress.getAllByName} — <b>every</b> returned address has to
 *       pass the private/reserved range check (byte by byte, not by regex), otherwise rejected.</li>
 * </ol>
 *
 * <p>An honest limitation, documented rather than hidden: between the resolution here and the connect
 * in {@code java.net.http.HttpClient} a DNS rebinding window stays open — the JDK HttpClient offers no
 * way to pin the socket to a checked IP (there is no pluggable DNS). It is closed partially: every
 * resolved address is checked (an attacker has to rebind each of them) and the redirect hops are
 * validated again. Closing it completely means an egress proxy at the infrastructure level, which is
 * outside the engine.
 */
@Slf4j
@Component
public class HttpSsrfGate {

    static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final HttpConnectorProperties properties;

    public HttpSsrfGate(HttpConnectorProperties properties) {
        this.properties = properties;
    }

    /**
     * Checks the computed (runtime) URL before the connection is opened.
     *
     * @throws SsrfRejectedException a deterministic rejection — retrying is pointless, so the worker
     *         turns it into the BPMN error {@code HTTP_CONNECTOR_CONFIG} and not into a FAILED retry
     * @throws java.io.IOException a transient resolution failure (DNS is down) — retried through the
     *         regular {@code FAILED}, then {@code failServiceTask} path
     */
    public void validate(URI uri) throws java.io.IOException {
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : null;
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new SsrfRejectedException("only http/https URLs are allowed (got scheme '" + uri.getScheme() + "')");
        }
        if (uri.getUserInfo() != null || (uri.getRawAuthority() != null && uri.getRawAuthority().contains("@"))) {
            throw new SsrfRejectedException("userinfo in URL is not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfRejectedException("URL has no host");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);

        Set<String> allowed = parseAllowedHosts(properties.getAllowedHosts());
        if (allowed.isEmpty()) {
            // Fail-closed deny-all by default.
            throw new SsrfRejectedException("no allowed-hosts configured (deny-all) — "
                + "ask the administrator to set zorrobpm.http-connector.allowed-hosts");
        }
        if (!hostMatchesAllowlist(normalizedHost)) {
            throw new SsrfRejectedException("host '" + host + "' is not in zorrobpm.http-connector.allowed-hosts");
        }

        InetAddress[] resolved = InetAddress.getAllByName(stripIpv6Brackets(normalizedHost));
        if (resolved.length == 0) {
            throw new SsrfRejectedException("host '" + host + "' resolves to no addresses");
        }
        boolean allowPrivate = properties.isAllowPrivateNetworks();
        for (InetAddress address : resolved) {
            if (!allowPrivate && isPrivateOrReserved(address)) {
                throw new SsrfRejectedException("host '" + host + "' resolves to a private/reserved IP ("
                    + address.getHostAddress() + ") which is not allowed");
            }
        }
        // The full URI is not logged, its query may hold a secret (an apiKey in a query parameter).
        // scheme://host is enough for diagnostics.
        log.debug("SSRF gate passed for {}://{} ({} address(es))",
            uri.getScheme(), uri.getHost(), resolved.length);
    }

    /** Package-visible for tests: parsing of the allowlist (trimmed, lowercased, without empty ones). */
    static Set<String> parseAllowedHosts(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
            .map(s -> s.trim().toLowerCase(Locale.ROOT))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    }

    private boolean hostMatchesAllowlist(String normalizedHost) {
        Set<String> allowed = parseAllowedHosts(properties.getAllowedHosts());
        String bare = stripIpv6Brackets(normalizedHost);
        for (String entry : allowed) {
            String bareEntry = stripIpv6Brackets(entry);
            if (bare.equals(bareEntry)) {
                return true;
            }
            // A suffix match is for DNS names only, not for literal IPs: "10.0.0.1" must not match
            // the entry "0.0.1".
            if (!isLiteralIp(bare) && !isLiteralIp(bareEntry) && bare.endsWith("." + bareEntry)) {
                return true;
            }
        }
        return false;
    }

    private static String stripIpv6Brackets(String host) {
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static boolean isLiteralIp(String host) {
        if (host == null) {
            return false;
        }
        if (host.contains(":")) {
            return true;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Byte-by-byte checks of the private/reserved ranges (IPv4 and IPv6). Deliberately not relying on
     * {@code InetAddress.isSiteLocalAddress()} and friends: the JDK semantics do not cover everything
     * (fc00::/7 unique-local and the 100.64/10 shared address space are not caught there, for
     * example).
     */
    static boolean isPrivateOrReserved(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            int b2 = b[2] & 0xFF;
            // 10/8, 172.16/12, 192.168/16
            if (b0 == 10 || (b0 == 172 && b1 >= 16 && b1 <= 31) || (b0 == 192 && b1 == 168)) {
                return true;
            }
            // 127/8 loopback, 0/8 ("this network"), 169.254/16 link-local (including cloud metadata)
            if (b0 == 127 || b0 == 0 || (b0 == 169 && b1 == 254)) {
                return true;
            }
            // 100.64/10 shared (CGNAT), 192.0.0/24, 192.0.2/24 + 198.51.100/24 + 203.0.113/24 (TEST-NET),
            // 192.88.99/24 (deprecated 6to4 relay), 198.18/15 (benchmarking)
            if ((b0 == 100 && b1 >= 64 && b1 <= 127)
                || (b0 == 192 && b1 == 0 && b2 == 0)
                || (b0 == 192 && b1 == 0 && b2 == 2)
                || (b0 == 198 && b1 == 51 && b2 == 100)
                || (b0 == 203 && b1 == 0 && b2 == 113)
                || (b0 == 192 && b1 == 88 && b2 == 99)
                || (b0 == 198 && (b1 == 18 || b1 == 19))) {
                return true;
            }
            // 224/4 multicast, 240/4 reserved
            return b0 >= 224;
        }
        if (b.length == 16) {
            // v4-mapped (::ffff:a.b.c.d) — the embedded v4 goes through the same rules.
            if (isV4Mapped(b)) {
                byte[] v4 = {b[12], b[13], b[14], b[15]};
                try {
                    return isPrivateOrReserved(InetAddress.getByAddress(v4));
                } catch (java.net.UnknownHostException e) {
                    return true;
                }
            }
            // ::1 loopback, :: unspecified
            boolean allZero = true;
            for (int i = 0; i < 15; i++) {
                if (b[i] != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero && (b[15] == 0 || b[15] == 1)) {
                return true;
            }
            int w0 = b[0] & 0xFF;
            int w1 = b[1] & 0xFF;
            // fc00::/7 unique-local, fe80::/10 link-local, ff00::/8 multicast
            if ((w0 & 0xFE) == 0xFC || (w0 == 0xFE && (w1 & 0xC0) == 0x80) || w0 == 0xFF) {
                return true;
            }
            // 2001:db8::/32 documentation
            return w0 == 0x20 && w1 == 0x01 && b[2] == 0x0D && b[3] == (byte) 0xB8;
        }
        // An unknown address length is fail-closed.
        return true;
    }

    private static boolean isV4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return b[10] == (byte) 0xFF && b[11] == (byte) 0xFF;
    }

    /** Package-visible for tests: an InetAddress without DNS (byte by byte). */
    static InetAddress addressOf(int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            b[i] = (byte) bytes[i];
        }
        try {
            return InetAddress.getByAddress(b);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** A deterministic rejection of the gate — retrying is pointless (a BPMN error, not a FAILED retry). */
    public static class SsrfRejectedException extends java.io.IOException {
        public SsrfRejectedException(String message) {
            super(message);
        }
    }
}