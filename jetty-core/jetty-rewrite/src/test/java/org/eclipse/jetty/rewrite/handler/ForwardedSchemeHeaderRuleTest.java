//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.rewrite.handler;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ForwardedSchemeHeaderRuleTest extends AbstractRuleTest
{
    public void start(ForwardedSchemeHeaderRule rule) throws Exception
    {
        _rewriteHandler.addRule(rule);
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.getHeaders().put("request-scheme", request.getHttpURI().getScheme());
                callback.succeeded();
            }
        });
    }

    @Test
    public void testDefaultScheme() throws Exception
    {
        ForwardedSchemeHeaderRule rule = new ForwardedSchemeHeaderRule();
        rule.setHeaderName("X-Forwarded-Scheme");
        rule.setHeaderValue("https");
        start(rule);

        String request = """
            GET / HTTP/1.1
            Host: local
            X-Forwarded-Scheme: https
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(200, response.getStatus());
        assertEquals("https", response.get("request-scheme"));
    }

    @Test
    public void testScheme() throws Exception
    {
        ForwardedSchemeHeaderRule rule = new ForwardedSchemeHeaderRule();
        rule.setHeaderName("X-Forwarded-Scheme");
        rule.setHeaderValue("https");
        rule.setScheme("wss");
        start(rule);

        String request = """
            GET / HTTP/1.1
            Host: local
            X-Forwarded-Scheme: https
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(200, response.getStatus());
        assertEquals("wss", response.get("request-scheme"));
    }

    @Test
    public void testHeaderValue() throws Exception
    {
        ForwardedSchemeHeaderRule rule = new ForwardedSchemeHeaderRule();
        rule.setHeaderName("Front-End-Https");
        rule.setHeaderValue("on");
        rule.setScheme("http");
        start(rule);

        String request = """
            GET / HTTP/1.1
            Host: local
            Front-End-Https: on
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(200, response.getStatus());
        assertEquals("http", response.get("request-scheme"));

        // Value does not match, scheme is retained.
        rule.setScheme("other");
        request = """
            GET other://local/ HTTP/1.1
            Host: local
            Front-End-Https: off
            
            """;

        response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(200, response.getStatus());
        assertEquals("other", response.get("request-scheme"));

        rule.setScheme("ws");
        // Null value should match.
        rule.setHeaderValue(null);
        request = """
            GET / HTTP/1.1
            Host: local
            Front-End-Https: any
            
            """;

        response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(200, response.getStatus());
        assertEquals("ws", response.get("request-scheme"));
    }
}
