package com.zorrodev.bpm.app;

import io.sentry.Sentry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application with {@code SENTRY_DSN} set, against a local {@link FakeSentry}: what is reported,
 * what is not, and that nothing personal travels with an event.
 */
@ActiveProfiles("it")
@SpringBootTest(classes = {APP.class, SentryMonitoringTests.FailingResource.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SentryMonitoringTests {

    private static final FakeSentry SENTRY = new FakeSentry();
    private static final String SECRET_BODY = "{\"iin\":\"900101300123\",\"secret\":\"body-secret\"}";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void sentry(DynamicPropertyRegistry registry) {
        registry.add("SENTRY_DSN", SENTRY::dsn);
        registry.add("SENTRY_ENVIRONMENT", () -> "test");
        registry.add("SENTRY_TRACES_SAMPLE_RATE", () -> "1.0");
    }

    @AfterAll
    static void stop() {
        SENTRY.close();
    }

    @BeforeEach
    void clear() {
        Sentry.flush(5000);
        SENTRY.clear();
    }

    @Test
    void unhandledFailureIsReportedOnceWithoutPersonalData() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/test-failure?relatedToUser=900101300123"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer zbpa_token-secret")
            .header("Cookie", "SESSION=cookie-secret")
            .header("X-Forwarded-For", "203.0.113.7")
            .POST(HttpRequest.BodyPublishers.ofString(SECRET_BODY)));

        assertThat(response.statusCode()).isEqualTo(500);
        Sentry.flush(5000);

        assertThat(SENTRY.events()).as(String.join("\n=====\n", SENTRY.all())).hasSize(1);
        String event = SENTRY.events().get(0);
        assertThat(event)
            .contains("UnexpectedFailure")
            .contains("/test-failure")
            .contains("\"method\":\"POST\"")
            .contains("\"environment\":\"test\"")
            .contains("\"release\":\"0.8.1-SNAPSHOT\"")
            .contains("\"application\":\"zorrobpm-app\"");
        assertThat(event)
            .doesNotContain("token-secret")
            .doesNotContain("cookie-secret")
            .doesNotContain("body-secret")
            .doesNotContain("900101300123")
            .doesNotContain("203.0.113.7")
            .doesNotContain("127.0.0.1");
    }

    @Test
    void queryStringsStayOutOfSentry() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(
            uri("/user-tasks?relatedToUser=900101300124&relatedToGroups=managers-secret")).GET());

        assertThat(response.statusCode()).isEqualTo(200);
        Sentry.flush(5000);
        assertThat(SENTRY.transactions()).isNotEmpty();
        assertThat(SENTRY.all()).noneMatch(envelope -> envelope.contains("900101300124"))
            .noneMatch(envelope -> envelope.contains("managers-secret"));
    }

    @Test
    void unknownIncidentIsNotReported() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/incidents/" + UUID.randomUUID())).GET());

        assertThat(response.statusCode()).isEqualTo(404);
        Sentry.flush(5000);
        assertThat(SENTRY.events()).isEmpty();
    }

    @Test
    void rejectedQueryIsNotReported() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/user-tasks?sort=bogus")).GET());

        assertThat(response.statusCode()).isEqualTo(400);
        Sentry.flush(5000);
        assertThat(SENTRY.events()).isEmpty();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    static class UnexpectedFailure extends RuntimeException {
        UnexpectedFailure() {
            super("unexpected failure");
        }
    }

    @RestController
    static class FailingResource {

        @PostMapping("/test-failure")
        void fail(@RequestBody String body) {
            throw new UnexpectedFailure();
        }
    }
}
