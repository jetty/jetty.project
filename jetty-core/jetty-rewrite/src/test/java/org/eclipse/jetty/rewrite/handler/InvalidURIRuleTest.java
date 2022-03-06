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

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class InvalidURIRuleTest extends AbstractRuleTest
{
    private void start(InvalidURIRule rule) throws Exception
    {
        _rewriteHandler.addRule(rule);
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                callback.succeeded();
            }
        });
    }

    @Test
    public void testValidUrl() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_FOUND_404);
        start(rule);

        String request = """
            GET /valid/uri.html HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testInvalidUrl() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_FOUND_404);
        start(rule);

        String request = """
            GET /invalid%0c/uri.html HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.NOT_FOUND_404, response.getStatus());
    }

    @Test
    public void testInvalidUrlWithMessage() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.METHOD_NOT_ALLOWED_405);
        rule.setMessage("foo");
        start(rule);

        String request = """
            GET /%01/ HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED_405, response.getStatus());
        assertThat(response.getContent(), containsString(rule.getMessage()));
    }

    @Test
    public void testInvalidJsp() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.BAD_REQUEST_400);
        start(rule);

        String request = """
            GET /jsp/bean1.jsp%00 HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }

    @Test
    public void testInvalidJspWithNullByte() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.BAD_REQUEST_400);
        start(rule);

        String request = """
            GET /jsp/bean1.jsp\000 HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }

    @Test
    public void testInvalidShamrock() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.BAD_REQUEST_400);
        start(rule);

        String request = """
            GET /jsp/shamrock-%00%E2%98%98.jsp HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }

    @Test
    public void testValidShamrock() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_FOUND_404);
        start(rule);

        String request = """
            GET /jsp/shamrock-%E2%98%98.jsp HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(200, response.getStatus());
    }
}
