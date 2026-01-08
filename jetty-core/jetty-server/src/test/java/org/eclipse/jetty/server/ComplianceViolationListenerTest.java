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

package org.eclipse.jetty.server;

import java.util.function.Consumer;

import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ComplianceViolationListenerTest
{
    private Server server;
    private LocalConnector localConnector;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    protected void startServer(Consumer<Server> serverConsumer) throws Exception
    {
        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        if (serverConsumer != null)
            serverConsumer.accept(server);

        server.start();
    }

    @Test
    public void testCleanPath() throws Exception
    {
        testRequestPath("/path/to/resource");
    }

    @Test
    public void testBadPath() throws Exception
    {
        testRequestPath("/path//..//%2e/%2f");
    }

    private void testRequestPath(String rawPath) throws Exception
    {
        UriCompliance uriCompliance = UriCompliance.DEFAULT;

        startServer(server ->
        {
            localConnector.getContainedBeans(HttpConfiguration.class)
                .forEach(httpConfig ->
                {
                    httpConfig.setUriCompliance(uriCompliance);
                    httpConfig.addComplianceViolationListener(new MyComplianceListener());
                });

            server.setHandler(new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback)
                {
                    try
                    {
                        String requestUri = request.getHttpURI().toURI().toASCIIString();
                        Content.Sink.write(response, true, requestUri, callback);
                        return true;
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                }
            });
        });

        String rawRequest = """
            GET %s HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """.formatted(rawPath);

        String rawResponse = localConnector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(200, response.getStatus(), rawResponse);
        String responseBody = response.getContent();
        assertThat(responseBody, containsString(rawPath));
    }

    public static class MyComplianceListener implements ComplianceViolation.Listener
    {
        @Override
        public ComplianceViolation.Listener initialize()
        {
            System.err.println("##CVL## - initialize()");
            return new MyRequestComplianceListener();
        }

        @Override
        public void onComplianceViolation(ComplianceViolation.Event event)
        {
            System.err.println("##CVL## - " + event);
        }
    }

    public static class MyRequestComplianceListener implements ComplianceViolation.Listener
    {
        private Attributes request;

        @Override
        public void onComplianceViolation(ComplianceViolation.Event event)
        {
            System.err.printf("##REQ## (%s) - %s%n", request, event);
        }

        @Override
        public void onRequestBegin(Attributes request)
        {
            System.err.printf("##REQ## (%s) - on RequestBegin%n", request);
            this.request = request;
        }

        @Override
        public void onRequestEnd(Attributes request)
        {
            System.err.printf("##REQ## (%s) - on RequestEnd%n", request);
        }
    }
}
