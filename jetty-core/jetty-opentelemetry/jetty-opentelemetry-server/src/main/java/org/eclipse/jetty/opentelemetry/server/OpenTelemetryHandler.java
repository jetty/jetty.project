//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.opentelemetry.server;

import java.net.SocketAddress;
import java.security.Principal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.semconv.ErrorAttributes;
import io.opentelemetry.semconv.SchemaUrls;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This Handler integrates with the OpenTelemetry API to provide spans and metrics as per the <a href="https://opentelemetry.io/docs/specs/semconv/http/">HTTP Server</a> sections.
 * By default, the span name is the method of the request (GET/POST/etc.). If this Handler is after the PathMappingsHandler the name of the span will include the pathspec declaration string in the name (e.g. "GET /hello/*").
 */
public class OpenTelemetryHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(OpenTelemetryHandler.class);

    private static final TextMapGetter<Request> servletGetter =
        new TextMapGetter<>()
        {
            @Override
            public Iterable<String> keys(Request context)
            {
                return context.getHeaders().getFieldNamesCollection();
            }

            @Override
            public String get(Request context, String key)
            {
                return context.getHeaders().get(key);
            }
        };

    private static final Set<String> validAttributeNames = Set.of(
        "http.request.method",
        "http.response.status_code",
        "http.response.body.size",
        "network.protocol.name",
        "network.protocol.version",
        "server.address",
        "server.port",
        "url.full",
        "url.query",
        "user_agent.original"
    );
    private static final Set<String> validMetricNames = Set.of(
        "http.server.request.duration",
        "http.server.request.body.size",
        "http.server.response.body.size"
    );

    private final OpenTelemetry openTelemetry;
    private final Map<String, String> attributeMappings = new HashMap<>();
    private final Map<String, String> metricMappings = new HashMap<>();

    private final TextMapPropagator textMapPropagator;

    private final Tracer tracer;
    private DoubleHistogram requestDuration;
    private LongHistogram requestBodySize;
    private LongHistogram responseBodySize;

    public OpenTelemetryHandler(OpenTelemetry openTelemetry)
    {
        this.openTelemetry = openTelemetry;

        textMapPropagator = openTelemetry.getPropagators().getTextMapPropagator();
        this.tracer = openTelemetry.tracerBuilder(getClass().getName())
            .setSchemaUrl(SchemaUrls.V1_37_0)
            .setInstrumentationVersion(getClass().getPackage().getImplementationVersion())
            .build();
    }

    public OpenTelemetryHandler()
    {
        this(GlobalOpenTelemetry.get());
    }

    /**
     * Add attributes to be included with spans and metrics. Valid names:
     * client.address
     * enduser.id
     * http.request.method
     * http.response.status_code
     * http.response.body.size
     * network.protocol.name
     * network.protocol.version
     * server.address
     * server.port
     * url.full
     * url.query
     * url.scheme
     * user_agent.original
     *
     * @param openTelemetryAttributeName attribute name as specified by OpenTelemetry
     * @param exportedAttributeName attribute name to be exported
     */
    public void addAttribute(String openTelemetryAttributeName, String exportedAttributeName)
    {
        if (!validAttributeNames.contains(openTelemetryAttributeName))
        {
            throw new IllegalArgumentException("%s is not a valid attribute name");
        }

        attributeMappings.put(openTelemetryAttributeName, exportedAttributeName);
    }

    /**
     * Add attributes to be included with spans and metrics. Use this method if the exported name is
     * the same as the OpenTelemetry specification name.
     */
    public void addAttribute(String openTelemetryAttributeName)
    {
        addAttribute(openTelemetryAttributeName, openTelemetryAttributeName);
    }

    /**
     * Add metrics to be exported by this handler. Valid names:
     * http.server.request.duration
     * http.server.request.body.size
     * http.server.response.body.size
     */
    public void addMetric(String openTelemetryMetricName, String exportedMetricName)
    {
        if (!validMetricNames.contains(openTelemetryMetricName))
        {
            throw new IllegalArgumentException("%s is not a valid metric name");
        }

        metricMappings.put(openTelemetryMetricName, exportedMetricName);
    }

    public void addMetric(String openTelemetryMetricName)
    {
        addMetric(openTelemetryMetricName, openTelemetryMetricName);
    }

    @Override
    protected void doStart() throws Exception
    {
        Meter meter = openTelemetry.meterBuilder(getClass().getName())
            .setSchemaUrl(SchemaUrls.V1_37_0)
            .setInstrumentationVersion(getClass().getPackage().getImplementationVersion())
            .build();

        if (metricMappings.get("http.server.request.duration") instanceof String requestDurationAttribute)
        {
            requestDuration = meter.histogramBuilder(requestDurationAttribute)
                .setExplicitBucketBoundariesAdvice(List.of(0.005D, 0.01D, 0.025D, 0.05D, 0.075D, 0.1D, 0.25D, 0.5D, 0.75D, 1D, 2.5D, 5D, 7.5D, 10D))
                .setUnit("s")
                .build();
        }
        if (metricMappings.get("http.server.request.body.size") instanceof String requestBodySizeAttribute)
        {
            requestBodySize = meter.histogramBuilder(requestBodySizeAttribute)
                .ofLongs()
                .setUnit("By")
                .build();
        }
        if (metricMappings.get("http.server.response.body.size") instanceof String responseBodySizeAttribute)
        {
            responseBodySize = meter.histogramBuilder(responseBodySizeAttribute)
                .ofLongs()
                .setUnit("By")
                .build();
        }

        super.doStart();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("handling {} {} {}", request, response, this);

        Handler next = getHandler();
        if (next == null)
            return false;

        Context context = textMapPropagator.extract(Context.current(), request, servletGetter);
        String spanName = request.getAttribute(PathSpec.class.getName()) instanceof PathSpec pathSpec
            ? request.getMethod() + " " + pathSpec.getDeclaration()
            : request.getMethod();

        long start = System.nanoTime();
        Span span = tracer.spanBuilder(spanName)
            .setParent(context)
            .setSpanKind(SpanKind.SERVER)
            .setStartTimestamp(start, TimeUnit.NANOSECONDS)
            .startSpan();

        try (Scope _ = span.makeCurrent())
        {
            Runnable success = () ->
            {
                finish(request, response, span, start, null);
            };
            Consumer<Throwable> failure = throwable ->
            {
                finish(request, response, span, start, throwable);
            };
            return next.handle(request, response, Callback.from(callback, Callback.from(InvocationType.EITHER, success, failure)));
        }
    }

    private void finish(Request request, Response response, Span span, long start, Throwable throwable)
    {
        if (throwable != null)
        {
            span.setStatus(StatusCode.ERROR);
            span.recordException(throwable);
        }
        else if (response.getStatus() >= 500)
        {
            span.setStatus(StatusCode.ERROR);
        }

        Attributes spanAttributes = getAttributes(null, request, response, true, throwable);

        span.setAllAttributes(spanAttributes);

        Attributes attributes = null;
        if (requestDuration != null)
        {
            attributes = getAttributes(attributes, request, response, false, throwable);
            long end = System.nanoTime();
            span.end(end, TimeUnit.NANOSECONDS);
            long duration = NanoTime.elapsed(start, end);
            Duration durationNanos = Duration.ofNanos(duration);
            double durationSeconds = ((double)durationNanos.toMillis()) / 1000D;

            requestDuration.record(durationSeconds, attributes);
        }

        if (requestBodySize != null)
        {
            long length = request.getLength();
            if (length > 0)
            {
                attributes = getAttributes(attributes, request, response, false, throwable);
                requestBodySize.record(length, attributes);
            }
        }
        if (responseBodySize != null)
        {
            var length = response.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
            if (length > 0)
            {
                attributes = getAttributes(attributes, request, response, false, throwable);
                responseBodySize.record(length, attributes);
            }
        }
    }

    private Attributes getAttributes(Attributes attributes, Request request, Response response, boolean isSpan, Throwable throwable)
    {
        if (attributes != null)
            return attributes;

        AttributesBuilder builder = Attributes.builder();

        for (Map.Entry<String, String> mappedAttribute : attributeMappings.entrySet())
        {
            switch (mappedAttribute.getKey())
            {
                case "client.address" ->
                {
                    if (isSpan && request.getConnectionMetaData().getRemoteSocketAddress() instanceof SocketAddress remoteSocketAddress)
                    {
                        builder.put(mappedAttribute.getValue(), remoteSocketAddress.toString());
                    }
                }
                case "enduser.id" ->
                {
                    if (isSpan && Request.getAuthenticationState(request).getUserPrincipal() instanceof Principal principal)
                    {
                        builder.put(mappedAttribute.getValue(), principal.getName());
                    }
                }
                case "http.request.method" -> builder.put(mappedAttribute.getValue(), request.getMethod());
                case "http.response.status_code" -> builder.put(mappedAttribute.getValue(), response.getStatus());
                case "http.response.body.size" ->
                {
                    var length = response.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
                    if (length > 0)
                        builder.put(mappedAttribute.getValue(), length);
                }
                case "network.protocol.name" ->
                    builder.put(mappedAttribute.getValue(), request.getConnectionMetaData().getProtocol());
                case "network.protocol.version" ->
                    builder.put(mappedAttribute.getValue(), request.getConnectionMetaData().getHttpVersion().getShortVersion());
                case "server.address" -> builder.put(mappedAttribute.getValue(), Request.getServerName(request));
                case "server.port" -> builder.put(mappedAttribute.getValue(), Request.getServerPort(request));
                case "url.full" -> builder.put(mappedAttribute.getValue(), request.getHttpURI().asString());
                case "url.query" ->
                {
                    if (isSpan && request.getHttpURI().getQuery() instanceof String query)
                    {
                        builder.put(mappedAttribute.getValue(), query);
                    }
                }
                case "url.scheme" -> builder.put(mappedAttribute.getValue(), request.getHttpURI().getScheme());
                case "user_agent.original" ->
                {
                    if (isSpan && request.getHeaders().get(HttpHeader.USER_AGENT) instanceof String userAgent)
                    {
                        builder.put(mappedAttribute.getValue(), userAgent);
                    }
                }
            }
        }

        if (throwable != null && attributeMappings.get(ErrorAttributes.ERROR_TYPE.getKey()) instanceof String attributeName)
        {
            builder.put(attributeName, throwable.getClass().getName());
        }
        return builder.build();
    }
}
