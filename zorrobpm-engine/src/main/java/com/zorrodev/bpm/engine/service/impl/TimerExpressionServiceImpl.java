package com.zorrodev.bpm.engine.service.impl;

import com.zorrodev.bpm.contract.model.ProcessVariable;
import com.zorrodev.bpm.engine.bpmn.model.BpmnElementModel;
import com.zorrodev.bpm.engine.bpmn.model.TimerEventExtensionModel;
import com.zorrodev.bpm.engine.dto.TimerSchedule;
import com.zorrodev.bpm.engine.service.ScriptService;
import com.zorrodev.bpm.engine.service.TimerExpressionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAmount;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Timer values: ISO-8601 literals or FEEL expressions (leading "="). Durations may mix calendar
 * units (years, months, weeks, days) and time units; calendar units are added on the UTC calendar.
 */
@Service
@RequiredArgsConstructor
public class TimerExpressionServiceImpl implements TimerExpressionService {

    private static final Pattern CYCLE = Pattern.compile("^R(\\d*)/(P\\S+)$");

    private final ScriptService scriptService;

    @Override
    public TimerSchedule schedule(BpmnElementModel boundaryEvent, List<ProcessVariable> variables, Instant now) {
        TimerEventExtensionModel timer = boundaryEvent.getExtensions().getTimerEventExtension();
        try {
            Object value = evaluate(timer.getExpression(), variables);
            return switch (timer.getType()) {
                case DATE -> TimerSchedule.once(toInstant(value));
                case DURATION -> TimerSchedule.once(plus(now, toAmount(value)));
                case CYCLE -> cycle(value, now);
            };
        } catch (IllegalStateException | IllegalArgumentException | DateTimeException | ArithmeticException e) {
            throw new IllegalStateException("Boundary event '" + boundaryEvent.getId() + "': timer '"
                + timer.getExpression() + "' is not supported: " + e.getMessage(), e);
        }
    }

    @Override
    public Instant next(String cycleInterval, Instant previousDueAt) {
        return plus(previousDueAt, parseDuration(cycleInterval));
    }

    private Object evaluate(String expression, List<ProcessVariable> variables) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalStateException("no value");
        }
        if (!expression.startsWith("=")) {
            return expression.trim();
        }
        Object result = scriptService.evaluateExpression(expression.substring(1), variables);
        if (result == null) {
            throw new IllegalStateException("expression evaluated to null");
        }
        return result;
    }

    private static TimerSchedule cycle(Object value, Instant now) {
        if (!(value instanceof String text)) {
            throw new IllegalStateException("a cycle must be a string like R3/PT10M, got " + value.getClass().getSimpleName());
        }
        Matcher matcher = CYCLE.matcher(text.trim());
        if (!matcher.matches()) {
            throw new IllegalStateException("only cycles R<n>/<duration> and R/<duration> are supported");
        }
        String interval = matcher.group(2);
        Instant dueAt = plus(now, parseDuration(interval));
        if (matcher.group(1).isEmpty()) {
            return new TimerSchedule(dueAt, interval, null);
        }
        int repetitions = Integer.parseInt(matcher.group(1));
        if (repetitions < 1) {
            throw new IllegalStateException("a cycle must repeat at least once");
        }
        return new TimerSchedule(dueAt, interval, repetitions - 1);
    }

    private static Instant toInstant(Object value) {
        if (value instanceof String text) {
            return parseDate(text);
        }
        if (value instanceof ZonedDateTime date) {
            return date.toInstant();
        }
        if (value instanceof OffsetDateTime date) {
            return date.toInstant();
        }
        if (value instanceof LocalDateTime date) {
            return date.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        throw new IllegalStateException("a date must be a date and time, got " + value.getClass().getSimpleName());
    }

    private static TemporalAmount[] toAmount(Object value) {
        if (value instanceof String text) {
            return parseDuration(text);
        }
        if (value instanceof Duration duration) {
            return new TemporalAmount[]{duration};
        }
        if (value instanceof Period period) {
            return new TemporalAmount[]{period};
        }
        throw new IllegalStateException("a duration must be a duration, got " + value.getClass().getSimpleName());
    }

    private static Instant parseDate(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            // no offset, or a zone id
        }
        try {
            return ZonedDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            // no offset
        }
        return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
    }

    /**
     * {@code P[n]Y[n]M[n]W[n]DT[n]H[n]M[n]S}: the date part goes to {@link Period}, the time part to
     * {@link Duration}, since neither parses the other's units.
     */
    private static TemporalAmount[] parseDuration(String text) {
        if (!text.startsWith("P") || text.equals("P") || text.endsWith("T")) {
            throw new DateTimeParseException("not an ISO-8601 duration", text, 0);
        }
        int t = text.indexOf('T');
        String datePart = t < 0 ? text : text.substring(0, t);
        Period period = datePart.equals("P") ? Period.ZERO : Period.parse(datePart);
        Duration duration = t < 0 ? Duration.ZERO : Duration.parse("PT" + text.substring(t + 1));
        return new TemporalAmount[]{period, duration};
    }

    private static Instant plus(Instant from, TemporalAmount... amounts) {
        ZonedDateTime result = from.atZone(ZoneOffset.UTC);
        for (TemporalAmount amount : amounts) {
            result = result.plus(amount);
        }
        Instant dueAt = result.toInstant();
        if (!dueAt.isAfter(from)) {
            throw new IllegalStateException("the duration must be positive");
        }
        return dueAt;
    }
}
