package com.zorrodev.bpm.exchange;

import lombok.Getter;
import lombok.Setter;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * What an incident records about a failure: a machine-readable code, a human-readable message and
 * details for analysis (the stack trace). One place builds it for every source of a failure - the
 * job handler starter, the engine itself and the API - so the format cannot drift.
 */
@Getter
@Setter
public class ErrorReport {

    public static final int MAX_ERROR_CODE = 255;
    public static final int MAX_DETAILS = 32_000;
    public static final String TRUNCATED = "… [truncated]";

    private String errorCode;
    private String message;
    private String details;

    public ErrorReport() {
    }

    public ErrorReport(String errorCode, String message, String details) {
        this.errorCode = errorCode;
        this.message = message;
        this.details = details;
    }

    /**
     * Describes the exception: the given code or the exception class, the exception text or the class
     * when there is no text, and the stack trace with its causes.
     */
    public static ErrorReport of(Throwable e, String errorCode) {
        String className = e.getClass().getName();
        String text = e.getMessage();
        StringWriter stackTrace = new StringWriter();
        e.printStackTrace(new PrintWriter(stackTrace));
        return truncate(new ErrorReport(
            errorCode != null && !errorCode.isBlank() ? errorCode : className,
            text != null && !text.isBlank() ? text : className,
            stackTrace.toString()));
    }

    /**
     * Cuts the code and the details to the lengths an incident stores; the details keep a mark that
     * they were cut.
     */
    public static ErrorReport truncate(ErrorReport report) {
        String errorCode = report.getErrorCode();
        if (errorCode != null && errorCode.length() > MAX_ERROR_CODE) {
            errorCode = errorCode.substring(0, MAX_ERROR_CODE);
        }
        String details = report.getDetails();
        if (details != null && details.length() > MAX_DETAILS) {
            details = details.substring(0, MAX_DETAILS - TRUNCATED.length()) + TRUNCATED;
        }
        return new ErrorReport(errorCode, report.getMessage(), details);
    }
}
