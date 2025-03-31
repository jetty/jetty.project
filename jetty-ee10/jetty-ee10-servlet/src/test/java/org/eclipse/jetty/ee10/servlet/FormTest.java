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

package org.eclipse.jetty.ee10.servlet;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.FormRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FormTest
{
    private static final int MAX_FORM_CONTENT_SIZE = 128;
    private static final int MAX_FORM_KEYS = 4;

    private Server server;
    private ServerConnector connector;
    private HttpClient client;
    private String contextPath = "/ctx";
    private String servletPath = "/test_form";

    private void start(Function<ServletContextHandler, HttpServlet> config) throws Exception
    {
        startServer(config);
        startClient();
    }

    private void startServer(Function<ServletContextHandler, HttpServlet> config) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);

        ServletContextHandler handler = new ServletContextHandler(contextPath);
        HttpServlet servlet = config.apply(handler);
        handler.addServlet(new ServletHolder(servlet), servletPath + "/*");
        server.setHandler(handler);

        server.start();
    }

    private void startClient() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }

    public static Stream<Arguments> formContentSizeScenarios()
    {
        return Stream.of(
            Arguments.of(null, ServletContextHandler.DEFAULT_MAX_FORM_CONTENT_SIZE + 1, true, HttpStatus.BAD_REQUEST_400),
            Arguments.of(null, ServletContextHandler.DEFAULT_MAX_FORM_CONTENT_SIZE + 1, false, HttpStatus.BAD_REQUEST_400),
            Arguments.of(-1, null,  true, HttpStatus.OK_200),
            Arguments.of(-1, null, false, HttpStatus.OK_200),
            Arguments.of(0, null, true, HttpStatus.BAD_REQUEST_400),
            Arguments.of(0, null, false, HttpStatus.BAD_REQUEST_400),
            Arguments.of(MAX_FORM_CONTENT_SIZE, MAX_FORM_CONTENT_SIZE + 1, true, HttpStatus.BAD_REQUEST_400),
            Arguments.of(MAX_FORM_CONTENT_SIZE, MAX_FORM_CONTENT_SIZE + 1, false, HttpStatus.BAD_REQUEST_400),
            Arguments.of(MAX_FORM_CONTENT_SIZE, MAX_FORM_CONTENT_SIZE, true, HttpStatus.OK_200),
            Arguments.of(MAX_FORM_CONTENT_SIZE, MAX_FORM_CONTENT_SIZE, false, HttpStatus.OK_200)
        );
    }

    @ParameterizedTest
    @MethodSource("formContentSizeScenarios")
    public void testMaxFormContentSizeExceeded(Integer maxFormContentSize, Integer contentSize, boolean withContentLength, int expectedStatus) throws Exception
    {
        if (contentSize == null)
            contentSize = ServletContextHandler.DEFAULT_MAX_FORM_CONTENT_SIZE;

        start(handler ->
        {
            if (maxFormContentSize != null)
                handler.setMaxFormContentSize(maxFormContentSize);
            return new HttpServlet()
            {
                @Override
                protected void service(HttpServletRequest request, HttpServletResponse response)
                {
                    request.getParameterMap();
                }
            };
        });

        AsyncRequestContent content = newContent(contentSize);
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.POST)
            .path(contextPath + servletPath)
            .headers(headers -> headers.put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.FORM_ENCODED.asString()))
            .body(content)
            .onRequestBegin(request ->
            {
                if (withContentLength)
                    content.close();
            })
            .onRequestCommit(request ->
            {
                if (!withContentLength)
                    content.close();
            })
            .send();

        assertEquals(expectedStatus, response.getStatus());
    }

    private AsyncRequestContent newContent(int size)
    {
        byte[] key = "foo=".getBytes(StandardCharsets.US_ASCII);
        byte[] value = new byte[size - key.length];
        Arrays.fill(value, (byte)'x');
        return new AsyncRequestContent(ByteBuffer.wrap(key), ByteBuffer.wrap(value));
    }

    public static Stream<Integer> formKeysScenarios()
    {
        return Stream.of(null, -1, 0, MAX_FORM_KEYS);
    }

    @ParameterizedTest
    @MethodSource("formKeysScenarios")
    public void testMaxFormKeysExceeded(Integer maxFormKeys) throws Exception
    {
        start(handler ->
        {
            if (maxFormKeys != null)
                handler.setMaxFormKeys(maxFormKeys);
            return new HttpServlet()
            {
                @Override
                protected void service(HttpServletRequest request, HttpServletResponse response)
                {
                    request.getParameterMap();
                }
            };
        });

        int keys = (maxFormKeys == null || maxFormKeys < 0)
            ? ServletContextHandler.DEFAULT_MAX_FORM_KEYS
            : maxFormKeys;
        // Have at least one key.
        keys = keys + 1;
        Fields formParams = new Fields();
        for (int i = 0; i < keys; ++i)
        {
            formParams.add("key_" + i, "value_" + i);
        }
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.POST)
            .path(contextPath + servletPath)
            .body(new FormRequestContent(formParams))
            .send();

        int expected = (maxFormKeys != null && maxFormKeys < 0)
            ? HttpStatus.OK_200
            : HttpStatus.BAD_REQUEST_400;
        assertEquals(expected, response.getStatus());
    }

    @Test
    public void testContentTypeWithNonCharsetParameter() throws Exception
    {
        String contentType = MimeTypes.Type.FORM_ENCODED.asString() + "; p=v";
        String paramName = "name1";
        String paramValue = "value1";
        start(handler -> new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                assertEquals(contentType, request.getContentType());
                Map<String, String[]> params = request.getParameterMap();
                assertEquals(1, params.size());
                assertEquals(paramValue, params.get(paramName)[0]);
            }
        });

        Fields formParams = new Fields();
        formParams.add(paramName, paramValue);
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.POST)
            .path(contextPath + servletPath)
            .headers(headers -> headers.put(HttpHeader.CONTENT_TYPE, contentType))
            .body(new FormRequestContent(formParams))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }
}
