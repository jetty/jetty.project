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
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.FormRequestContent;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.SizeLimitHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SizeLimitHandlerServletTest
{
    private static final int SIZE_LIMIT = 1024;
    private Server _server;
    private ServerConnector _connector;
    private HttpClient _client;

    private void start(HttpServlet servlet) throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setMaxFormContentSize(10 * SIZE_LIMIT);
        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setInflateBufferSize(1024);
        SizeLimitHandler sizeLimitHandler = new SizeLimitHandler(SIZE_LIMIT, SIZE_LIMIT);
        sizeLimitHandler.setHandler(gzipHandler);
        contextHandler.insertHandler(sizeLimitHandler);

        contextHandler.addServlet(servlet, "/");

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
        start(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                String requestContent = IO.toString(req.getInputStream());
                resp.getWriter().print(requestContent);
            }
        });

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
        start(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                String requestContent = IO.toString(req.getInputStream());
                resp.getWriter().print(requestContent);
            }
        });

        String content = "x".repeat(SIZE_LIMIT * 2);

        URI uri = URI.create("http://localhost:" + _connector.getLocalPort());
        ContentResponse response = _client.POST(uri)
            .body(new StringRequestContent(content)).send();

        assertThat(response.getStatus(), equalTo(HttpStatus.PAYLOAD_TOO_LARGE_413));
    }

    @Test
    public void testChunkedEcho() throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                String requestContent = IO.toString(req.getInputStream());
                resp.getWriter().print(requestContent);
            }
        });

        String content = "x".repeat(SIZE_LIMIT);

        URI uri = URI.create("http://localhost:" + _connector.getLocalPort());
        Exchanger<Response> exchanger = new Exchanger<>();
        AsyncRequestContent asyncRequestContent = new AsyncRequestContent();
        _client.POST(uri).body(asyncRequestContent).send(result ->
        {
            try
            {
                exchanger.exchange(result.getResponse());
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        });

        try (Blocker.Callback callback = Blocker.callback())
        {
            asyncRequestContent.write(false, BufferUtil.toBuffer(content), callback);
            asyncRequestContent.flush();
            callback.block();
        }
        try (Blocker.Callback callback = Blocker.callback())
        {
            asyncRequestContent.write(true, BufferUtil.toBuffer(content), callback);
            asyncRequestContent.flush();
            callback.block();
        }
        asyncRequestContent.close();

        Response response = exchanger.exchange(null);
        assertThat(response.getStatus(), equalTo(HttpStatus.PAYLOAD_TOO_LARGE_413));
    }

    @Test
    public void testGzipEchoNoAcceptEncoding() throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                String requestContent = IO.toString(req.getInputStream());
                // The content will be buffered, and the implementation
                // will try to flush it, failing because of SizeLimitHandler.
                resp.getWriter().print(requestContent);
            }
        });

        String content = "x".repeat(SIZE_LIMIT * 2);
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort());

        StringBuilder contentReceived = new StringBuilder();
        CompletableFuture<Result> resultFuture = new CompletableFuture<>();
        _client.POST(uri)
            .headers(httpFields -> httpFields.add(HttpHeader.CONTENT_ENCODING, "gzip"))
            .body(gzipContent(content))
            .onResponseContentAsync((response, chunk, demander) ->
            {
                contentReceived.append(BufferUtil.toString(chunk.getByteBuffer()));
                demander.run();
            })
            .send(resultFuture::complete);

        Result result = resultFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertThat(result.getResponse().getStatus(), equalTo(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(contentReceived.toString(), containsString("Response body is too large"));
    }

    @Test
    public void testGzipEchoNoAcceptEncodingFlush() throws Exception
    {
        CountDownLatch flushFailureLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                String requestContent = IO.toString(req.getInputStream());
                // The content will be buffered.
                resp.getWriter().print(requestContent);
                try
                {
                    // The flush will fail because exceeds
                    // the SizeLimitHandler configuration.
                    resp.flushBuffer();
                }
                catch (Throwable x)
                {
                    flushFailureLatch.countDown();
                    throw x;
                }
            }
        });

        String content = "x".repeat(SIZE_LIMIT * 2);
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort());

        StringBuilder contentReceived = new StringBuilder();
        CompletableFuture<Result> resultFuture = new CompletableFuture<>();
        _client.POST(uri)
            .headers(httpFields -> httpFields.add(HttpHeader.CONTENT_ENCODING, "gzip"))
            .body(gzipContent(content))
            .onResponseContentAsync((response, chunk, demander) ->
            {
                contentReceived.append(BufferUtil.toString(chunk.getByteBuffer()));
                demander.run();
            })
            .send(resultFuture::complete);

        assertTrue(flushFailureLatch.await(5, TimeUnit.SECONDS));

        Result result = resultFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertThat(result.getResponse().getStatus(), equalTo(HttpStatus.INTERNAL_SERVER_ERROR_500));
        assertThat(contentReceived.toString(), containsString("Response body is too large"));
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

    @Test
    public void testFormSize() throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.getWriter().print(req.getParameterMap());
            }
        });

        String key = "x".repeat(SIZE_LIMIT * 2);
        Fields fields = new Fields();
        fields.add(key, "value");

        URI uri = URI.create("http://localhost:" + _connector.getLocalPort());
        ContentResponse response = _client.POST(uri)
            .body(new FormRequestContent(fields)).send();

        assertThat(response.getStatus(), equalTo(HttpStatus.PAYLOAD_TOO_LARGE_413));
    }
}
