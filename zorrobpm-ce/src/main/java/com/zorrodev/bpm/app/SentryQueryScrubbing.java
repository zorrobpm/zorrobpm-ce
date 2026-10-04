package com.zorrodev.bpm.app;

import io.sentry.Breadcrumb;
import io.sentry.SentryOptions;
import io.sentry.SpanContext;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentrySpan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps query strings out of Sentry. They carry who is asking - the tasklist sends the person's IIN
 * and groups as {@code relatedToUser} / {@code relatedToGroups} - and the Spring integration of the
 * SDK records them regardless of {@code send-default-pii}: in the request of events and
 * transactions, in the {@code http.query} of client spans and breadcrumbs.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(SentryOptions.class)
public class SentryQueryScrubbing {

    private static final String QUERY = "http.query";

    @Bean
    public SentryOptions.BeforeSendCallback sentryScrubEventQuery() {
        return (event, hint) -> {
            scrub(event.getRequest());
            scrubBreadcrumbs(event.getBreadcrumbs());
            return event;
        };
    }

    @Bean
    public SentryOptions.BeforeSendTransactionCallback sentryScrubTransactionQuery() {
        return (transaction, hint) -> {
            scrub(transaction.getRequest());
            scrubBreadcrumbs(transaction.getBreadcrumbs());
            SpanContext trace = transaction.getContexts().getTrace();
            if (trace != null) {
                scrub(trace.getData());
                if (trace.getDescription() != null) {
                    trace.setDescription(withoutQuery(trace.getDescription()));
                }
            }
            for (SentrySpan span : transaction.getSpans()) {
                if (span.getData() != null) {
                    Map<String, Object> data = new HashMap<>(span.getData());
                    scrub(data);
                    span.setData(data);
                }
            }
            return transaction;
        };
    }

    @Bean
    public SentryOptions.BeforeBreadcrumbCallback sentryScrubBreadcrumbQuery() {
        return (breadcrumb, hint) -> {
            scrub(breadcrumb);
            return breadcrumb;
        };
    }

    static void scrub(Request request) {
        if (request != null) {
            request.setQueryString(null);
            if (request.getUrl() != null) {
                request.setUrl(withoutQuery(request.getUrl()));
            }
        }
    }

    static void scrub(Breadcrumb breadcrumb) {
        breadcrumb.removeData(QUERY);
        if (breadcrumb.getData("url") instanceof String url) {
            breadcrumb.setData("url", withoutQuery(url));
        }
    }

    static void scrub(Map<String, Object> data) {
        if (data == null) {
            return;
        }
        data.remove(QUERY);
        data.replaceAll((key, value) -> value instanceof String text && key.contains("url") ? withoutQuery(text) : value);
    }

    private static void scrubBreadcrumbs(List<Breadcrumb> breadcrumbs) {
        if (breadcrumbs != null) {
            breadcrumbs.forEach(SentryQueryScrubbing::scrub);
        }
    }

    static String withoutQuery(String text) {
        int query = text.indexOf('?');
        return query < 0 ? text : text.substring(0, query);
    }
}
