package com.zorrodev.bpm.exchange;

import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorReportTest {

    @Test
    void exceptionWithText() {
        ErrorReport report = ErrorReport.of(new IllegalArgumentException("card declined"), null);

        assertThat(report.getErrorCode()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(report.getMessage()).isEqualTo("card declined");
        assertThat(report.getDetails())
            .startsWith("java.lang.IllegalArgumentException: card declined")
            .contains("\tat com.zorrodev.bpm.exchange.ErrorReportTest");
    }

    @Test
    void exceptionWithoutTextUsesClassName() {
        ErrorReport report = ErrorReport.of(new NullPointerException(), null);

        assertThat(report.getMessage()).isEqualTo("java.lang.NullPointerException");
        assertThat(report.getErrorCode()).isEqualTo("java.lang.NullPointerException");
        assertThat(report.getMessage()).doesNotContain(": null");
        assertThat(report.getDetails()).startsWith("java.lang.NullPointerException");
    }

    @Test
    void blankTextUsesClassName() {
        ErrorReport report = ErrorReport.of(new IllegalStateException("  "), null);

        assertThat(report.getMessage()).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void explicitCodeWins() {
        ErrorReport report = ErrorReport.of(new RuntimeException("card declined"), "CARD_DECLINED");

        assertThat(report.getErrorCode()).isEqualTo("CARD_DECLINED");
        assertThat(report.getMessage()).isEqualTo("card declined");
    }

    @Test
    void detailsCarryTheCauseChain() {
        Exception e = new IllegalStateException("payment failed", new SocketTimeoutException("connect timed out"));

        ErrorReport report = ErrorReport.of(e, null);

        assertThat(report.getDetails()).contains("Caused by: java.net.SocketTimeoutException: connect timed out");
    }

    @Test
    void truncateCutsCodeAndDetails() {
        ErrorReport report = ErrorReport.truncate(new ErrorReport("C".repeat(300), "boom", "d".repeat(40_000)));

        assertThat(report.getErrorCode()).hasSize(ErrorReport.MAX_ERROR_CODE);
        assertThat(report.getDetails()).hasSize(ErrorReport.MAX_DETAILS).endsWith(ErrorReport.TRUNCATED);
        assertThat(report.getMessage()).isEqualTo("boom");
    }

    @Test
    void truncateKeepsShortAndMissingValues() {
        ErrorReport report = ErrorReport.truncate(new ErrorReport(null, "boom", null));

        assertThat(report.getErrorCode()).isNull();
        assertThat(report.getDetails()).isNull();

        ErrorReport exact = ErrorReport.truncate(new ErrorReport("C", "boom", "d".repeat(ErrorReport.MAX_DETAILS)));
        assertThat(exact.getDetails()).hasSize(ErrorReport.MAX_DETAILS).doesNotEndWith(ErrorReport.TRUNCATED);
    }
}
