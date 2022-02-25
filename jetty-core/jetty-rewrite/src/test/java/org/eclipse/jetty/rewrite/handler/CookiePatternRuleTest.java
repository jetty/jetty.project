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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.fail;

public class CookiePatternRuleTest
{
    private Server server;
    private LocalConnector localConnector;

    public void startServer(CookiePatternRule rule) throws Exception
    {
        server = new Server();
        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        RewriteHandler rewriteHandler = new RewriteHandler();
//        rewriteHandler.setRewriteRequestURI(false);
        rewriteHandler.addRule(rule);

        Handler dummyHandler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setContentType("text/plain");
                response.setCharacterEncoding("utf-8");
                PrintWriter out = response.getWriter();
                out.printf("target=%s%n", target);
                out.printf("baseRequest.requestUri=%s%n", baseRequest.getRequestURI());
                out.printf("baseRequest.originalUri=%s%n", baseRequest.getOriginalURI());
                out.printf("request.requestUri=%s%n", request.getRequestURI());
                out.printf("request.queryString=%s%n", request.getQueryString());
                baseRequest.setHandled(true);
            }
        };

        server.setHandler(new HandlerList(rewriteHandler, dummyHandler));
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (server != null)
        {
            server.stop();
        }
    }

    @BeforeEach
    public void init() throws Exception
    {
        CookiePatternRule rule = new CookiePatternRule();
        rule.setPattern("*");
        rule.setName("cookie");
        rule.setValue("value");

        startServer(rule);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET / HTTP/1.1\r\n");
        rawRequest.append("Host: local\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = localConnector.getResponse(rawRequest.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        // verify
        HttpField setCookieField = response.getField(HttpHeader.SET_COOKIE);
        assertThat("response should have Set-Cookie", setCookieField, notNullValue());
        for (String value : setCookieField.getValues())
        {
            String[] result = value.split("=");
            assertThat(result[0], is("cookie"));
            assertThat(result[1], is("value"));
        }
    }

    @Test
    public void testSetAlready() throws Exception
    {
        CookiePatternRule rule = new CookiePatternRule();
        rule.setPattern("*");
        rule.setName("set");
        rule.setValue("already");

        startServer(rule);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET / HTTP/1.1\r\n");
        rawRequest.append("Host: local\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("Cookie: set=already\r\n"); // already present on request
        rawRequest.append("\r\n");

        String rawResponse = localConnector.getResponse(rawRequest.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        // verify
        assertThat("response should not have Set-Cookie", response.getField(HttpHeader.SET_COOKIE), nullValue());
    }

    @Test
    public void testUrlQuery() throws Exception
    {
        CookiePatternRule rule = new CookiePatternRule();
        rule.setPattern("*");
        rule.setName("fruit");
        rule.setValue("banana");

        startServer(rule);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /other?fruit=apple HTTP/1.1\r\n");
        rawRequest.append("Host: local\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = localConnector.getResponse(rawRequest.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        String responseContent = response.getContent();
        assertResponseContentLine(responseContent, "baseRequest.requestUri=", "/other");
        assertResponseContentLine(responseContent, "request.queryString=", "fruit=apple");

        // verify
        HttpField setCookieField = response.getField(HttpHeader.SET_COOKIE);
        assertThat("response should have Set-Cookie", setCookieField, notNullValue());
        for (String value : setCookieField.getValues())
        {
            String[] result = value.split("=");
            assertThat(result[0], is("fruit"));
            assertThat(result[1], is("banana"));
        }
    }

    @Test
    public void testUrlParameter() throws Exception
    {
        CookiePatternRule rule = new CookiePatternRule();
        rule.setPattern("*");
        rule.setName("fruit");
        rule.setValue("banana");

        startServer(rule);

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /other;fruit=apple HTTP/1.1\r\n");
        rawRequest.append("Host: local\r\n");
        rawRequest.append("Connection: close\r\n");
        rawRequest.append("\r\n");

        String rawResponse = localConnector.getResponse(rawRequest.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        String responseContent = response.getContent();
        assertResponseContentLine(responseContent, "baseRequest.requestUri=", "/other;fruit=apple");

        // verify
        HttpField setCookieField = response.getField(HttpHeader.SET_COOKIE);
        assertThat("response should have Set-Cookie", setCookieField, notNullValue());
        for (String value : setCookieField.getValues())
        {
            String[] result = value.split("=");
            assertThat(result[0], is("fruit"));
            assertThat(result[1], is("banana"));
        }
    }

    private void assertResponseContentLine(String responseContent, String linePrefix, String expectedEquals) throws IOException
    {
        String line;
        try (StringReader stringReader = new StringReader(responseContent);
             BufferedReader bufferedReader = new BufferedReader(stringReader))
        {
            boolean foundIt = false;
            while ((line = bufferedReader.readLine()) != null)
            {
                if (line.startsWith(linePrefix))
                {
                    if (foundIt)
                    {
                        // duplicate lines
                        fail("Found multiple lines prefixed with: " + linePrefix);
                    }
                    // found it
                    String actualValue = line.substring(linePrefix.length());
                    assertThat("Line:" + linePrefix, actualValue, is(expectedEquals));
                    foundIt = true;
                }
            }

            if (!foundIt)
            {
                fail("Unable to find line prefixed with: " + linePrefix);
            }
        }
    }
}
