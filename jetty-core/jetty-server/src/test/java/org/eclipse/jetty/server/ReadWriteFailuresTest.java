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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReadWriteFailuresTest
{
    private Server server;
    private LocalConnector connector;

    private void start(Handler handler) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testReadFailureDoesNotImpactSubsequentWrite() throws Exception
    {
        long idleTimeout = 1000;
        String content = "no impact :)";
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Upon idle timeout, the demand callback is invoked.
                request.demand(() ->
                {
                    try
                    {
                        Content.Chunk chunk = request.read();
                        assertTrue(Content.Chunk.isFailure(chunk, false));

                        response.setStatus(HttpStatus.ACCEPTED_202);
                        Content.Sink.write(response, true, content, callback);
                    }
                    catch (Throwable x)
                    {
                        callback.failed(x);
                    }
                });
                return true;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        String request = """
            POST / HTTP/1.1
            Host: localhost
            Content-Length: 1
            
            """;
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request, 5, TimeUnit.SECONDS));

        assertEquals(HttpStatus.ACCEPTED_202, response.getStatus());
        assertEquals(content, response.getContent());
    }

    @Test
    public void testWriteFailureDoesNotImpactSubsequentReads() throws Exception
    {
        String content = "0123456789";
        Throwable writeFailure = new IOException();
        CountDownLatch latch = new CountDownLatch(1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                request.addHttpStreamWrapper(stream -> new HttpStream.Wrapper(stream)
                {
                    @Override
                    public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
                    {
                        callback.failed(writeFailure);
                    }
                });

                // First write must fail.
                Callback.Completable completable1 = new Callback.Completable();
                Content.Sink.write(response, false, "first_write", completable1);
                Throwable writeFailure1 = assertThrows(ExecutionException.class, () -> completable1.get(5, TimeUnit.SECONDS)).getCause();
                assertSame(writeFailure, writeFailure1);

                // Try a second write, it should fail.
                Callback.Completable completable2 = new Callback.Completable();
                Content.Sink.write(response, false, "second_write", completable2);
                Throwable writeFailure2 = assertThrows(ExecutionException.class, () -> completable2.get(5, TimeUnit.SECONDS)).getCause();
                assertSame(writeFailure1, writeFailure2);

                // Now try to read.
                String read = Content.Source.asString(request);
                assertEquals(content, read);

                latch.countDown();

                callback.succeeded();
                return true;
            }
        });

        String request = """
            POST / HTTP/1.1
            Host: localhost
            Content-Length: %d
            
            %s
            """.formatted(content.length(), content);
        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest(request))
        {
            endPoint.waitUntilClosedOrIdleFor(5, TimeUnit.SECONDS);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testFailureFailsPendingWrite(boolean fatal) throws Exception
    {
        long idleTimeout = 1000;
        CountDownLatch latch = new CountDownLatch(1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                request.addHttpStreamWrapper(stream -> new HttpStream.Wrapper(stream)
                {
                    @Override
                    public void send(MetaData.Request request, MetaData.Response response, boolean last, ByteBuffer content, Callback callback)
                    {
                        // Do nothing to make the write pending.
                    }
                });

                request.addIdleTimeoutListener(x -> fatal);

                Callback.Completable completable1 = new Callback.Completable();
                Content.Sink.write(response, true, "hello world", completable1);
                Throwable writeFailure1 = assertThrows(ExecutionException.class, () -> completable1.get(2 * idleTimeout, TimeUnit.MILLISECONDS)).getCause();

                // Verify that further writes are failed.
                Callback.Completable completable2 = new Callback.Completable();
                Content.Sink.write(response, true, "hello world", completable2);
                Throwable writeFailure2 = assertThrows(ExecutionException.class, () -> completable2.get(5, TimeUnit.SECONDS)).getCause();
                assertSame(writeFailure1, writeFailure2);

                latch.countDown();

                callback.failed(writeFailure1);
                return true;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        String request = """
            POST / HTTP/1.1
            Host: localhost
            Content-Length: 1
            
            """;
        try (LocalConnector.LocalEndPoint endPoint = connector.executeRequest(request))
        {
            endPoint.waitUntilClosedOrIdleFor(5, TimeUnit.SECONDS);
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testDemandCallbackThrows() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addFailureListener(callback::failed);
                request.demand(() ->
                {
                    // Results in a fatal failure, and failure listener is invoked.
                    throw new QuietException.RuntimeException();
                });
                return true;
            }
        });

        String content = "hello world";
        String request = """
            POST / HTTP/1.1
            Host: localhost
            Content-Length: %d
            
            %s
            """.formatted(content.length(), content);
        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(request, 5, TimeUnit.SECONDS));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus());
        assertThat(response.getContent(), containsString("QuietException"));
    }
}
