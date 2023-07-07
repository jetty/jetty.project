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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SizeLimitHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThan;

public class SizeLimitHandlerServletTest
{
    private static final int SIZE_LIMIT = 1024;
    private Server _server;
    private ServerConnector _connector;
    private HttpClient _client;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setInflateBufferSize(1024);
        SizeLimitHandler sizeLimitHandler = new SizeLimitHandler(SIZE_LIMIT, SIZE_LIMIT);
        sizeLimitHandler.setHandler(gzipHandler);
        contextHandler.insertHandler(sizeLimitHandler);

        contextHandler.addServlet(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                String requestContent = IO.toString(req.getInputStream());
                resp.getWriter().print(requestContent);
            }
        }, "/");

        _server.setHandler(contextHandler);
        _server.start();

        _client = new HttpClient();
        _client.start();
        _client.getContentDecoderFactories().clear();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testGzippedEcho() throws Exception
    {
        String content = "x".repeat(SIZE_LIMIT * 2);

        URI uri = URI.create("http://localhost:" + _connector.getLocalPort());
        ContentResponse response = _client.POST(uri)
            .headers(httpFields ->
            {
                httpFields.add(HttpHeader.CONTENT_ENCODING, "gzip");
                httpFields.add(HttpHeader.ACCEPT_ENCODING, "gzip");
            })
            .body(gzipContent(content)).send();

        assertThat(response.getHeaders().get(HttpHeader.CONTENT_ENCODING), equalTo("gzip"));
        assertThat(response.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH), lessThan((long)SIZE_LIMIT));

        ByteArrayInputStream inputStream = new ByteArrayInputStream(response.getContent());
        GZIPInputStream gzipInputStream = new GZIPInputStream(inputStream);
        String responseContent = IO.toString(gzipInputStream);
        assertThat(responseContent, equalTo(content));
        assertThat(responseContent.length(), greaterThan(SIZE_LIMIT));
    }

    @Test
    public void testNormalEcho() throws Exception
    {
        String content = "x".repeat(SIZE_LIMIT * 2);

        URI uri = URI.create("http://localhost:" + _connector.getLocalPort());
        ContentResponse response = _client.POST(uri)
            .body(new StringRequestContent(content)).send();

        System.err.println(response);
        System.err.println(response.getHeaders());
        System.err.println(response.getContentAsString());

        assertThat(response.getStatus(), equalTo(HttpStatus.PAYLOAD_TOO_LARGE_413));
    }

    @Test
    public void testGzipEchoNoAcceptEncoding() throws Exception
    {
        String content = "x".repeat(SIZE_LIMIT * 2);
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort());

        AtomicInteger contentReceived = new AtomicInteger();
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        _client.POST(uri)
            .headers(httpFields -> httpFields.add(HttpHeader.CONTENT_ENCODING, "gzip"))
            .body(gzipContent(content))
            .onResponseContentAsync((response, chunk, demander) ->
            {
                contentReceived.addAndGet(chunk.getByteBuffer().remaining());
                chunk.release();
                demander.run();
            }).send(result -> failure.complete(result.getFailure()));

        Throwable exception = failure.get(5, TimeUnit.SECONDS);
        assertThat(exception, instanceOf(EOFException.class));
        assertThat(contentReceived.get(), lessThan(SIZE_LIMIT));
    }

    public static Request.Content gzipContent(String content) throws Exception
    {
        byte[] bytes = content.getBytes();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream);
        gzipOutputStream.write(bytes);
        gzipOutputStream.close();
        return new BytesRequestContent(outputStream.toByteArray());
    }
}
