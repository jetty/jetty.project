//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Collections;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ForceRequestHeaderValueRuleTest
{
    private Server server;
    private LocalConnector connector;
    private ForceRequestHeaderValueRule rule;

    @BeforeEach
    public void setup() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        HandlerList handlers = new HandlerList();

        RewriteHandler rewriteHandler = new RewriteHandler();
        rule = new ForceRequestHeaderValueRule();
        rewriteHandler.addRule(rule);

        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setContentType("text/plain");
                response.setCharacterEncoding("utf-8");
                OutputStream stream = response.getOutputStream();
                OutputStreamWriter out = new OutputStreamWriter(stream);
                out.append("Echo\n");
                for (String headerName : Collections.list(request.getHeaderNames()))
                {
                    // Combine all values for header into single output on response body
                    out.append("Request Header[").append(headerName).append("]: [")
                        .append(request.getHeader(headerName)).append("]\n");
                }
                out.flush();
                baseRequest.setHandled(true);
            }
        };

        handlers.addHandler(rewriteHandler);
        handlers.addHandler(handler);
        server.setHandler(handlers);
        server.start();
    }

    @AfterEach
    public void teardown()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testNormalRequest() throws Exception
    {
        rule.setHeaderName("Accept");
        rule.setForcedValue("*/*");

        StringBuilder request = new StringBuilder();
        request.append("GET /echo/foo HTTP/1.1\r\n");
        request.append("Host: local\r\n");
        request.append("Connection: closed\r\n");
        request.append("\r\n");

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request.toString()));
        assertEquals(200, response.getStatus());
        assertThat(response.getContent(), not(containsString("[Accept]")));
        assertThat(response.getContent(), containsString("[Host]: [local]"));
        assertThat(response.getContent(), containsString("[Connection]: [closed]"));
    }

    @Test
    public void testOneAcceptHeaderRequest() throws Exception
    {
        rule.setHeaderName("Accept");
        rule.setForcedValue("*/*");

        StringBuilder request = new StringBuilder();
        request.append("GET /echo/foo HTTP/1.1\r\n");
        request.append("Host: local\r\n");
        request.append("Accept: */*\r\n");
        request.append("Connection: closed\r\n");
        request.append("\r\n");

        String rawResponse = connector.getResponse(request.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(200, response.getStatus());
        assertThat(response.getContent(), containsString("[Accept]: [*/*]"));
        assertThat(response.getContent(), containsString("[Host]: [local]"));
        assertThat(response.getContent(), containsString("[Connection]: [closed]"));
    }

    @Test
    public void testThreeAcceptHeadersRequest() throws Exception
    {
        rule.setHeaderName("Accept");
        rule.setForcedValue("*/*");

        StringBuilder request = new StringBuilder();
        request.append("GET /echo/foo HTTP/1.1\r\n");
        request.append("Host: local\r\n");
        request.append("Accept: images/jpeg\r\n");
        request.append("Accept: text/plain\r\n");
        request.append("Accept: */*\r\n");
        request.append("Connection: closed\r\n");
        request.append("\r\n");

        String rawResponse = connector.getResponse(request.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(200, response.getStatus());
        assertThat(response.getContent(), containsString("[Accept]: [*/*]"));
        assertThat(response.getContent(), containsString("[Host]: [local]"));
        assertThat(response.getContent(), containsString("[Connection]: [closed]"));
    }

    @Test
    public void testInterleavedAcceptHeadersRequest() throws Exception
    {
        rule.setHeaderName("Accept");
        rule.setForcedValue("*/*");

        StringBuilder request = new StringBuilder();
        request.append("GET /echo/foo HTTP/1.1\r\n");
        request.append("Host: local\r\n");
        request.append("Accept: images/jpeg\r\n"); // not value intended to be forced
        request.append("Accept-Encoding: gzip;q=1.0, identity; q=0.5, *;q=0\r\n");
        request.append("accept: text/plain\r\n"); // interleaved with other headers shouldn't matter
        request.append("Accept-Charset: iso-8859-5, unicode-1-1;q=0.8\r\n");
        request.append("ACCEPT: */*\r\n"); // case shouldn't matter
        request.append("Connection: closed\r\n");
        request.append("\r\n");

        String rawResponse = connector.getResponse(request.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(200, response.getStatus());
        assertThat(response.getContent(), containsString("[Accept]: [*/*]"));
        assertThat(response.getContent(), containsString("[Accept-Charset]: [iso-8859-5, unicode-1-1;q=0.8]"));
        assertThat(response.getContent(), containsString("[Accept-Encoding]: [gzip;q=1.0, identity; q=0.5, *;q=0]"));
        assertThat(response.getContent(), containsString("[Host]: [local]"));
        assertThat(response.getContent(), containsString("[Connection]: [closed]"));
    }
}
