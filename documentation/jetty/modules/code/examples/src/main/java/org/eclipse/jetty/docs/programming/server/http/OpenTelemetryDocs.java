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

package org.eclipse.jetty.docs.programming.server.http;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.eclipse.jetty.opentelemetry.server.OpenTelemetryHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;

public class OpenTelemetryDocs
{
    public void createHandler()
    {
        // tag::createHandler[]
        OpenTelemetry openTelemetry = GlobalOpenTelemetry.get();
        OpenTelemetryHandler handlerWithInstance = new OpenTelemetryHandler(openTelemetry);
        // or
        OpenTelemetryHandler handler = new OpenTelemetryHandler();
        // end::createHandler[]
    }

    public void addAttributes()
    {
        // tag::addAttribute[]
        OpenTelemetryHandler handler = new OpenTelemetryHandler();
        // If exported attribute name is the same as the OpenTelemetry attribute name.
        handler.addAttribute("enduser.id");
        // If exported attribute name is different from OpenTelemetry attribute.
        handler.addAttribute("http.request.method", "method");
        // end::addAttribute[]
    }

    public void addMetric()
    {
        // tag::addMetric[]
        OpenTelemetryHandler handler = new OpenTelemetryHandler();
        // If exported metric name is the same as the OpenTelemetry metric name.
        handler.addMetric("http.server.request.duration");
        // If exported metric name is different from OpenTelemetry metric.
        handler.addMetric("http.server.request.duration", "duration");
        // end::addMetric[]
    }

    public void exampleUsage()
    {
        Server server = null;
        Handler nextHandler = null;
        // tag::exampleUsage[]
        OpenTelemetry openTelemetry = GlobalOpenTelemetry.get();
        OpenTelemetryHandler otelHandler = new OpenTelemetryHandler(openTelemetry);

        // Configure attributes
        otelHandler.addAttribute("client.address", "client_ip");
        otelHandler.addAttribute("http.response.status_code", "status_code");

        // Configure metrics
        otelHandler.addMetric("http.server.request.duration");
        otelHandler.addMetric("http.server.response.body.size");

        // Add to the handler chain
        server.insertHandler(otelHandler);
        // end::exampleUsage[]
    }
}
