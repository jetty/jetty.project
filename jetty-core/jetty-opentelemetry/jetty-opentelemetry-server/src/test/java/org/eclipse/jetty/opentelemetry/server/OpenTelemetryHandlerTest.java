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

import java.net.URI;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.PathMappingsHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OpenTelemetryHandlerTest
{
    private Server server;
    private HttpClient client;
    private LocalMetricExporter metricExporter;
    private LocalSpanExporter spanExporter;
    private OpenTelemetry openTelemetry;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new HttpClient();
        client.start();

        var sdkMeterProviderBuilder = SdkMeterProvider.builder();
        metricExporter = new LocalMetricExporter();
        PeriodicMetricReader periodicMetricReader = PeriodicMetricReader.builder(metricExporter)
            .setInterval(5, TimeUnit.SECONDS)
            .build();
        sdkMeterProviderBuilder.registerMetricReader(periodicMetricReader);
        var sdkMeterProvider = sdkMeterProviderBuilder.build();

        var sdkTracerProviderBuilder = SdkTracerProvider.builder();
        spanExporter = new LocalSpanExporter();
        SpanProcessor spanProcessor = SimpleSpanProcessor.builder(spanExporter).setMeterProvider(() -> sdkMeterProvider).build();
        var sdkTracerProvider = sdkTracerProviderBuilder
            .addSpanProcessor(spanProcessor)
            .setSampler(Sampler.alwaysOn())
            .build();

        openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(sdkTracerProvider)
            .setMeterProvider(sdkMeterProvider)
            .build();
    }

    @AfterEach
    public void stopAll()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testOpenTelemetryHandler() throws Exception
    {
        String message = "Hello Jetty!\n".repeat(10);

        OpenTelemetryHandler openTelemetryHandler = new OpenTelemetryHandler(openTelemetry);
        openTelemetryHandler.addAttribute("url.full");
        openTelemetryHandler.addAttribute("http.request.method");
        openTelemetryHandler.addAttribute("http.response.status_code");
        openTelemetryHandler.addAttribute("http.response.body.size");

        openTelemetryHandler.addMetric("http.server.request.duration");
        openTelemetryHandler.addMetric("http.server.request.body.size");
        openTelemetryHandler.addMetric("http.server.response.body.size");

        openTelemetryHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Content.Sink.write(response, true, message, callback);
                return true;
            }
        });

        startServer(openTelemetryHandler);
        URI serverURI = server.getURI();
        client.newRequest(serverURI.getHost(), serverURI.getPort())
            .method(HttpMethod.GET)
            .path("/hello")
            .send();

        for (MetricData metric : metricExporter.getMetrics())
        {
            for (PointData point : metric.getData().getPoints())
            {
                System.out.println(point.getAttributes());
            }
        }
        for (SpanData span : spanExporter.getSpans())
        {
            System.out.println(span.getAttributes());
        }
    }

    @Test
    public void testOpenTelemetryHandlerWithPathSpec() throws Exception
    {
        spanExporter.reset();
        String message = "Hello Jetty!\n".repeat(10);

        PathMappingsHandler pathMappingsHandler = new PathMappingsHandler();

        OpenTelemetryHandler openTelemetryHandler = new OpenTelemetryHandler(openTelemetry);
        openTelemetryHandler.addAttribute("url.full");
        openTelemetryHandler.addAttribute("http.request.method");
        openTelemetryHandler.addAttribute("http.response.status_code");
        openTelemetryHandler.addAttribute("http.response.body.size");

        openTelemetryHandler.addMetric("http.server.request.duration");

        openTelemetryHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Content.Sink.write(response, true, message, callback);
                return true;
            }
        });

        pathMappingsHandler.addMapping(PathSpec.from("/hello/*"), openTelemetryHandler);

        startServer(pathMappingsHandler);
        URI serverURI = server.getURI();
        client.newRequest(serverURI.getHost(), serverURI.getPort())
            .method(HttpMethod.GET)
            .path("/hello/foo")
            .send();

        for (SpanData span : spanExporter.getSpans())
        {
            Assertions.assertEquals("GET /hello/*", span.getName());
        }
    }

    @Test
    public void testOpenTelemetryHandlerInvalidAttribute()
    {
        Assertions.assertThrows(IllegalArgumentException.class, () ->
        {
            OpenTelemetryHandler openTelemetryHandler = new OpenTelemetryHandler(openTelemetry);
            openTelemetryHandler.addAttribute("foo");
        });
    }

    @Test
    public void testOpenTelemetryHandlerInvalidMetric()
    {
        Assertions.assertThrows(IllegalArgumentException.class, () ->
        {
            OpenTelemetryHandler openTelemetryHandler = new OpenTelemetryHandler(openTelemetry);
            openTelemetryHandler.addMetric("foo");
        });
    }

    private void startServer(Handler rootHandler) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        server.setHandler(rootHandler);
        server.start();
    }
}
