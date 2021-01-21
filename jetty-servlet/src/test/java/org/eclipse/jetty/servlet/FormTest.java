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

package org.eclipse.jetty.servlet;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.api.AfterEach;
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

        ServletContextHandler handler = new ServletContextHandler(server, contextPath);
        HttpServlet servlet = config.apply(handler);
        handler.addServlet(new ServletHolder(servlet), servletPath + "/*");

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
            Arguments.of(null, true),
            Arguments.of(null, false),
            Arguments.of(-1, true),
            Arguments.of(-1, false),
            Arguments.of(0, true),
            Arguments.of(0, false),
            Arguments.of(MAX_FORM_CONTENT_SIZE, true),
            Arguments.of(MAX_FORM_CONTENT_SIZE, false)
        );
    }

    @ParameterizedTest
    @MethodSource("formContentSizeScenarios")
    public void testMaxFormContentSizeExceeded(Integer maxFormContentSize, boolean withContentLength) throws Exception
    {
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

        byte[] key = "foo=".getBytes(StandardCharsets.US_ASCII);
        int length = (maxFormContentSize == null || maxFormContentSize < 0)
            ? ContextHandler.DEFAULT_MAX_FORM_CONTENT_SIZE
            : maxFormContentSize;
        // Avoid empty value.
        length = length + 1;
        byte[] value = new byte[length];
        Arrays.fill(value, (byte)'x');
        DeferredContentProvider content = new DeferredContentProvider(ByteBuffer.wrap(key), ByteBuffer.wrap(value));

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.POST)
            .path(contextPath + servletPath)
            .header(HttpHeader.CONTENT_TYPE, MimeTypes.Type.FORM_ENCODED.asString())
            .content(content)
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

        int expected = (maxFormContentSize != null && maxFormContentSize < 0)
            ? HttpStatus.OK_200
            : HttpStatus.BAD_REQUEST_400;
        assertEquals(expected, response.getStatus());
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
            ? ContextHandler.DEFAULT_MAX_FORM_KEYS
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
            .content(new FormContentProvider(formParams))
            .send();

        int expected = (maxFormKeys != null && maxFormKeys < 0)
            ? HttpStatus.OK_200
            : HttpStatus.BAD_REQUEST_400;
        assertEquals(expected, response.getStatus());
    }
}
