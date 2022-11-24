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
            public void doProcess(Request request, Response response, Callback callback)
            {
                callback.succeeded();
            }
        });
    }

    @Test
    public void testValidUrl() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_ACCEPTABLE_406);
        start(rule);

        String request = """
            GET /valid/uri.html HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testInvalidUrl() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_ACCEPTABLE_406);
        start(rule);

        String request = """
            GET /invalid%0c/uri.html HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.NOT_ACCEPTABLE_406, response.getStatus());
    }

    @Test
    public void testInvalidUrlWithMessage() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_ACCEPTABLE_406);
        rule.setMessage("foo");
        start(rule);

        String request = """
            GET /%01/ HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.NOT_ACCEPTABLE_406, response.getStatus());
        assertThat(response.getContent(), containsString(rule.getMessage()));
    }

    @Test
    public void testInvalidJspWithNullByteEncoded() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_ACCEPTABLE_406);
        start(rule);

        String request = """
            GET /jsp/bean1.jsp%00 HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        // The rule is not invoked because byte NULL is rejected at parsing level.
        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }

    @Test
    public void testInvalidJspWithControlByteEncoded() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_ACCEPTABLE_406);
        start(rule);

        String request = """
            GET /jsp/bean1.jsp%01 HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.NOT_ACCEPTABLE_406, response.getStatus());
    }

    @Test
    public void testInvalidJspWithNullByte() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_ACCEPTABLE_406);
        start(rule);

        String request = """
            GET /jsp/bean1.jsp\000 HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        // The rule is not invoked because byte NULL is rejected at parsing level.
        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }

    @Test
    public void testInvalidJspWithControlByte() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_ACCEPTABLE_406);
        start(rule);

        String request = """
            GET /jsp/bean1.jsp\001 HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        // The rule is not invoked because byte CNTL bytes are rejected at parsing level.
        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }

    @Test
    public void testInvalidShamrockWithNullByte() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_ACCEPTABLE_406);
        start(rule);

        String request = """
            GET /jsp/shamrock-%00%E2%98%98.jsp HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        // The rule is not invoked because byte NULL is rejected at parsing level.
        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }

    @Test
    public void testInvalidShamrockWithControlByteEncoded() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_ACCEPTABLE_406);
        start(rule);

        String request = """
            GET /jsp/shamrock-%0F%E2%98%98.jsp HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.NOT_ACCEPTABLE_406, response.getStatus());
    }

    @Test
    public void testValidShamrock() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_ACCEPTABLE_406);
        start(rule);

        String request = """
            GET /jsp/shamrock-%E2%98%98.jsp HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testInvalidUTF8() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_ACCEPTABLE_406);
        start(rule);

        String request = """
            GET /jsp/shamrock-%A0%A1.jsp HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        // The rule is not invoked because the UTF-8 sequence is invalid.
        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }

    @Test
    public void testIncompleteUTF8() throws Exception
    {
        InvalidURIRule rule = new InvalidURIRule();
        rule.setCode(HttpStatus.NOT_ACCEPTABLE_406);
        start(rule);

        String request = """
            GET /foo%CE%BA%E1 HTTP/1.1
            Host: localhost
                        
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        // The rule is not invoked because the UTF-8 sequence is incomplete.
        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }
}
