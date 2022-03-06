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

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ForceRequestHeaderValueRuleTest extends AbstractRuleTest
{
    public void start(ForceRequestHeaderValueRule rule) throws Exception
    {
        _rewriteHandler.addRule(rule);
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.setContentType("text/plain;charset=utf-8");
                for (HttpField httpField : request.getHeaders())
                {
                    response.write(false, Callback.NOOP, "Request Header[%s]: [%s]%n".formatted(httpField.getName(), httpField.getValue()));
                }
                response.write(true, callback, BufferUtil.EMPTY_BUFFER);
            }
        });
    }

    @Test
    public void testNormalRequest() throws Exception
    {
        ForceRequestHeaderValueRule rule = new ForceRequestHeaderValueRule();
        rule.setHeaderName("Accept");
        rule.setHeaderValue("*/*");
        start(rule);

        String request = """
            GET /echo/foo HTTP/1.1
            Host: local
            Connection: close
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request));
        assertEquals(200, response.getStatus());
        assertThat(response.getContent(), not(containsString("[Accept]")));
        assertThat(response.getContent(), containsString("[Host]: [local]"));
        assertThat(response.getContent(), containsString("[Connection]: [close]"));
    }

    @Test
    public void testOneAcceptHeaderRequest() throws Exception
    {
        ForceRequestHeaderValueRule rule = new ForceRequestHeaderValueRule();
        rule.setHeaderName("Accept");
        rule.setHeaderValue("*/*");
        start(rule);

        String request = """
            GET /echo/foo HTTP/1.1
            Host: local
            Accept: */*
            Connection: closed
            
            """;

        String rawResponse = _connector.getResponse(request);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(200, response.getStatus());
        assertThat(response.getContent(), containsString("[Accept]: [*/*]"));
        assertThat(response.getContent(), containsString("[Host]: [local]"));
        assertThat(response.getContent(), containsString("[Connection]: [closed]"));
    }

    @Test
    public void testThreeAcceptHeadersRequest() throws Exception
    {
        ForceRequestHeaderValueRule rule = new ForceRequestHeaderValueRule();
        rule.setHeaderName("Accept");
        rule.setHeaderValue("text/*");
        start(rule);

        String request = """
            GET /echo/foo HTTP/1.1
            Host: local
            Accept: images/jpeg
            Accept: text/plain
            Accept: */*
            Connection: closed
            
            """;

        String rawResponse = _connector.getResponse(request);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(200, response.getStatus());
        assertThat(response.getContent(), containsString("[Accept]: [text/*]"));
        assertThat(response.getContent(), containsString("[Host]: [local]"));
        assertThat(response.getContent(), containsString("[Connection]: [closed]"));
    }

    @Test
    public void testInterleavedAcceptHeadersRequest() throws Exception
    {
        ForceRequestHeaderValueRule rule = new ForceRequestHeaderValueRule();
        rule.setHeaderName("Accept");
        rule.setHeaderValue("application/*");
        start(rule);

        String request = """
            GET /echo/foo HTTP/1.1
            Host: local
            Accept: images/jpeg
            Accept-Encoding: gzip;q=1.0, identity; q=0.5, *;q=0
            accept: text/plain
            Accept-Charset: iso-8859-5, unicode-1-1;q=0.8
            ACCEPT: */*
            Connection: closed
            
            """;

        String rawResponse = _connector.getResponse(request);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(200, response.getStatus());
        assertThat(response.getContent(), containsString("[Accept]: [application/*]"));
        assertThat(response.getContent(), containsString("[Accept-Charset]: [iso-8859-5, unicode-1-1;q=0.8]"));
        assertThat(response.getContent(), containsString("[Accept-Encoding]: [gzip;q=1.0, identity; q=0.5, *;q=0]"));
        assertThat(response.getContent(), containsString("[Host]: [local]"));
        assertThat(response.getContent(), containsString("[Connection]: [closed]"));
    }
}
