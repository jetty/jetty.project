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

package org.eclipse.jetty.client.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.eclipse.jetty.client.EmptyServerHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InputStreamContentTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void start(Handler handler) throws Exception
    {
        startServer(handler);
        startClient();
    }

    private void startServer(Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    private void startClient() throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient();
        client.setExecutor(clientThreads);
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

    /**
     * need public access to avoid jpms issue
     */
    public static List<BiConsumer<Request, InputStream>> content()
    {
        return List.of(
            (request, stream) -> request.body(new InputStreamRequestContent(stream)),
            (request, stream) -> request.body(new InputStreamRequestContent(stream))
        );
    }

    @ParameterizedTest
    @MethodSource("content")
    public void testInputStreamEmpty(BiConsumer<Request, InputStream> setContent) throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response) throws Exception
            {
                serverLatch.countDown();
                if (Content.Source.asByteBuffer(request).hasRemaining())
                    throw new IOException();
            }
        });

        CountDownLatch closeLatch = new CountDownLatch(1);
        InputStream stream = new InputStream()
        {
            @Override
            public int read()
            {
                return -1;
            }

            @Override
            public void close() throws IOException
            {
                super.close();
                closeLatch.countDown();
            }
        };

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS);
        setContent.accept(request, stream);
        ContentResponse response = request.send();

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertEquals(response.getStatus(), HttpStatus.OK_200);
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("content")
    public void testInputStreamThrowing(BiConsumer<Request, InputStream> setContent) throws Exception
    {
        start(new EmptyServerHandler());

        CountDownLatch closeLatch = new CountDownLatch(1);
        InputStream stream = new InputStream()
        {
            @Override
            public int read() throws IOException
            {
                throw new IOException();
            }

            @Override
            public void close() throws IOException
            {
                super.close();
                closeLatch.countDown();
            }
        };

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS);
        setContent.accept(request, stream);

        assertThrows(ExecutionException.class, request::send);
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("content")
    public void testInputStreamThrowingAfterFirstRead(BiConsumer<Request, InputStream> setContent) throws Exception
    {
        byte singleByteContent = 0;
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response) throws Exception
            {
                ByteBuffer buffer = Content.Source.asByteBuffer(request);
                assertTrue(buffer.hasRemaining());
                assertEquals(singleByteContent, buffer.get());
                serverLatch.countDown();
            }
        });

        CountDownLatch closeLatch = new CountDownLatch(1);
        InputStream stream = new InputStream()
        {
            private int reads;

            @Override
            public int read() throws IOException
            {
                if (++reads == 1)
                    return singleByteContent;
                throw new IOException();
            }

            @Override
            public void close() throws IOException
            {
                super.close();
                closeLatch.countDown();
            }
        };

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS);
        setContent.accept(request, stream);

        assertThrows(ExecutionException.class, request::send);
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("content")
    public void testInputStreamWithSmallContent(BiConsumer<Request, InputStream> setContent) throws Exception
    {
        testInputStreamWithContent(setContent, new byte[1024]);
    }

    @ParameterizedTest
    @MethodSource("content")
    public void testInputStreamWithLargeContent(BiConsumer<Request, InputStream> setContent) throws Exception
    {
        testInputStreamWithContent(setContent, new byte[64 * 1024 * 1024]);
    }

    private void testInputStreamWithContent(BiConsumer<Request, InputStream> setContent, byte[] content) throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response) throws Exception
            {
                serverLatch.countDown();
                Content.Source.consumeAll(request);
            }
        });

        CountDownLatch closeLatch = new CountDownLatch(1);
        ByteArrayInputStream stream = new ByteArrayInputStream(content)
        {
            @Override
            public void close() throws IOException
            {
                super.close();
                closeLatch.countDown();
            }
        };

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS);
        setContent.accept(request, stream);
        ContentResponse response = request.send();

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertEquals(response.getStatus(), HttpStatus.OK_200);
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }
}
