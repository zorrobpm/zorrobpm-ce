package com.zorrodev.bpm.httpconnector;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SSRF gate, fail-closed at every step. No network is needed: literal IPs do not require DNS and
 * {@code localhost} resolves locally.
 */
class HttpSsrfGateTest {

    private static HttpSsrfGate gate(String allowedHosts, boolean allowPrivate) {
        HttpConnectorProperties props = new HttpConnectorProperties();
        props.setAllowedHosts(allowedHosts);
        props.setAllowPrivateNetworks(allowPrivate);
        return new HttpSsrfGate(props);
    }

    @Test
    void scheme_file_rejected() {
        HttpSsrfGate gate = gate("example.com", false);
        assertThatThrownBy(() -> gate.validate(URI.create("file:///etc/passwd")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("http/https");
    }

    @Test
    void scheme_gopher_rejected() {
        HttpSsrfGate gate = gate("example.com", false);
        assertThatThrownBy(() -> gate.validate(URI.create("gopher://example.com/1")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class);
    }

    @Test
    void userinfo_rejected() {
        HttpSsrfGate gate = gate("example.com", false);
        assertThatThrownBy(() -> gate.validate(URI.create("http://user:pass@example.com/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("userinfo");
    }

    @Test
    void emptyAllowlist_denyAll_evenForPublicLiteral() {
        // An empty allowed-hosts is deny-all (fail-closed), not fail-open for public hosts.
        HttpSsrfGate gate = gate("", false);
        assertThatThrownBy(() -> gate.validate(URI.create("http://8.8.8.8/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("deny-all");
    }

    @Test
    void hostNotInAllowlist_rejected() {
        HttpSsrfGate gate = gate("api.example.com", true);
        assertThatThrownBy(() -> gate.validate(URI.create("http://evil.com/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("not in zorrobpm.http-connector.allowed-hosts");
    }

    @Test
    void loopbackLiteral_rejected_whenPrivateNotAllowed() {
        HttpSsrfGate gate = gate("127.0.0.1", false);
        assertThatThrownBy(() -> gate.validate(URI.create("http://127.0.0.1:8080/actuator")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("private/reserved");
    }

    @Test
    void privateRanges_rejected_whenPrivateNotAllowed() {
        HttpSsrfGate gate = gate("10.0.0.1,172.20.5.5,192.168.1.1", false);
        assertThatThrownBy(() -> gate.validate(URI.create("http://10.0.0.1/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class);
        assertThatThrownBy(() -> gate.validate(URI.create("http://172.20.5.5/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class);
        assertThatThrownBy(() -> gate.validate(URI.create("http://192.168.1.1/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class);
    }

    @Test
    void cloudMetadata_rejected_whenPrivateNotAllowed() {
        HttpSsrfGate gate = gate("169.254.169.254", false);
        assertThatThrownBy(() -> gate.validate(URI.create("http://169.254.169.254/latest/meta-data/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("private/reserved");
    }

    @Test
    void localhost_resolvesToLoopback_rejected_whenPrivateNotAllowed() {
        // No external network: localhost resolves locally to 127.0.0.1, and the gate catches it AFTER
        // the resolution.
        HttpSsrfGate gate = gate("localhost", false);
        assertThatThrownBy(() -> gate.validate(URI.create("http://localhost:15672/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("private/reserved");
    }

    @Test
    void ipv6Loopback_rejected_whenPrivateNotAllowed() {
        HttpSsrfGate gate = gate("::1", false);
        assertThatThrownBy(() -> gate.validate(URI.create("http://[::1]:8080/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("private/reserved");
    }

    @Test
    void publicLiteral_passes_whenAllowlisted() throws Exception {
        gate("8.8.8.8", false).validate(URI.create("http://8.8.8.8/"));
    }

    @Test
    void privateLiteral_passes_onlyWithExplicitOptIn() throws Exception {
        gate("127.0.0.1", true).validate(URI.create("http://127.0.0.1:8080/"));
    }

    @Test
    void suffixMatch_doesNotApplyToLiteralIps() {
        // "10.0.0.1" must not match the entry "0.0.1" by suffix: a literal IP matches exactly only.
        HttpSsrfGate gate = gate("0.0.1", true);
        assertThatThrownBy(() -> gate.validate(URI.create("http://10.0.0.1/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("not in zorrobpm.http-connector.allowed-hosts");
    }

    @Test
    void dnsSuffix_classicBypass_evilExample_rejected() {
        // The classic allowlist bypass: "evil-example.com" is not a suffix of "example.com". Only the
        // dot anchor catches it, not a bare endsWith on the string.
        HttpSsrfGate gate = gate("example.com", true);
        assertThatThrownBy(() -> gate.validate(URI.create("http://evil-example.com/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("not in zorrobpm.http-connector.allowed-hosts");
        assertThatThrownBy(() -> gate.validate(URI.create("http://example.com.evil.com/")))
            .isInstanceOf(HttpSsrfGate.SsrfRejectedException.class)
            .hasMessageContaining("not in zorrobpm.http-connector.allowed-hosts");
    }

    @Test
    void uppercaseScheme_accepted_notABypass() {
        // "HTTP://8.8.8.8" is the same scheme after lowercasing, not a bypass.
        try {
            gate("8.8.8.8", false).validate(URI.create("HTTP://8.8.8.8/"));
        } catch (Exception e) {
            throw new AssertionError("uppercase HTTP scheme must pass the scheme check", e);
        }
    }

    // --- byte-by-byte range checks, without DNS ---

    @Test
    void privateOrReserved_ipv4_matrix() {
        // private, loopback, link-local and special-use ranges
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(10, 0, 0, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(172, 16, 0, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(172, 31, 255, 255))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(192, 168, 0, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(127, 0, 0, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(169, 254, 169, 254))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(0, 0, 0, 0))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(100, 64, 0, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(192, 0, 2, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(203, 0, 113, 5))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(198, 18, 0, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(224, 0, 0, 1))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(240, 0, 0, 1))).isTrue();
        // the boundaries: 172.15.x and 172.32.x are public already
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(172, 15, 0, 1))).isFalse();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(172, 32, 0, 1))).isFalse();
        // public
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(8, 8, 8, 8))).isFalse();
        assertThat(HttpSsrfGate.isPrivateOrReserved(HttpSsrfGate.addressOf(1, 1, 1, 1))).isFalse();
    }

    @Test
    void privateOrReserved_ipv6_matrix() throws Exception {
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("::1"))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("::"))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("fc00::1"))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("fd00::1"))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("fe80::1"))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("ff02::1"))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("2001:db8::1"))).isTrue();
        // a private v4-mapped address is caught through the embedded v4
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("::ffff:10.0.0.1"))).isTrue();
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("::ffff:8.8.8.8"))).isFalse();
        // a global unicast address passes
        assertThat(HttpSsrfGate.isPrivateOrReserved(InetAddress.getByName("2606:4700:4700::1111"))).isFalse();
    }

    @Test
    void parseAllowedHosts_trimsAndLowercases() {
        assertThat(HttpSsrfGate.parseAllowedHosts(" API.Example.COM , ,internal.local "))
            .containsExactlyInAnyOrder("api.example.com", "internal.local");
        assertThat(HttpSsrfGate.parseAllowedHosts("")).isEmpty();
        assertThat(HttpSsrfGate.parseAllowedHosts(null)).isEmpty();
    }
}
